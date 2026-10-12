package com.csu.pharmacie.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
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

    // Vrai lorsque le pharmacien a ajouté lui-même ce médicament (non répertorié dans les
    // éligibles ni les exclusions). Alimente la colonne « Observation » des factures Excel :
    // la SEN-CSU peut alors valider son intégration ou l'exclure définitivement.
    @Builder.Default
    private boolean ajouteParPharmacien = false;

    // Numéro du bon de commande numérique (BCSU) intégré au dossier patient, le cas échéant.
    private String bonCommandeNumero;

    // Pièces justificatives du dossier patient (images encodées en base64 / data URL)
    private String ticketCaisse;
    private String bonCommande;
    private String ordonnance;

    // Ajouté pour l'affichage dans l'historique (nom de la pharmacie)
    private String pharmacieNom;
}
