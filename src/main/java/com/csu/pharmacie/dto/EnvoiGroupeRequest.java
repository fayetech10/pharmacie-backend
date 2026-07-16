package com.csu.pharmacie.dto;

import com.csu.pharmacie.entity.Regime;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Envoi groupé d'une facture mensuelle (toutes les factures brouillon d'un régime + mois). */
@Data
public class EnvoiGroupeRequest {
    @NotNull(message = "Le régime est obligatoire")
    private Regime regime;
    @Min(1) @Max(12)
    private int mois;
    @Min(2020)
    private int annee;
}
