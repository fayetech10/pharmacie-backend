package com.csu.pharmacie.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Ligne d'une facture Hors CSU : un acte vendu à un patient payant (§8). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LigneHorsCsu {

    /** Tarif du référentiel Hors CSU ; null pour une saisie libre. */
    private String tarifId;
    private String code;
    private String designation;
    private String categorie;
    private int quantite;
    private double prixUnitaire;
    /** Calculé : quantite × prixUnitaire. */
    private double montant;
}
