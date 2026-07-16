package com.csu.pharmacie.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Historique générique (lettre de garantie / bon de commande) : mêmes champs que
 * HistoriqueAction (Facture), mais avec un statut en String car partagé entre deux
 * enums de statut différents (StatutLettreGarantie / StatutBonCommande).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoriqueDossier {
    private LocalDateTime date;
    private String utilisateurId;
    private String utilisateurNom;
    private String statut;
    private String commentaire;
}
