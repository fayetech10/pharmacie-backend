package com.csu.pharmacie.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Agrégat d'une région (activité par région, nom résolu). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegionStat {
    private String id;
    private String nom;
    private long nombreFactures;
    private double montant;
}
