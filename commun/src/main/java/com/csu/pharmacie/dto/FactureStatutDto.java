package com.csu.pharmacie.dto;

import com.csu.pharmacie.entity.StatutFacture;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Projection minimale d'une facture — identifiant + statut — destinée aux badges
 * de notification.
 *
 * <p>Les compteurs n'ont besoin que de ces deux champs : les charger via une
 * projection JPQL évite de désérialiser les colonnes JSONB (lignes, historique)
 * de chaque facture à chaque navigation.
 */
@Data
@AllArgsConstructor
public class FactureStatutDto {
    private String id;
    private StatutFacture statut;
}
