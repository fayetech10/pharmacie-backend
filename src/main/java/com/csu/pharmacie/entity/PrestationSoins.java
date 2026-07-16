package com.csu.pharmacie.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Ligne du tableau « PRISE EN CHARGE » de la feuille de soins papier :
 * Date | Désignation des prestations | Montant | Part Assuré | Part Assureur.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrestationSoins {
    private LocalDate date;
    private String designation;
    private double montant;

    // Calculés en service à partir du régime de la feuille — jamais saisis directement.
    private double partAssure;
    private double partAssureur;
}
