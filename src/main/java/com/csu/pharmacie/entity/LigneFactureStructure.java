package com.csu.pharmacie.entity;

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
public class LigneFactureStructure {
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

    // Champs spécifiques par catégorie (classeur CS_EPS) par ligne
    private String service;                    // Service hospitalier
    private String diagnostique;               // Diagnostique médical
    private String numeroRegistre;             // Enfants 0-5 ans
    private String ircIra;                     // Dialyses : IRC / IRA
    private String indicationCbt;              // Césarienne
    private String numeroRegistreBloc;         // Césarienne
    private String dateHeureIntervention;      // Césarienne
    private Integer dureeHospitalisationJours; // Césarienne
}
