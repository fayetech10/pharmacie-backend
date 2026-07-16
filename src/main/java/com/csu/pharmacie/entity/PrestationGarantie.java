package com.csu.pharmacie.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrestationGarantie {
    private String designation;
    private LocalDate datePriseEnCharge;
    private double montant;
    // Pertinent seulement si le régime du dossier est CESARIENNE.
    private String motifCesarienne;

    // Calculés en service à partir du régime du dossier — jamais saisis directement.
    private double montantBeneficiaire;
    private double montantSencsu;
}
