package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "bons_commande", indexes = {
        @Index(name = "idx_bon_numero", columnList = "numero", unique = true),
        @Index(name = "idx_bon_lettre", columnList = "lettre_garantie_id")
})
public class BonCommande {
    @Id
    @UuidGenerator
    private String id;

    @Column(unique = true, nullable = false)
    private String numero;

    private String lettreGarantieId;
    // Dénormalisé pour affichage rapide en recherche, sans recharger la lettre.
    private String lettreGarantieNumero;

    private String patientNom;
    private String patientPrenom;
    // Numéro matricule / d'immatriculation du bénéficiaire (dénormalisé depuis la lettre de garantie).
    private String patientMatricule;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(255)")
    private Regime regime;

    // Nombre de lignes de médicaments à imprimer (vides) : saisi par le BCSU,
    // le pharmacien remplit ensuite les médicaments à la main sur le bon.
    private int nombreLignes;

    // Copie figée des prestations au moment de la génération du bon (traçabilité).
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<PrestationGarantie> prestations = new ArrayList<>();

    private double montantTotal;
    private double montantTotalBeneficiaire;
    private double montantTotalSencsu;

    @Enumerated(EnumType.STRING)
    private StatutBonCommande statut;

    // Usage multi-pharmacies : un bon peut être servi par PLUSIEURS pharmacies jusqu'à
    // ce que toutes ses lignes (nombreLignes) soient consommées. Compteur cumulatif des
    // lignes de médicaments déjà facturées (toutes pharmacies confondues).
    // Integer nullable → les bons antérieurs (colonne NULL après migration ddl-auto) = 0.
    @Builder.Default
    private Integer lignesUtilisees = 0;
    private LocalDateTime dateDerniereUtilisation;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<HistoriqueDossier> historique = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;

    // ---- Helpers multi-pharmacies ----

    /** Nombre de lignes encore disponibles (non consommées) sur ce bon. */
    public int lignesDisponibles() {
        return nombreLignes - (lignesUtilisees != null ? lignesUtilisees : 0);
    }

    /** Vrai si toutes les lignes du bon ont été consommées (aucune ligne disponible). */
    public boolean estComplet() {
        return lignesDisponibles() <= 0;
    }
}
