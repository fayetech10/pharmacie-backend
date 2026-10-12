package com.csu.pharmacie.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class LigneFactureStructureDto {
    @NotBlank(message = "La désignation de la prestation est obligatoire")
    private String designation;

    private String codeActe;

    @NotNull(message = "La date de prise en charge est obligatoire")
    private LocalDate datePriseEnCharge;

    @Min(value = 1, message = "La quantité doit être supérieure à 0")
    private int quantite;

    @Min(value = 0, message = "Le prix unitaire doit être positif")
    private double prixUnitaire;

    private String motifCesarienne;
}
