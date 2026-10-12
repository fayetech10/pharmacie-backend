package com.csu.pharmacie.poste.service;

import com.csu.pharmacie.entity.Beneficiaire;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.commun.facturation.ParametresFacturation;
import com.csu.pharmacie.commun.facturation.ReglesFacturation;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.poste.depot.DepotBeneficiaire;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;

/**
 * Enregistrement des bénéficiaires (§3).
 *
 * <p>Le matricule du Plan Sésame est attribué ici, à la création, plutôt qu'au moment de
 * facturer : l'agent doit pouvoir le lire et le communiquer au patient immédiatement.
 * La génération reste déterministe, donc réenregistrer le même bénéficiaire ne crée pas
 * un second matricule.</p>
 */
@Service
@RequiredArgsConstructor
public class ServiceBeneficiaire {

    private final DepotBeneficiaire depot;
    private final ParametresFacturation parametres;

    public Page<Beneficiaire> rechercher(String recherche, int page, int taille) {
        String structureId = ContexteUtilisateur.structureCourante();
        String requete = recherche == null ? "" : recherche.trim().toLowerCase(Locale.ROOT);
        return depot.rechercher(structureId, requete,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, taille), 100),
                        Sort.by("nom", "prenom")));
    }

    public Beneficiaire parId(String id) {
        Beneficiaire beneficiaire = depot.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Bénéficiaire introuvable : " + id));
        if (!ContexteUtilisateur.structureCourante().equals(beneficiaire.getStructureId())) {
            throw ExceptionMetier.interdit("Ce bénéficiaire appartient à une autre structure");
        }
        return beneficiaire;
    }

    @Transactional
    public Beneficiaire enregistrer(String id, Beneficiaire saisie) {
        String structureId = ContexteUtilisateur.structureCourante();
        if (saisie.getNom() == null || saisie.getNom().isBlank()) {
            throw ExceptionMetier.invalide("Le nom du bénéficiaire est obligatoire");
        }
        if (saisie.getDateNaissance() != null && saisie.getDateNaissance().isAfter(LocalDate.now())) {
            throw ExceptionMetier.invalide("La date de naissance ne peut pas être dans le futur");
        }

        Beneficiaire beneficiaire = id == null || id.isBlank() ? new Beneficiaire() : parId(id);
        beneficiaire.setStructureId(structureId);
        beneficiaire.setNom(saisie.getNom().trim());
        beneficiaire.setPrenom(saisie.getPrenom() == null ? null : saisie.getPrenom().trim());
        beneficiaire.setSexe(normaliserSexe(saisie.getSexe()));
        beneficiaire.setDateNaissance(saisie.getDateNaissance());
        beneficiaire.setTelephone(saisie.getTelephone());
        beneficiaire.setAdresse(saisie.getAdresse());
        beneficiaire.setNumeroCni(saisie.getNumeroCni());
        beneficiaire.setRegime(saisie.getRegime());
        beneficiaire.setMatricule(saisie.getMatricule());

        if (beneficiaire.getCreatedBy() == null) {
            beneficiaire.setCreatedBy(ContexteUtilisateur.courant().getId());
        }

        controlerUnicite(beneficiaire);
        attribuerMatricule(beneficiaire, structureId);
        return depot.save(beneficiaire);
    }

    @Transactional
    public Beneficiaire basculerActivation(String id, boolean actif) {
        Beneficiaire beneficiaire = parId(id);
        beneficiaire.setActif(actif);
        return depot.save(beneficiaire);
    }

    /**
     * Prépare une ligne de facturation à partir du bénéficiaire : évite à l'agent de
     * ressaisir l'état civil pour chaque prestation, et garantit que les contrôles d'âge
     * du §4 disposent bien de la date de naissance.
     */
    public LigneFactureStructure versLigne(String beneficiaireId) {
        Beneficiaire beneficiaire = parId(beneficiaireId);
        return LigneFactureStructure.builder()
                .patientId(beneficiaire.getId())
                .patientNom(beneficiaire.getNom())
                .patientPrenom(beneficiaire.getPrenom())
                .patientSexe(beneficiaire.getSexe())
                .patientDateNaissance(beneficiaire.getDateNaissance())
                .patientTelephone(beneficiaire.getTelephone())
                .patientAdresse(beneficiaire.getAdresse())
                .patientNumeroCni(beneficiaire.getNumeroCni())
                .patientMatricule(beneficiaire.getMatricule())
                .datePriseEnCharge(LocalDate.now())
                .quantite(1)
                .build();
    }

    // ────────────────────────────── règles ──────────────────────────────

    /**
     * Le matricule Sésame est dérivé de l'identité du bénéficiaire et de sa structure :
     * stable d'un enregistrement à l'autre, donc rejouable sans créer de doublon (§4).
     */
    private void attribuerMatricule(Beneficiaire beneficiaire, String structureId) {
        if (beneficiaire.getRegime() != Regime.SESAME) {
            return;
        }
        if (beneficiaire.getMatricule() != null && !beneficiaire.getMatricule().isBlank()) {
            return;
        }
        LigneFactureStructure reference = LigneFactureStructure.builder()
                .patientId(beneficiaire.getId())
                .patientNom(beneficiaire.getNom())
                .patientPrenom(beneficiaire.getPrenom())
                .patientDateNaissance(beneficiaire.getDateNaissance())
                .build();
        beneficiaire.setMatricule(ReglesFacturation.genererMatriculeSesame(
                parametres.getPrefixeMatriculeSesame(), structureId, reference));
    }

    /** Un même numéro de CNI ou de matricule ne peut désigner deux personnes dans la structure. */
    private void controlerUnicite(Beneficiaire beneficiaire) {
        if (beneficiaire.getNumeroCni() != null && !beneficiaire.getNumeroCni().isBlank()) {
            Optional<Beneficiaire> existant = depot.findByStructureIdAndNumeroCniIgnoreCase(
                    beneficiaire.getStructureId(), beneficiaire.getNumeroCni());
            if (existant.isPresent() && !existant.get().getId().equals(beneficiaire.getId())) {
                throw ExceptionMetier.conflit("Le numéro de CNI « " + beneficiaire.getNumeroCni()
                        + " » est déjà enregistré pour « " + existant.get().nomComplet() + " ».");
            }
        }
        if (beneficiaire.getMatricule() != null && !beneficiaire.getMatricule().isBlank()) {
            Optional<Beneficiaire> existant = depot.findByStructureIdAndMatriculeIgnoreCase(
                    beneficiaire.getStructureId(), beneficiaire.getMatricule());
            if (existant.isPresent() && !existant.get().getId().equals(beneficiaire.getId())) {
                throw ExceptionMetier.conflit("Le matricule « " + beneficiaire.getMatricule()
                        + " » est déjà attribué à « " + existant.get().nomComplet() + " ».");
            }
        }
    }

    private static String normaliserSexe(String sexe) {
        if (sexe == null || sexe.isBlank()) {
            return null;
        }
        char premiere = Character.toUpperCase(sexe.trim().charAt(0));
        return premiere == 'F' ? "F" : "M";
    }
}
