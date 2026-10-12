package com.csu.pharmacie.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Agrégat d'une pharmacie (classement top pharmacies). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PharmacieStat {
    private String id;
    private String nom;
    private long nombreFactures;
    private double montant;
}
