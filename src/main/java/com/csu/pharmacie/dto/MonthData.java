package com.csu.pharmacie.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthData {
    private int mois;
    private int annee;
    private long nombreFactures;
    private double montantTotal;
}
