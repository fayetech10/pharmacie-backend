package com.csu.pharmacie.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Agrégat d'un médicament sur l'ensemble des lignes de factures (top médicaments). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicamentStat {
    private String nom;
    private long quantite;
    private double montant;
    private long nombreLignes;
}
