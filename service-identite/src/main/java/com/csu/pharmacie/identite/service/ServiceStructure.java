package com.csu.pharmacie.identite.service;

import com.csu.pharmacie.entity.District;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.TypeStructure;
import com.csu.pharmacie.commun.export.LecteurExcel;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.CompteurNumero;
import com.csu.pharmacie.identite.depot.DepotCompteurNumero;
import com.csu.pharmacie.identite.depot.DepotDistrict;
import com.csu.pharmacie.identite.depot.DepotRegion;
import com.csu.pharmacie.identite.depot.DepotStructure;
import com.csu.pharmacie.identite.dto.ResultatImport;
import com.csu.pharmacie.entity.Region;
import com.csu.pharmacie.entity.StructureSanitaire;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gestion des structures sanitaires et de leurs rattachements (§6 du document).
 *
 * <p>Deux invariants sont défendus ici, parce qu'ils cassent silencieusement tout le
 * reste s'ils sont violés :</p>
 * <ul>
 *   <li>seul un poste de santé peut avoir un centre de rattachement, et ce centre doit
 *       réellement polariser (CS ou EPS) — sinon les factures du poste n'ont personne
 *       pour les valider et restent bloquées en {@code SOUMISE_CS} ;</li>
 *   <li>le district d'une structure doit appartenir à la région déclarée — sinon la
 *       consolidation district et les tableaux de bord régionaux ne totalisent pas la
 *       même chose.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ServiceStructure {

    private final DepotStructure depotStructure;
    private final DepotDistrict depotDistrict;
    private final DepotRegion depotRegion;
    private final DepotCompteurNumero depotCompteurNumero;
    private final ServiceUtilisateur serviceUtilisateur;
    private final CodeDistrict codeDistrict;

    // ────────────────────────────── lecture ──────────────────────────────

    /** Structures visibles par l'utilisateur courant, filtrées selon son échelon. */
    public List<StructureSanitaire> lister(String districtId, String regionId, TypeStructure type,
                                           String recherche) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();

        List<StructureSanitaire> structures = depotStructure.findAll();

        // Un compte de district ou de région ne voit jamais au-delà de son périmètre,
        // même s'il demande explicitement autre chose.
        String districtImpose = utilisateur.getRole() == Role.DISTRICT ? utilisateur.getDistrictId() : districtId;
        String regionImposee = utilisateur.getRole() == Role.SERVICE_REGIONAL ? utilisateur.getRegionId() : regionId;

        String requete = recherche == null ? "" : recherche.trim().toLowerCase();

        return structures.stream()
                .filter(s -> districtImpose == null || districtImpose.isBlank()
                        || districtImpose.equals(s.getDistrictId()))
                .filter(s -> regionImposee == null || regionImposee.isBlank()
                        || regionImposee.equals(s.getRegionId()))
                .filter(s -> type == null || type == s.typeEffectif())
                .filter(s -> requete.isEmpty()
                        || (s.getNom() != null && s.getNom().toLowerCase().contains(requete))
                        || (s.getCode() != null && s.getCode().toLowerCase().contains(requete)))
                .sorted(Comparator.comparing(s -> s.getNom() == null ? "" : s.getNom()))
                .toList();
    }

    public StructureSanitaire parId(String id) {
        return depotStructure.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Structure sanitaire introuvable : " + id));
    }

    /** Structures dont l'agent BCSU référent est cet utilisateur. */
    public List<StructureSanitaire> parBcsu(String bcsuId) {
        return depotStructure.findByBcsuId(bcsuId).stream()
                .sorted(Comparator.comparing(s -> s.getNom() == null ? "" : s.getNom()))
                .toList();
    }

    /** Postes de santé polarisés par un centre. */
    public List<StructureSanitaire> postesRattaches(String centreId) {
        return depotStructure.findByStructureRattachementId(centreId).stream()
                .sorted(Comparator.comparing(s -> s.getNom() == null ? "" : s.getNom()))
                .toList();
    }

    // ────────────────────────────── écriture ──────────────────────────────

    @Transactional
    public StructureSanitaire creer(StructureSanitaire structure) {
        return creer(structure, null);
    }

    @Transactional
    public StructureSanitaire creer(StructureSanitaire structure, String motDePasseSouhaite) {
        normaliser(structure);
        UtilisateurAuthentifie courant = ContexteUtilisateur.courant();
        if (courant.getRole() == Role.SERVICE_REGIONAL
                && (structure.getRegionId() == null || structure.getRegionId().isBlank())) {
            // Comme le cœur : une structure créée par un service régional est de sa région.
            structure.setRegionId(courant.getRegionId());
        }
        // Centre « siège du district » sans district choisi : c'est lui QUI EST le district —
        // on crée le district à partir du centre au lieu d'exiger un rattachement.
        creerDistrictDuSiege(structure);
        // Avant le code, car c'est lui qui déduit la région du district — or le code s'en sert.
        verifierCoherence(structure);
        verifierSiegeDistrict(structure);
        verifierPerimetre(structure);

        if (structure.getCode() == null || structure.getCode().isBlank()) {
            structure.setCode(genererCode(structure));
        } else if (depotStructure.existsByCodeIgnoreCase(structure.getCode())) {
            throw ExceptionMetier.conflit("Une structure porte déjà le code « " + structure.getCode() + " »");
        }

        structure.setId(null);
        StructureSanitaire enregistree = depotStructure.save(structure);

        // Une structure sans compte est une structure dont personne ne peut se connecter :
        // l'email et le mot de passe saisis à l'écran resteraient lettre morte.
        ServiceUtilisateur.CompteCree compte =
                serviceUtilisateur.creerCompteStructure(enregistree, structure.getEmail(), motDePasseSouhaite);
        if (compte != null) {
            enregistree.setResponsableId(compte.utilisateur().getId());
            enregistree = depotStructure.save(enregistree);
        }
        retirerAutresSieges(enregistree);
        return enregistree;
    }

    /**
     * Code attribué automatiquement, au format {@code {RÉGION}{TYPE}{NN}} — DKCS01, DKEPS01.
     *
     * <p>Le formulaire ne demande pas ce code, et c'est volontaire : tout ce qu'il faut pour
     * le composer — le type et la région — y est déjà saisi. L'exiger revenait à bloquer la
     * création sur un champ qui n'existe pas à l'écran.</p>
     *
     * <p><b>Le format n'est pas libre.</b> Les numéros de lettres de garantie et de bons de
     * commande se dérivent du code en lui retirant son rang : {@code DKCS01 → DKCS}, d'où
     * {@code G-DKCS-0001}. Un séparateur ou un rang de longueur différente casserait cette
     * extraction. C'est la convention de {@code NumeroSequenceService} côté cœur, reprise
     * ici à l'identique — compteur compris, sans quoi les deux applications attribueraient
     * les mêmes codes chacune de son côté.</p>
     *
     * <p>La séquence est permanente : jamais remise à zéro, un code identifie une structure
     * pour toujours. La boucle enjambe un rang déjà pris par un code saisi à la main.</p>
     */
    private String genererCode(StructureSanitaire structure) {
        String region = structure.getRegionId() == null ? null
                : depotRegion.findById(structure.getRegionId()).map(Region::getCode).orElse(null);
        if (region == null || region.isBlank()) {
            throw ExceptionMetier.invalide("Le code est calculé à partir de la région : "
                    + "renseignez la région ou le district de la structure.");
        }

        String codeRegion = alphanumerique(region, "SN");
        String codeType = alphanumerique(structure.typeEffectif().name(), "CS");

        String code;
        do {
            long rang = prochainRang("ST:" + codeRegion + ":" + codeType);
            code = String.format("%s%s%02d", codeRegion, codeType, rang);
        } while (depotStructure.existsByCodeIgnoreCase(code));
        return code;
    }

    /** Incrémente le compteur partagé avec le cœur, ligne verrouillée le temps de la transaction. */
    private long prochainRang(String cle) {
        CompteurNumero compteur = depotCompteurNumero.findByCleForUpdate(cle)
                .orElseGet(() -> depotCompteurNumero.saveAndFlush(new CompteurNumero(cle, 0L)));
        compteur.setValeur(compteur.getValeur() + 1);
        depotCompteurNumero.save(compteur);
        return compteur.getValeur();
    }

    /** Tirets et espaces casseraient l'extraction des initiales dans les numéros LG/BC. */
    private static String alphanumerique(String valeur, String defaut) {
        if (valeur == null) {
            return defaut;
        }
        String normalise = valeur.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        return normalise.isEmpty() ? defaut : normalise;
    }

    @Transactional
    public StructureSanitaire modifier(String id, StructureSanitaire modifications) {
        StructureSanitaire structure = parId(id);
        verifierPerimetre(structure);
        normaliser(modifications);

        if (modifications.getCode() != null && !modifications.getCode().equalsIgnoreCase(structure.getCode())) {
            if (depotStructure.existsByCodeIgnoreCase(modifications.getCode())) {
                throw ExceptionMetier.conflit("Une structure porte déjà le code « " + modifications.getCode() + " »");
            }
            structure.setCode(modifications.getCode());
        }
        if (modifications.getNom() != null) {
            structure.setNom(modifications.getNom());
        }
        if (modifications.getTypeStructure() != null && !modifications.getTypeStructure().isBlank()) {
            structure.setTypeStructure(modifications.getTypeStructure());
        }
        structure.setStructureRattachementId(modifications.getStructureRattachementId());
        structure.setDistrictId(modifications.getDistrictId());
        structure.setRegionId(modifications.getRegionId());
        structure.setAdresse(modifications.getAdresse());
        structure.setTelephone(modifications.getTelephone());
        structure.setEmail(modifications.getEmail());
        // Le formulaire ne renvoie jamais le responsable : l'écraser sans condition le
        // remettait à vide à chaque modification, et l'export perdait le compte de la structure.
        if (modifications.getResponsableId() != null && !modifications.getResponsableId().isBlank()) {
            structure.setResponsableId(modifications.getResponsableId());
        }
        structure.setBcsuId(modifications.getBcsuId());
        // Absent d'un ancien client : on ne touche pas à la désignation existante.
        if (modifications.getSiegeDistrict() != null) {
            structure.setSiegeDistrict(modifications.getSiegeDistrict());
        }

        creerDistrictDuSiege(structure);
        verifierCoherence(structure);
        verifierSiegeDistrict(structure);
        // Une modification ne fait pas sortir une structure de la région du service régional.
        verifierPerimetre(structure);
        StructureSanitaire enregistree = depotStructure.save(structure);
        propagerAuxPostes(enregistree);
        retirerAutresSieges(enregistree);
        return enregistree;
    }

    /**
     * Centre de santé coché « siège du district » SANS district choisi : le centre est
     * lui-même le district qui polarise ses postes — on crée ce district (même région, nom
     * dérivé du centre, code {@code {RÉGION}DT{NN}}) et on y rattache le centre. Si un district
     * est déjà choisi, rien n'est créé : le centre devient le siège de ce district existant.
     *
     * <p>Aucun compte district séparé n'est créé : c'est le compte du centre qui porte les
     * droits du district (double casquette).</p>
     */
    private void creerDistrictDuSiege(StructureSanitaire structure) {
        if (!Boolean.TRUE.equals(structure.getSiegeDistrict())
                || structure.typeEffectif() != TypeStructure.CS
                || (structure.getDistrictId() != null && !structure.getDistrictId().isBlank())) {
            return;
        }
        if (structure.getRegionId() == null || structure.getRegionId().isBlank()) {
            throw ExceptionMetier.invalide("Choisissez la région du centre : le district créé à partir de lui y sera rattaché.");
        }

        District district = depotDistrict.save(District.builder()
                .code(codeDistrict.generer(structure.getRegionId()))
                .nom(nomDistrictDuSiege(structure.getNom()))
                .regionId(structure.getRegionId())
                .telephone(structure.getTelephone())
                .email(structure.getEmail())
                .actif(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        structure.setDistrictId(district.getId());
    }

    /** « Centre de santé de Khombole » → « District sanitaire de Khombole » ; sinon « District sanitaire de {nom} ». */
    private static String nomDistrictDuSiege(String nomCentre) {
        String nom = nomCentre == null ? "" : nomCentre.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^centre\\s+de\\s+sant[eé]\\s*", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
                .matcher(nom);
        if (m.find()) {
            String reste = nom.substring(m.end()).trim();
            return reste.isEmpty() ? "District sanitaire" : "District sanitaire " + reste;
        }
        return nom.isEmpty() ? "District sanitaire" : "District sanitaire de " + nom;
    }

    /**
     * « Siège du district » : réservé à un centre de santé rattaché à un district. Un siège
     * désigné ailleurs que sur un CS serait sans effet — on le refuse plutôt que de l'ignorer.
     */
    private void verifierSiegeDistrict(StructureSanitaire structure) {
        if (!Boolean.TRUE.equals(structure.getSiegeDistrict())) {
            structure.setSiegeDistrict(false);
            return;
        }
        if (structure.typeEffectif() != com.csu.pharmacie.entity.TypeStructure.CS) {
            throw ExceptionMetier.invalide("Seul un centre de santé peut être désigné siège du district.");
        }
        if (structure.getDistrictId() == null || structure.getDistrictId().isBlank()) {
            throw ExceptionMetier.invalide("Rattachez d'abord ce centre à un district pour le désigner siège du district.");
        }
    }

    /** Un seul siège par district : désigner ce centre retire la désignation des autres. */
    private void retirerAutresSieges(StructureSanitaire siege) {
        if (!siege.estSiegeDistrict()) {
            return;
        }
        for (StructureSanitaire autre : depotStructure.findByDistrictId(siege.getDistrictId())) {
            if (!autre.getId().equals(siege.getId()) && Boolean.TRUE.equals(autre.getSiegeDistrict())) {
                autre.setSiegeDistrict(false);
                depotStructure.save(autre);
            }
        }
    }

    /**
     * Activation / désactivation (§6). On ne supprime pas une structure : ses factures
     * passées doivent rester rattachables. Désactiver un centre qui polarise encore des
     * postes actifs bloquerait leur circuit de validation — c'est refusé.
     *
     * <p>Les comptes de connexion de la structure suivent. Sans cette cascade, désactiver ne
     * fermait rien : la connexion ne regarde que l'état du compte, pas celui de la structure.</p>
     */
    @Transactional
    public StructureSanitaire basculerActivation(String id, boolean actif) {
        StructureSanitaire structure = parId(id);
        verifierPerimetre(structure);
        if (!actif && structure.polarise()) {
            long postesActifs = depotStructure.findByStructureRattachementId(id).stream()
                    .filter(StructureSanitaire::isActif)
                    .count();
            if (postesActifs > 0) {
                throw ExceptionMetier.conflit("Impossible de désactiver « " + structure.getNom() + " » : "
                        + postesActifs + " poste(s) de santé y sont encore rattaché(s). "
                        + "Rattachez-les à un autre centre au préalable.");
            }
        }
        structure.setActif(actif);
        StructureSanitaire enregistree = depotStructure.save(structure);
        serviceUtilisateur.basculerComptesStructure(id, actif);
        return enregistree;
    }

    /**
     * Nouveau mot de passe temporaire pour le compte de connexion de la structure (créé s'il
     * manque), renvoyé une seule fois pour être communiqué à la structure.
     */
    @Transactional
    public ServiceUtilisateur.AccesCompte reinitialiserMotDePasseCompte(String id) {
        StructureSanitaire structure = parId(id);
        verifierPerimetre(structure);
        ServiceUtilisateur.AccesCompte acces = serviceUtilisateur.reinitialiserCompteStructure(structure);
        // Le responsable est effacé par chaque modification de la structure (le formulaire ne
        // le renvoie pas) : on le rétablit sur le compte qui vient d'être servi.
        if (!acces.compteId().equals(structure.getResponsableId())) {
            structure.setResponsableId(acces.compteId());
            depotStructure.save(structure);
        }
        return acces;
    }

    // ────────────────────────────── import Excel ──────────────────────────────

    /**
     * Création en masse depuis un classeur (§6 : « création individuelle ou en masse via Excel »).
     *
     * <p>Le fichier est traité en deux passes : d'abord toutes les structures, ensuite les
     * rattachements. C'est ce qui permet à un poste de citer, dans la même feuille, un
     * centre déclaré plus bas — cas systématique quand le fichier est trié par ordre
     * alphabétique.</p>
     */
    @Transactional
    public ResultatImport importer(MultipartFile fichier) {
        ResultatImport resultat = new ResultatImport();
        UtilisateurAuthentifie courant = ContexteUtilisateur.courant();
        List<LecteurExcel.LigneImport> lignes;
        try {
            lignes = LecteurExcel.lire(fichier.getInputStream());
        } catch (IOException e) {
            throw ExceptionMetier.invalide("Lecture du fichier impossible : " + e.getMessage());
        }
        resultat.setLignesLues(lignes.size());

        Map<String, String> districtsParCode = indexerDistricts();
        Map<String, String> regionsParCode = indexerRegions();
        // Code du centre de rattachement, à résoudre en seconde passe.
        Map<String, String> rattachementsARegler = new HashMap<>();
        // Centres du fichier : leurs postes déjà en base suivent un éventuel changement de district.
        List<StructureSanitaire> centresImportes = new java.util.ArrayList<>();

        for (LecteurExcel.LigneImport ligne : lignes) {
            try {
                String code = ligne.texte("code", "codestructure");
                String nom = ligne.texte("nom", "nomstructure", "structure", "libelle");
                if (code == null || nom == null) {
                    resultat.erreur(ligne.numeroLigne(), "colonnes « Code » et « Nom » obligatoires");
                    continue;
                }

                TypeStructure type = TypeStructure.depuisCode(ligne.texte("type", "typestructure", "niveau"));
                if (type == null) {
                    resultat.erreur(ligne.numeroLigne(), "type de structure illisible (attendu : PS, CS ou EPS)");
                    continue;
                }

                String codeRattachement = ligne.texte("rattachement", "centrederattachement",
                        "centrerattachement", "coderattachement");
                String districtId = resoudre(districtsParCode, ligne.texte("district", "codedistrict"));
                // Un poste qui cite son centre en prend le district (seconde passe) : la colonne
                // District ne lui est pas demandée.
                boolean districtDuCentre = type == TypeStructure.PS && codeRattachement != null;
                if (districtId == null && type != TypeStructure.EPS && !districtDuCentre) {
                    resultat.erreur(ligne.numeroLigne(), "district sanitaire obligatoire pour un poste ou un centre "
                            + "de santé (colonne « District », code ou nom)");
                    continue;
                }
                String regionId = resoudre(regionsParCode, ligne.texte("region", "coderegion"));
                // Une structure sans région explicite hérite de celle de son district :
                // dans les fichiers du terrain la colonne Région est souvent omise.
                if (regionId == null && districtId != null) {
                    regionId = depotDistrict.findById(districtId).map(District::getRegionId).orElse(null);
                }

                Optional<StructureSanitaire> existante = depotStructure.findByCodeIgnoreCase(code);
                if (courant.getRole() == Role.SERVICE_REGIONAL) {
                    // Un service régional n'importe — ni ne met à jour — que dans sa région.
                    if (regionId == null) {
                        regionId = courant.getRegionId();
                    }
                    String regionSr = courant.getRegionId();
                    boolean existanteAilleurs = existante.isPresent()
                            && !java.util.Objects.equals(existante.get().getRegionId(), regionSr);
                    if (regionSr == null || !regionSr.equals(regionId) || existanteAilleurs) {
                        resultat.erreur(ligne.numeroLigne(), "structure hors de votre région");
                        continue;
                    }
                }
                StructureSanitaire structure = existante.orElseGet(StructureSanitaire::new);
                structure.setCode(code);
                structure.setNom(nom);
                structure.setTypeStructure(type.name());
                structure.setDistrictId(districtId);
                structure.setRegionId(regionId);
                structure.setAdresse(ligne.texte("adresse"));
                structure.setTelephone(ligne.texte("telephone", "tel"));
                structure.setEmail(ligne.texte("email", "mail"));
                structure.setActif(true);
                depotStructure.save(structure);
                if (structure.polarise()) {
                    centresImportes.add(structure);
                }

                if (districtDuCentre) {
                    rattachementsARegler.put(code, codeRattachement);
                }

                if (existante.isPresent()) {
                    resultat.setMisAJour(resultat.getMisAJour() + 1);
                } else {
                    resultat.setCrees(resultat.getCrees() + 1);
                }
            } catch (ExceptionMetier e) {
                resultat.erreur(ligne.numeroLigne(), e.getMessage());
            }
        }

        // Seconde passe : les rattachements, une fois toutes les structures connues.
        rattachementsARegler.forEach((codePoste, codeCentre) -> {
            Optional<StructureSanitaire> poste = depotStructure.findByCodeIgnoreCase(codePoste);
            Optional<StructureSanitaire> centre = depotStructure.findByCodeIgnoreCase(codeCentre);
            if (poste.isEmpty()) {
                return;
            }
            if (centre.isEmpty() || !centre.get().polarise()) {
                resultat.getErreurs().add("Poste « " + codePoste + " » : centre de rattachement « "
                        + codeCentre + " » introuvable ou non polarisant — rattachement non appliqué.");
                return;
            }
            poste.get().setStructureRattachementId(centre.get().getId());
            heriterDuCentre(poste.get(), centre.get());
            if (poste.get().getRegionId() == null) {
                poste.get().setRegionId(centre.get().getRegionId());
            }
            if (poste.get().getDistrictId() == null) {
                resultat.getErreurs().add("Poste « " + codePoste + " » : son centre « " + codeCentre
                        + " » n'a pas de district — renseignez la colonne « District » du poste.");
            }
            depotStructure.save(poste.get());
        });
        centresImportes.forEach(this::propagerAuxPostes);

        return resultat;
    }

    // ────────────────────────────── conversion ──────────────────────────────

    /** Vue publique enrichie des libellés, telle que la consomment les autres services. */
    public StructureDto versDto(StructureSanitaire structure) {
        District district = structure.getDistrictId() == null ? null
                : depotDistrict.findById(structure.getDistrictId()).orElse(null);
        Region region = structure.getRegionId() == null ? null
                : depotRegion.findById(structure.getRegionId()).orElse(null);
        StructureSanitaire rattachement = structure.getStructureRattachementId() == null ? null
                : depotStructure.findById(structure.getStructureRattachementId()).orElse(null);

        return StructureDto.builder()
                .id(structure.getId())
                .code(structure.getCode())
                .nom(structure.getNom())
                .adresse(structure.getAdresse())
                .typeStructure(structure.typeEffectif())
                .structureRattachementId(structure.getStructureRattachementId())
                .structureRattachementNom(rattachement == null ? null : rattachement.getNom())
                .districtId(structure.getDistrictId())
                .districtNom(district == null ? null : district.getNom())
                .regionId(structure.getRegionId())
                .regionNom(region == null ? null : region.getNom())
                .actif(structure.isActif())
                .siegeDistrict(structure.estSiegeDistrict())
                .build();
    }

    // ────────────────────────────── règles ──────────────────────────────

    /**
     * Le service régional n'agit que sur les structures de sa région. La lecture le filtrait
     * déjà ({@link #lister}) ; l'activation et la réinitialisation des accès ne le vérifiaient pas.
     */
    private void verifierPerimetre(StructureSanitaire structure) {
        UtilisateurAuthentifie courant = ContexteUtilisateur.courant();
        if (courant.getRole() == Role.SERVICE_REGIONAL
                && (courant.getRegionId() == null || !courant.getRegionId().equals(structure.getRegionId()))) {
            throw ExceptionMetier.interdit("La structure « " + structure.getNom()
                    + " » n'appartient pas à votre région.");
        }
    }

    /**
     * Un poste appartient au district de son centre de rattachement : le district ne se
     * saisit pas, il se déduit (arbitrage du 03/10/2026). Le saisir à part permettait un
     * poste d'un district rattaché au centre d'un autre — invisible alors dans la
     * consolidation du district qui valide ses factures.
     *
     * <p>Seule exception : un centre sans district (un EPS peut en être dispensé). Le poste
     * garde alors le district saisi, toujours obligatoire pour lui.</p>
     */
    private void heriterDuCentre(StructureSanitaire poste, StructureSanitaire centre) {
        if (centre.getDistrictId() == null || centre.getDistrictId().isBlank()) {
            return;
        }
        poste.setDistrictId(centre.getDistrictId());
        // Région remise à zéro : verifierCoherence la reprend du district, sans pouvoir
        // tomber en désaccord avec une région restée dans le formulaire.
        poste.setRegionId(null);
    }

    /** Un centre qui change de district emmène ses postes : ils en dépendent (voir {@link #heriterDuCentre}). */
    private void propagerAuxPostes(StructureSanitaire centre) {
        if (!centre.polarise() || centre.getDistrictId() == null) {
            return;
        }
        for (StructureSanitaire poste : depotStructure.findByStructureRattachementId(centre.getId())) {
            if (!centre.getDistrictId().equals(poste.getDistrictId())
                    || !java.util.Objects.equals(centre.getRegionId(), poste.getRegionId())) {
                poste.setDistrictId(centre.getDistrictId());
                poste.setRegionId(centre.getRegionId());
                depotStructure.save(poste);
            }
        }
    }

    private void normaliser(StructureSanitaire structure) {
        if (structure.getCode() != null) {
            structure.setCode(structure.getCode().trim().toUpperCase());
        }
        if (structure.getNom() != null) {
            structure.setNom(structure.getNom().trim());
        }
        if (structure.getStructureRattachementId() != null && structure.getStructureRattachementId().isBlank()) {
            structure.setStructureRattachementId(null);
        }
        if (structure.getDistrictId() != null && structure.getDistrictId().isBlank()) {
            structure.setDistrictId(null);
        }
    }

    private void verifierCoherence(StructureSanitaire structure) {
        if (structure.typeEffectif() == null) {
            throw ExceptionMetier.invalide("Le type de structure (PS, CS ou EPS) est obligatoire");
        }

        String rattachementId = structure.getStructureRattachementId();
        if (rattachementId != null) {
            if (structure.typeEffectif() != TypeStructure.PS) {
                throw ExceptionMetier.invalide(
                        "Seul un poste de santé peut être rattaché à un centre de santé ou un EPS.");
            }
            if (rattachementId.equals(structure.getId())) {
                throw ExceptionMetier.invalide("Une structure ne peut pas être rattachée à elle-même.");
            }
            StructureSanitaire centre = depotStructure.findById(rattachementId)
                    .orElseThrow(() -> ExceptionMetier.invalide("Centre de rattachement introuvable."));
            if (!centre.polarise()) {
                throw ExceptionMetier.invalide("« " + centre.getNom() + " » est un poste de santé : "
                        + "il ne peut pas polariser d'autres postes. Choisissez un centre de santé ou un EPS.");
            }
            heriterDuCentre(structure, centre);
        } else if (structure.typeEffectif() == TypeStructure.PS) {
            // Non bloquant : un poste peut être créé avant que son centre existe, mais
            // ses factures resteront alors sans destinataire — le front l'alerte.
            structure.setStructureRattachementId(null);
        }

        // Cahier « Espace PS », §2 : postes et centres de santé sont obligatoirement rattachés
        // à un district sanitaire — c'est l'échelon de consolidation de leurs factures. Les
        // EPS (hôpitaux) en restent dispensés.
        if (structure.getDistrictId() == null
                && (structure.typeEffectif() == TypeStructure.PS || structure.typeEffectif() == TypeStructure.CS)) {
            throw ExceptionMetier.invalide("Un poste ou un centre de santé doit être rattaché à un district sanitaire : "
                    + "choisissez le district de « " + structure.getNom() + " ».");
        }
        if (structure.getDistrictId() != null) {
            District district = depotDistrict.findById(structure.getDistrictId())
                    .orElseThrow(() -> ExceptionMetier.invalide("District introuvable."));
            if (structure.getRegionId() == null) {
                structure.setRegionId(district.getRegionId());
            } else if (district.getRegionId() != null && !district.getRegionId().equals(structure.getRegionId())) {
                throw ExceptionMetier.invalide("Le district « " + district.getNom()
                        + " » n'appartient pas à la région sélectionnée.");
            }
        }
    }

    private Map<String, String> indexerDistricts() {
        Map<String, String> index = new HashMap<>();
        for (District district : depotDistrict.findAll()) {
            if (district.getCode() != null) {
                index.put(district.getCode().toLowerCase(), district.getId());
            }
            if (district.getNom() != null) {
                index.put(district.getNom().toLowerCase(), district.getId());
            }
        }
        return index;
    }

    private Map<String, String> indexerRegions() {
        Map<String, String> index = new HashMap<>();
        for (Region region : depotRegion.findAll()) {
            if (region.getCode() != null) {
                index.put(region.getCode().toLowerCase(), region.getId());
            }
            if (region.getNom() != null) {
                index.put(region.getNom().toLowerCase(), region.getId());
            }
        }
        return index;
    }

    private static String resoudre(Map<String, String> index, String valeur) {
        return valeur == null ? null : index.get(valeur.trim().toLowerCase());
    }
}
