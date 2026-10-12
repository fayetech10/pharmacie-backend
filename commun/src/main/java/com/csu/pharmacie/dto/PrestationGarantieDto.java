package com.csu.pharmacie.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PrestationGarantieDto {
    @NotBlank(message = "La désignation (médicament/prestation) est obligatoire")
    private String designation;

    @NotNull(message = "La date de prise en charge est obligatoire")
    private LocalDate datePriseEnCharge;

    @Min(value = 0, message = "Le montant doit être positif")
    private double montant;

    // Pertinent uniquement si le régime du dossier est CESARIENNE (non validé strictement ici).
    private String motifCesarienne;
}
