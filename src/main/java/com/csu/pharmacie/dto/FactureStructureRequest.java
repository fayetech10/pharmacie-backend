package com.csu.pharmacie.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class FactureStructureRequest {
    /** Numéro de la lettre de garantie source (facultatif pour les Postes de Santé). */
    private String lettreGarantieNumero;

    /** Le régime (obligatoire pour les Postes de Santé qui n'ont pas de lettre de garantie). */
    private com.csu.pharmacie.entity.Regime regime;

    /** Champs patient (obligatoires pour les Postes de Santé qui n'ont pas de LG). */
    private String patientNom;
    private String patientPrenom;
    private String patientTelephone;

    /** Feuille de soins déposée par le patient à rattacher à cette facture (fin du circuit). */
    private String feuilleSoinsId;

    /** Service hospitalier (ex : Cardiologie, Maternité, Urgences). */
    private String service;
    /** Diagnostique médical. */
    private String diagnostique;

    // ---- Identité du bénéficiaire (classeur CS_EPS ; préremplie depuis la lettre si absente) ----
    private java.time.LocalDate patientDateNaissance;
    private String patientSexe;
    private String patientAdresse;
    private String patientMatricule;
    /** SESAME uniquement : N° CNI en plus du matricule. */
    private String patientNumeroCni;

    // ---- Champs spécifiques par catégorie ----
    private String numeroRegistre;             // Enfants 0-5 ans
    private String ircIra;                     // Dialyses : IRC / IRA
    private String indicationCbt;              // Césarienne
    private String numeroRegistreBloc;         // Césarienne
    private String dateHeureIntervention;      // Césarienne
    private Integer dureeHospitalisationJours; // Césarienne

    @NotEmpty(message = "La facture doit contenir au moins une prestation")
    @Valid
    private List<LigneFactureStructureDto> lignes;

    @Size(max = 2097152, message = "Image trop volumineuse")
    private String ticketCaisse;

    @Valid
    private List<DocumentComplementaireDto> documentsComplementaires;
}
