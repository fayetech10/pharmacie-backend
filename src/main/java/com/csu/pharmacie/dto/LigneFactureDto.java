package com.csu.pharmacie.dto;

import lombok.Data;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
public class LigneFactureDto {
    @NotBlank(message = "Le nom et prénom du patient sont obligatoires")
    private String patientNomPrenom;

    @NotBlank(message = "Le matricule du patient est obligatoire")
    private String patientMatricule;

    @NotBlank(message = "Le médicament est obligatoire")
    private String medicament;

    @NotBlank(message = "Le code produit est obligatoire")
    private String codeProduit;

    @Min(value = 1, message = "La quantité doit être supérieure à 0")
    private int quantite;

    @Min(value = 0, message = "Le prix unitaire doit être positif")
    private double prixUnitaire;

    // Pièces justificatives du dossier patient (images encodées en base64 / data URL)
    private String ticketCaisse;
    private String bonCommande;
    private String ordonnance;
}
