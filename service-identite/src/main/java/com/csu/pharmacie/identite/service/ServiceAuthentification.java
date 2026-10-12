package com.csu.pharmacie.identite.service;

import com.csu.pharmacie.entity.District;
import com.csu.pharmacie.entity.Pointage;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.securite.JetonService;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.identite.depot.DepotDistrict;
import com.csu.pharmacie.identite.depot.DepotPointage;
import com.csu.pharmacie.identite.depot.DepotStructure;
import com.csu.pharmacie.identite.depot.DepotUtilisateur;
import com.csu.pharmacie.identite.dto.DtosAuth;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.User;


/**
 * Connexion et gestion du mot de passe (§1 du document).
 *
 * <p>La connexion fait plus qu'authentifier : elle compose le contexte complet de
 * l'utilisateur (structure, type de structure, centre de rattachement, district, région)
 * et le grave dans le jeton. Tous les autres microservices s'appuieront sur ces claims —
 * il est donc essentiel de les résoudre <em>ici</em>, une fois, plutôt que de faire
 * interroger service-identite à chaque requête.</p>
 */
@Service
@RequiredArgsConstructor
public class ServiceAuthentification {

    private final DepotUtilisateur depotUtilisateur;
    private final DepotStructure depotStructure;
    private final DepotDistrict depotDistrict;
    private final DepotPointage depotPointage;
    private final PasswordEncoder encodeur;
    private final JetonService jetonService;

    @Transactional
    public DtosAuth.ReponseConnexion connecter(DtosAuth.RequeteConnexion requete) {
        String identifiant = requete.identifiantSaisi();
        if (identifiant.isBlank()) {
            throw ExceptionMetier.invalide("L'identifiant est obligatoire");
        }

        User utilisateur = depotUtilisateur.findByEmailIgnoreCase(identifiant)
                .or(() -> depotUtilisateur.findByUsernameIgnoreCase(identifiant))
                // Message volontairement identique en cas d'email inconnu ou de mot de passe
                // faux : distinguer les deux permettrait d'énumérer les comptes existants.
                .orElseThrow(() -> ExceptionMetier.nonAuthentifie("Identifiants incorrects"));

        if (!encodeur.matches(requete.getPassword(), utilisateur.getPassword())) {
            throw ExceptionMetier.nonAuthentifie("Identifiants incorrects");
        }
        if (!utilisateur.isActif()) {
            throw ExceptionMetier.interdit("Ce compte est désactivé. Contactez votre administrateur.");
        }
        verifierStructureActive(utilisateur);

        Pointage pointage = enregistrerConnexionPointage(utilisateur);
        return construireReponse(utilisateur, pointage);
    }

    /**
     * Une structure désactivée (§6 : on ne supprime pas une structure, on la désactive) ferme
     * l'accès de ses comptes — y compris d'un compte resté ou remis actif à l'unité. Les agents
     * BCSU rattachés à la structure ne sont pas concernés : ce sont leurs comptes, pas le sien.
     */
    private void verifierStructureActive(User utilisateur) {
        if (utilisateur.getRole() == null || !utilisateur.getRole().estStructureSanitaire()
                || utilisateur.getStructureSanitaireId() == null) {
            return;
        }
        depotStructure.findById(utilisateur.getStructureSanitaireId())
                .filter(structure -> !structure.isActif())
                .ifPresent(structure -> {
                    throw ExceptionMetier.interdit("La structure « " + structure.getNom()
                            + " » est désactivée. Contactez votre service régional.");
                });
    }

    /** Contexte complet de l'utilisateur connecté, rafraîchi depuis la base. */
    public DtosAuth.ReponseConnexion moi() {
        UtilisateurAuthentifie courant = ContexteUtilisateur.courant();
        User utilisateur = depotUtilisateur.findById(courant.getId())
                .or(() -> depotUtilisateur.findByEmailIgnoreCase(courant.getEmail()))
                .orElseThrow(() -> ExceptionMetier.introuvable("Compte introuvable"));
        Pointage pointage = pointageDuJour(utilisateur);
        return construireReponse(utilisateur, pointage);
    }

    /**
     * Changement du mot de passe (§1). Lève aussi le drapeau {@code mustChangePassword}
     * posé lors des créations en masse : c'est le seul moyen de le retirer.
     */
    @Transactional
    public void changerMotDePasse(DtosAuth.RequeteChangementMotDePasse requete) {
        UtilisateurAuthentifie courant = ContexteUtilisateur.courant();
        User utilisateur = depotUtilisateur.findById(courant.getId())
                .or(() -> depotUtilisateur.findByEmailIgnoreCase(courant.getEmail()))
                .orElseThrow(() -> ExceptionMetier.introuvable("Compte introuvable"));

        if (!encodeur.matches(requete.getAncienMotDePasse(), utilisateur.getPassword())) {
            throw ExceptionMetier.invalide("Le mot de passe actuel est incorrect");
        }
        if (encodeur.matches(requete.getNouveauMotDePasse(), utilisateur.getPassword())) {
            throw ExceptionMetier.invalide("Le nouveau mot de passe doit être différent de l'ancien");
        }

        utilisateur.setPassword(encodeur.encode(requete.getNouveauMotDePasse()));
        utilisateur.setMustChangePassword(false);
        depotUtilisateur.save(utilisateur);
    }

    // ────────────────────────────── composition du contexte ──────────────────────────────

    private DtosAuth.ReponseConnexion construireReponse(User utilisateur, Pointage pointage) {
        StructureSanitaire structure = utilisateur.getStructureSanitaireId() == null ? null
                : depotStructure.findById(utilisateur.getStructureSanitaireId()).orElse(null);
        StructureSanitaire rattachement = structure == null || structure.getStructureRattachementId() == null
                ? null : depotStructure.findById(structure.getStructureRattachementId()).orElse(null);

        // Le district du compte prime ; à défaut on prend celui de sa structure, pour que
        // les consolidations district fonctionnent sans ressaisie sur les comptes existants.
        String districtId = utilisateur.getDistrictId() != null ? utilisateur.getDistrictId()
                : (structure == null ? null : structure.getDistrictId());
        District district = districtId == null ? null : depotDistrict.findById(districtId).orElse(null);

        String regionId = utilisateur.getRegionId() != null ? utilisateur.getRegionId()
                : (structure == null ? null : structure.getRegionId());

        UtilisateurAuthentifie contexte = UtilisateurAuthentifie.builder()
                .id(utilisateur.getId())
                .email(utilisateur.getEmail())
                .nom(utilisateur.getNom())
                .prenom(utilisateur.getPrenom())
                .role(utilisateur.getRole())
                .structureId(utilisateur.getStructureSanitaireId())
                .structureNom(structure == null ? null : structure.getNom())
                .typeStructure(structure == null ? null : structure.typeEffectif())
                .structureRattachementId(structure == null ? null : structure.getStructureRattachementId())
                .districtId(districtId)
                .regionId(regionId)
                .pharmacieId(utilisateur.getPharmacieId())
                // Embarqué dans le jeton : chaque service refuse alors localement les
                // requêtes d'un compte non régularisé, sans aller interroger la base.
                .motDePasseAChanger(Boolean.TRUE.equals(utilisateur.getMustChangePassword()))
                // Centre siège du district : double casquette centre + district (sur SON district).
                .siegeDistrict(structure != null && structure.estSiegeDistrict()
                        && utilisateur.getRole() != null && utilisateur.getRole().estStructureSanitaire())
                .build();

        Role effectif = contexte.roleEffectif();

        return DtosAuth.ReponseConnexion.builder()
                .token(jetonService.emettre(contexte))
                .expiresIn(jetonService.dureeValiditeMs())
                .userId(utilisateur.getId())
                .email(utilisateur.getEmail())
                .username(utilisateur.getUsername())
                .mustChangePassword(Boolean.TRUE.equals(utilisateur.getMustChangePassword()))
                .nom(utilisateur.getNom())
                .prenom(utilisateur.getPrenom())
                .role(utilisateur.getRole())
                .roleEffectif(effectif)
                .pharmacieId(utilisateur.getPharmacieId())
                .regionId(regionId)
                .districtId(districtId)
                .districtNom(district == null ? null : district.getNom())
                .structureSanitaireId(utilisateur.getStructureSanitaireId())
                .structureNom(structure == null ? null : structure.getNom())
                .structureType(structure == null || structure.getTypeStructure() == null
                        ? null : structure.getTypeStructure())
                .structureRattachementId(structure == null ? null : structure.getStructureRattachementId())
                .structureRattachementNom(rattachement == null ? null : rattachement.getNom())
                .siegeDistrict(contexte.isSiegeDistrict())
                // Pointage BCSU du jour : sans ce report, la LoginResponse d'identite ne portait
                // jamais pointageHeure et l'écran BCSU redemandait de pointer à chaque reconnexion.
                .pointageHeure(pointage != null && pointage.getHeureArrivee() != null
                        ? pointage.getHeureArrivee().format(DateTimeFormatter.ofPattern("HH:mm"))
                        : null)
                .premierPointage(pointage != null && pointage.getHeureArrivee() == null)
                .pointageDepartHeure(pointage != null && pointage.getHeureDepart() != null
                        ? pointage.getHeureDepart().format(DateTimeFormatter.ofPattern("HH:mm"))
                        : null)
                .build();
    }

    /**
     * Pointage BCSU à la connexion, à l'identique du monolithe : la première connexion du
     * jour crée la ligne (sans heure d'arrivée/départ), les suivantes mettent seulement à jour
     * la dernière connexion. L'arrivée et le départ effectifs restent des actions explicites
     * de l'agent. L'entité {@code Pointage} vit dans « commun » : identite y accède sans
     * dépendre du cœur. Appelé depuis {@code connecter()} (transactionnel).
     */
    private Pointage enregistrerConnexionPointage(User utilisateur) {
        if (utilisateur.getRole() != Role.BCSU) {
            return null;
        }
        LocalDateTime maintenant = LocalDateTime.now();
        Pointage pointage = depotPointage.findByUserIdAndDate(utilisateur.getId(), LocalDate.now())
                .map(p -> {
                    p.setDerniereConnexion(maintenant);
                    return p;
                })
                .orElseGet(() -> Pointage.builder()
                        .userId(utilisateur.getId())
                        .userNom(utilisateur.getNom())
                        .userPrenom(utilisateur.getPrenom())
                        .role(utilisateur.getRole())
                        .date(LocalDate.now())
                        .derniereConnexion(maintenant)
                        .build());
        return depotPointage.save(pointage);
    }

    /** Pointage du jour en lecture seule — pour rafraîchir le contexte via {@code moi()}. */
    private Pointage pointageDuJour(User utilisateur) {
        if (utilisateur.getRole() != Role.BCSU) {
            return null;
        }
        return depotPointage.findByUserIdAndDate(utilisateur.getId(), LocalDate.now()).orElse(null);
    }
}
