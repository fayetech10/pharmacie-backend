package com.csu.pharmacie.identite.service;

import com.csu.pharmacie.entity.District;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.csu.pharmacie.commun.client.dto.DistrictDto;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.commun.export.LecteurExcel;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.identite.depot.DepotDistrict;
import com.csu.pharmacie.identite.depot.DepotRegion;
import com.csu.pharmacie.identite.depot.DepotStructure;
import com.csu.pharmacie.identite.dto.ResultatImport;
import com.csu.pharmacie.entity.Region;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.User;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gestion des districts sanitaires — la maille de consolidation introduite au §9.
 */
@Service
@RequiredArgsConstructor
public class ServiceDistrict {

    private final DepotDistrict depotDistrict;
    private final DepotRegion depotRegion;
    private final DepotStructure depotStructure;
    private final ServiceUtilisateur serviceUtilisateur;
    private final ServiceStructure serviceStructure;
    private final CodeDistrict codeDistrict;

    /**
     * District créé et accès du compte qui le pilote, renvoyés une seule fois à l'écran.
     * {@code centre} : le centre de santé siège créé avec lui (option « c'est aussi un centre »).
     */
    public record DistrictCree(District district, ServiceUtilisateur.AccesCompte acces, StructureSanitaire centre) {
    }

    public List<District> lister(String regionId) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        // Un service régional ne voit que ses districts ; un compte district, que le sien.
        String regionImposee = utilisateur.getRole() == Role.SERVICE_REGIONAL
                ? utilisateur.getRegionId() : regionId;

        return depotDistrict.findAll().stream()
                .filter(d -> utilisateur.getRole() != Role.DISTRICT
                        || d.getId().equals(utilisateur.getDistrictId()))
                .filter(d -> regionImposee == null || regionImposee.isBlank()
                        || regionImposee.equals(d.getRegionId()))
                .sorted(Comparator.comparing(d -> d.getNom() == null ? "" : d.getNom()))
                .toList();
    }

    public District parId(String id) {
        return depotDistrict.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("District introuvable : " + id));
    }

    @Transactional
    public DistrictCree creer(District district) {
        return creer(district, false, null);
    }

    /**
     * Crée le district et — si {@code avecCentre} — le centre de santé qui EST ce district
     * (siège), dans la même transaction : symétrique de la case « Siège du district » côté
     * structure. Le centre reçoit le nom donné ou un nom dérivé du district, la région, le
     * téléphone et l'e-mail du district ; son compte de connexion porte les droits du district,
     * donc aucun compte District séparé n'est créé dans ce cas.
     */
    @Transactional
    public DistrictCree creer(District district, boolean avecCentre, String nomCentre) {
        normaliser(district);
        // Région d'abord : c'est elle qui compose le code attribué automatiquement.
        imposerRegion(district);
        verifierRegion(district);
        if (district.getCode() == null || district.getCode().isBlank()) {
            // Code automatique (08/10/2026) : {CODE_RÉGION}DT{NN}, non saisi à l'écran.
            district.setCode(codeDistrict.generer(district.getRegionId()));
        } else if (depotDistrict.existsByCodeIgnoreCase(district.getCode())) {
            throw ExceptionMetier.conflit("Un district porte déjà le code « " + district.getCode() + " »");
        }
        district.setId(null);
        District enregistre = depotDistrict.save(district);

        if (avecCentre) {
            // Le centre de santé siège : c'est son compte qui pilote le district.
            String email = serviceUtilisateur.emailDisponible(enregistre.getEmail()) ? enregistre.getEmail() : null;
            StructureSanitaire centre = serviceStructure.creer(StructureSanitaire.builder()
                    .nom(nomCentre != null && !nomCentre.isBlank() ? nomCentre.trim() : nomCentreDuDistrict(enregistre.getNom()))
                    .typeStructure("CS")
                    .regionId(enregistre.getRegionId())
                    .districtId(enregistre.getId())
                    .telephone(enregistre.getTelephone())
                    .email(email)
                    .siegeDistrict(true)
                    .build());
            String identifiant = serviceUtilisateur.identifiantCompteStructure(centre);
            ServiceUtilisateur.AccesCompte acces = identifiant == null ? null
                    : new ServiceUtilisateur.AccesCompte(centre.getResponsableId(), identifiant,
                            ServiceUtilisateur.MOT_DE_PASSE_GENERIQUE, true);
            return new DistrictCree(enregistre, acces, centre);
        }

        // Un district sans compte est un district dont personne ne peut se connecter : l'e-mail
        // saisi à l'écran restait un simple contact. Le compte est créé avec lui.
        ServiceUtilisateur.CompteCree compte = serviceUtilisateur.creerCompteDistrict(enregistre);
        ServiceUtilisateur.AccesCompte acces = null;
        if (compte != null) {
            enregistre.setResponsableId(compte.utilisateur().getId());
            enregistre = depotDistrict.save(enregistre);
            User utilisateur = compte.utilisateur();
            acces = new ServiceUtilisateur.AccesCompte(utilisateur.getId(),
                    utilisateur.getUsername() != null ? utilisateur.getUsername() : utilisateur.getEmail(),
                    compte.motDePasseTemporaire(), true);
        }
        return new DistrictCree(enregistre, acces, null);
    }

    /** « District sanitaire de Khombole » → « Centre de santé de Khombole » ; sinon « Centre de santé de {nom} ». */
    private static String nomCentreDuDistrict(String nomDistrict) {
        String nom = nomDistrict == null ? "" : nomDistrict.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^district\\s+sanitaire\\s*", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
                .matcher(nom);
        if (m.find()) {
            String reste = nom.substring(m.end()).trim();
            return reste.isEmpty() ? "Centre de santé" : "Centre de santé " + reste;
        }
        return nom.isEmpty() ? "Centre de santé" : "Centre de santé de " + nom;
    }

    /**
     * Accès du district (identifiant + mot de passe par défaut) : remis à zéro, ou compte créé
     * s'il n'en a pas — rattrape les districts créés ou importés avant la création automatique.
     */
    @Transactional
    public ServiceUtilisateur.AccesCompte reinitialiserAcces(String id) {
        District district = parId(id);
        verifierPerimetre(district);
        ServiceUtilisateur.AccesCompte acces = serviceUtilisateur.reinitialiserCompteDistrict(district);
        if (acces.compteCree()) {
            district.setResponsableId(acces.compteId());
            depotDistrict.save(district);
        }
        return acces;
    }

    @Transactional
    public District modifier(String id, District modifications) {
        District district = parId(id);
        verifierPerimetre(district);
        normaliser(modifications);
        if (modifications.getCode() != null && !modifications.getCode().equalsIgnoreCase(district.getCode())) {
            if (depotDistrict.existsByCodeIgnoreCase(modifications.getCode())) {
                throw ExceptionMetier.conflit("Un district porte déjà le code « " + modifications.getCode() + " »");
            }
            district.setCode(modifications.getCode());
        }
        if (modifications.getNom() != null) {
            district.setNom(modifications.getNom());
        }
        district.setRegionId(modifications.getRegionId());
        imposerRegion(district);
        district.setResponsableId(modifications.getResponsableId());
        district.setTelephone(modifications.getTelephone());
        district.setEmail(modifications.getEmail());
        verifierRegion(district);
        return depotDistrict.save(district);
    }

    /**
     * Un district encore peuplé ne peut pas être désactivé : ses structures perdraient
     * leur échelon de consolidation et disparaîtraient des situations globales.
     */
    @Transactional
    public District basculerActivation(String id, boolean actif) {
        District district = parId(id);
        verifierPerimetre(district);
        if (!actif) {
            long structures = depotStructure.countByDistrictId(id);
            if (structures > 0) {
                throw ExceptionMetier.conflit("Impossible de désactiver le district « " + district.getNom()
                        + " » : " + structures + " structure(s) y sont rattachée(s).");
            }
        }
        district.setActif(actif);
        // Le compte de connexion suit le district.
        serviceUtilisateur.basculerComptesDistrict(id, actif);
        return depotDistrict.save(district);
    }

    @Transactional
    public ResultatImport importer(MultipartFile fichier) {
        ResultatImport resultat = new ResultatImport();
        List<LecteurExcel.LigneImport> lignes;
        try {
            lignes = LecteurExcel.lire(fichier.getInputStream());
        } catch (IOException e) {
            throw ExceptionMetier.invalide("Lecture du fichier impossible : " + e.getMessage());
        }
        resultat.setLignesLues(lignes.size());

        Map<String, String> regionsParCode = new HashMap<>();
        for (Region region : depotRegion.findAll()) {
            if (region.getCode() != null) {
                regionsParCode.put(region.getCode().toLowerCase(), region.getId());
            }
            if (region.getNom() != null) {
                regionsParCode.put(region.getNom().toLowerCase(), region.getId());
            }
        }

        for (LecteurExcel.LigneImport ligne : lignes) {
            String code = ligne.texte("code", "codedistrict");
            String nom = ligne.texte("nom", "district", "libelle");
            if (code == null || nom == null) {
                resultat.erreur(ligne.numeroLigne(), "colonnes « Code » et « Nom » obligatoires");
                continue;
            }
            String regionRecherchee = ligne.texte("region", "coderegion");
            String regionId = regionRecherchee == null ? null
                    : regionsParCode.get(regionRecherchee.trim().toLowerCase());
            if (regionRecherchee != null && regionId == null) {
                resultat.erreur(ligne.numeroLigne(), "région « " + regionRecherchee + " » inconnue");
                continue;
            }
            String regionAppelant = regionDuServiceRegional();
            if (regionAppelant != null) {
                if (regionId != null && !regionId.equals(regionAppelant)) {
                    resultat.erreur(ligne.numeroLigne(), "le district n'est pas dans votre région");
                    continue;
                }
                regionId = regionAppelant;
            }

            Optional<District> existant = depotDistrict.findByCodeIgnoreCase(code);
            if (existant.isPresent() && regionAppelant != null
                    && !regionAppelant.equals(existant.get().getRegionId())) {
                resultat.erreur(ligne.numeroLigne(), "le code « " + code + " » est celui d'un district d'une autre région");
                continue;
            }
            District district = existant.orElseGet(District::new);
            district.setCode(code.trim().toUpperCase());
            district.setNom(nom.trim());
            district.setRegionId(regionId);
            district.setTelephone(ligne.texte("telephone", "tel"));
            district.setEmail(ligne.texte("email", "mail"));
            district.setActif(true);
            depotDistrict.save(district);

            if (existant.isPresent()) {
                resultat.setMisAJour(resultat.getMisAJour() + 1);
            } else {
                resultat.setCrees(resultat.getCrees() + 1);
            }
        }
        return resultat;
    }

    public DistrictDto versDto(District district) {
        Region region = district.getRegionId() == null ? null
                : depotRegion.findById(district.getRegionId()).orElse(null);
        return DistrictDto.builder()
                .id(district.getId())
                .code(district.getCode())
                .nom(district.getNom())
                .regionId(district.getRegionId())
                .regionNom(region == null ? null : region.getNom())
                .actif(district.isActif())
                .build();
    }

    private void normaliser(District district) {
        if (district.getCode() != null) {
            district.setCode(district.getCode().trim().toUpperCase());
        }
        if (district.getNom() != null) {
            district.setNom(district.getNom().trim());
        }
        if (district.getRegionId() != null && district.getRegionId().isBlank()) {
            district.setRegionId(null);
        }
    }

    /**
     * Chaque service régional configure les districts de SA région (cahier « Espace PS »,
     * §2) : la région d'un district qu'il crée ou modifie est la sienne, quoi qu'il envoie.
     */
    private void imposerRegion(District district) {
        String region = regionDuServiceRegional();
        if (region != null) {
            district.setRegionId(region);
        }
    }

    /** Un service régional n'agit pas sur le district d'une autre région. */
    private void verifierPerimetre(District district) {
        String region = regionDuServiceRegional();
        if (region != null && !region.equals(district.getRegionId())) {
            throw ExceptionMetier.interdit("Le district « " + district.getNom() + " » n'appartient pas à votre région.");
        }
    }

    /** Région de l'appelant s'il est service régional ; {@code null} pour le niveau central. */
    private static String regionDuServiceRegional() {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        if (utilisateur.getRole() != Role.SERVICE_REGIONAL) {
            return null;
        }
        if (utilisateur.getRegionId() == null || utilisateur.getRegionId().isBlank()) {
            throw ExceptionMetier.interdit("Votre compte de service régional n'est rattaché à aucune région.");
        }
        return utilisateur.getRegionId();
    }

    private void verifierRegion(District district) {
        if (district.getRegionId() == null) {
            throw ExceptionMetier.invalide("La région de rattachement du district est obligatoire");
        }
        if (depotRegion.findById(district.getRegionId()).isEmpty()) {
            throw ExceptionMetier.invalide("Région introuvable");
        }
    }
}
