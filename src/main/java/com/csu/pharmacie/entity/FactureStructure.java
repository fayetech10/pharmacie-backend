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

/**
 * Facture d'une structure sanitaire : un dossier par lettre de garantie (un patient),
 * complété par l'agent puis transmis au Service Régional. Réutilise StatutFacture pour
 * le workflow de validation (BROUILLON → ENVOYEE → VALIDEE_SR / REJETEE_SR → ...).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "factures_structure", indexes = {
        @Index(name = "idx_fs_numero", columnList = "numero", unique = true),
        @Index(name = "idx_fs_structure", columnList = "structure_sanitaire_id"),
        @Index(name = "idx_fs_region", columnList = "region_id")
})
public class FactureStructure {
    @Id
    @UuidGenerator
    private String id;

    @Column(unique = true, nullable = false)
    private String numero;

    private String structureSanitaireId;
    private String structureNom;
    private String regionId;
    private String bcsuId;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(255)")
    private Regime regime;

    // Classement « par régime et par mois » côté « Mes factures ».
    private int mois;
    private int annee;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<LigneFactureStructure> lignes = new ArrayList<>();

    private double montantTotal;
    private double montantTotalBeneficiaire;
    private double montantTotalSencsu;

    @Enumerated(EnumType.STRING)
    private StatutFacture statut;

    @Column(columnDefinition = "text")
    private String ticketCaisse;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<DocumentComplementaire> documentsComplementaires = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<HistoriqueDossier> historique = new ArrayList<>();

    private String commentaireRejet;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
}
