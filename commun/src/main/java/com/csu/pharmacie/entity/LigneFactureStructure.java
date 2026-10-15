package com.csu.pharmacie.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Ligne de prestation d'une facture de structure sanitaire (modèle SITFAC).
 * Chaque prestation est indépendante ; part bénéficiaire / part SEN-CSU calculées
 * en service à partir du régime du dossier.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LigneFactureStructure {

    /** Prestation apportée par le fichier de fin de mois d'un centre ou d'un EPS (cf. {@link #origine}). */
    public static final String ORIGINE_IMPORT = "IMPORT";

    /**
     * D'où vient la prestation : {@link #ORIGINE_IMPORT} pour l'import de fin de mois, nul pour une
     * saisie à l'écran. Un nouvel import remplace les prestations importées d'un patient, jamais
     * celles saisies à la main — c'est ce qui permet de réimporter sans doublon.
     */
    private String origine;

    /**
     * Prestation du référentiel (§5). Quand elle est renseignée, le prix, la désignation
     * et le code sont réécrits depuis le référentiel à chaque enregistrement : le montant
     * envoyé par le client n'est jamais retenu.
     */
    private String prestationId;
    /**
     * Prestation du catalogue propre au point de soins (écran « Paramétrage »). À la
     * différence de {@link #prestationId}, son prix n'est pas imposé : c'est la structure
     * elle-même qui le fixe, et il reste modifiable sur la facture.
     */
    private String tarifId;
    /** Dossier patient de la facture auquel appartient la ligne (cf. {@link DossierPatient}). */
    private String dossierId;
    private String designation;    // Acte / prestation
    private String codeActe;       // Code SITFAC (optionnel)
    private LocalDate datePriseEnCharge;
    private int quantite;
    private double prixUnitaire;
    private double montant;         // quantite * prixUnitaire (recalculé en service)
    private String motifCesarienne; // Renseigné seulement si régime == CESARIENNE

    private double montantBeneficiaire;
    private double montantSencsu;

    // Rapprochement Patient / Lettre de garantie par ligne pour la facturation incrémentielle
    private String lettreGarantieId;
    private String lettreGarantieNumero;
    private String feuilleSoinsId;
    private String feuilleSoinsNumero;
    private String patientId;
    private String patientNom;
    private String patientPrenom;
    private String patientTelephone;
    private LocalDate patientDateNaissance;
    private String patientSexe;
    private String patientAdresse;
    private String patientMatricule;
    private String patientNumeroCni;
    /** Issue de la vérification de la carte CSU du patient (cf. VerificationCarte), saisie au poste. */
    private String verificationCarte;

    // Champs spécifiques par catégorie (classeur CS_EPS) par ligne
    private String service;                    // Service hospitalier
    private String numeroRegistre;             // Enfants 0-5 ans
    private String ircIra;                     // Dialyses : IRC / IRA
    private String indicationCbt;              // Césarienne
    private String numeroRegistreBloc;         // Césarienne
    private String dateHeureIntervention;      // Césarienne
    private Integer dureeHospitalisationJours; // Césarienne

    // ─── Intégration pharmacie (§14) ───
    /** Prestation servie par la pharmacie dépositaire : sa facturation sort du stock. */
    private boolean depositaire;
    /** Article de stock à décrémenter — recopié du référentiel, jamais saisi. */
    private String medicamentId;
    /** Renseigné après la sortie de stock, pour ne jamais la déclencher deux fois. */
    private String mouvementStockId;

    /**
     * Clé d'unicité d'un bénéficiaire dans une facture, pour compter les patients
     * distincts et regrouper les lignes dans la liste nominative.
     */
    public String cleBeneficiaire() {
        if (patientId != null && !patientId.isBlank()) {
            return patientId;
        }
        if (lettreGarantieNumero != null && !lettreGarantieNumero.isBlank()) {
            return lettreGarantieNumero;
        }
        if (patientMatricule != null && !patientMatricule.isBlank()) {
            return patientMatricule;
        }
        return (patientPrenom == null ? "" : patientPrenom.trim().toLowerCase())
                + "|" + (patientNom == null ? "" : patientNom.trim().toLowerCase())
                + "|" + (patientDateNaissance == null ? "" : patientDateNaissance);
    }

    public String nomComplet() {
        return ((patientPrenom == null ? "" : patientPrenom) + " "
                + (patientNom == null ? "" : patientNom)).trim();
    }
}
