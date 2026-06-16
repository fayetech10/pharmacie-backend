package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "factures", indexes = {
        // Couvre findByPharmacieId, findByPharmacieIdAndMoisAndAnnee et findRetards (préfixe pharmacie_id).
        @Index(name = "idx_facture_pharmacie_periode", columnList = "pharmacie_id, annee, mois"),
        // Liste régionale (findByRegionId).
        @Index(name = "idx_facture_region", columnList = "region_id")
})
public class Facture {
    @Id
    @UuidGenerator
    private String id;
    private String pharmacieId;
    private String pharmacieNom;
    private String regionId;
    private int mois;
    private int annee;
    private double montantTotal;
    @Enumerated(EnumType.STRING)
    private StatutFacture statut;

    // Les lignes (avec leurs pièces base64) et l'historique sont des sous-documents :
    // on les conserve tels quels en colonnes JSONB, sans table relationnelle dédiée.
    // L'application n'interroge jamais l'intérieur de ces listes côté BD (toute
    // l'agrégation se fait en mémoire), donc JSONB est le choix le plus simple et fidèle.
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<LigneFacture> lignes = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<HistoriqueAction> historique = new ArrayList<>();

    private String commentaireRejet;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
}
