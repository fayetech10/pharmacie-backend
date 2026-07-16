package com.csu.pharmacie.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LigneFacture {
    private String patientNomPrenom;
    private String patientMatricule;
    private String medicament;
    private String codeProduit;
    private int quantite;
    private double prixUnitaire;
    private double montant;

    @Builder.Default
    private StatutLigne statutLigne = StatutLigne.EN_ATTENTE;
    private String motifRejet;

    // Numéro du bon de commande numérique (BCSU) intégré au dossier patient, le cas échéant.
    private String bonCommandeNumero;

    // Pièces justificatives du dossier patient (images encodées en base64 / data URL)
    private String ticketCaisse;
    private String bonCommande;
    private String ordonnance;

    // Ajouté pour l'affichage dans l'historique (nom de la pharmacie)
    private String pharmacieNom;
}
