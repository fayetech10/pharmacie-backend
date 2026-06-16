package com.csu.pharmacie.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "factures")
// Couvre findByPharmacieId, findByPharmacieIdAndMoisAndAnnee et findRetards (préfixe pharmacieId).
@CompoundIndex(name = "pharmacie_periode_idx", def = "{'pharmacieId': 1, 'annee': 1, 'mois': 1}")
public class Facture {
    @Id
    private String id;
    private String pharmacieId;
    private String pharmacieNom;
    // Liste régionale (findByRegionId).
    @Indexed
    private String regionId;
    private int mois;
    private int annee;
    private double montantTotal;
    private StatutFacture statut;
    
    @Builder.Default
    private List<LigneFacture> lignes = new ArrayList<>();
    
    @Builder.Default
    private List<HistoriqueAction> historique = new ArrayList<>();
    
    private String commentaireRejet;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
}
