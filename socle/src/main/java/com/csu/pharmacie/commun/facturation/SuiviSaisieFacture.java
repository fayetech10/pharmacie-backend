package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.Regime;

/** Résumé sans données patient pour le suivi régional des factures en cours de saisie. */
public record SuiviSaisieFacture(
        String structureSanitaireId,
        int mois,
        int annee,
        Regime regime,
        StatutFacture statut,
        int nombreBeneficiaires,
        double montantTotal,
        double montantSencsu) {
}
