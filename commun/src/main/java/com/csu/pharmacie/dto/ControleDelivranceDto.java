package com.csu.pharmacie.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Résultat du contrôle « médicament déjà délivré durant le mois ».
 * Le périmètre est NATIONAL : toutes les pharmacies de la base sont scannées, afin
 * qu'un bénéficiaire ne puisse pas se faire délivrer le même traitement mensuel en
 * changeant de pharmacie ou de région.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControleDelivranceDto {

    /** Quantité totale déjà délivrée au bénéficiaire ce mois-ci pour le médicament contrôlé. */
    private int quantiteDejaDelivree;

    /** Détail par pharmacie, pour que le pharmacien puisse vérifier le traitement mensuel. */
    private List<Delivrance> delivrances;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Delivrance {
        private String pharmacieNom;
        private String medicament;
        private int quantite;
    }
}
