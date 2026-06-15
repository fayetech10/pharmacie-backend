package com.csu.pharmacie.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class StatsDto {
    // Indicateurs principaux
    private long nombreFactures;
    private double montantTotal;
    private double montantCsu;       // part CSU prise en charge (50 %)
    private double montantMoyen;     // montant moyen par facture

    // Indicateurs de performance
    private double tauxValidation;            // % de factures traitées validées
    private double tauxRejet;                 // % de factures traitées rejetées
    private double delaiMoyenTraitementJours; // délai moyen envoi → 1ère décision (SR)
    private long lignesAcceptees;
    private long lignesRejetees;

    // Répartitions
    private Map<String, Long> facturesParStatut;
    private Map<String, Double> montantParStatut;
    private List<RegionStat> parRegion;
    private List<PharmacieStat> topPharmacies;
    private List<MedicamentStat> topMedicaments;

    private List<MonthData> evolutionMensuelle;
}
