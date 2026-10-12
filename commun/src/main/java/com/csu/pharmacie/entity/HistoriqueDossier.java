package com.csu.pharmacie.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class HistoriqueDossier {
    private LocalDateTime date;
    private String utilisateurId;
    private String utilisateurNom;
    private String statut;
    private String commentaire;

    // ─── Traçabilité des modifications (facture d'un point de soins) ───
    /** Dossier patient concerné, quand la modification porte sur un patient. */
    private String dossierId;
    /** Information modifiée (« Prestation », « Quantité », « Téléphone »…). */
    private String champ;
    private String ancienneValeur;
    private String nouvelleValeur;
}
