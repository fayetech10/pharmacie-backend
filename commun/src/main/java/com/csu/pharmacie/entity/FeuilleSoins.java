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
 * Feuille de soins (formulaire papier ASCSU) : document nominatif qui transite
 * entre le patient, l'agent bureau (BCSU) qui la délivre, et la structure sanitaire
 * qui remplit la prise en charge puis la rattache à sa facturation.
 * Les champs reprennent les rubriques du formulaire papier : régime, code assuré /
 * n° immatriculation, n° lettre de garantie, identité, accompagnant, prise en charge,
 * signatures (assureur / prestataire / assuré).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "feuilles_soins", indexes = {
        @Index(name = "idx_fds_numero", columnList = "numero", unique = true),
        @Index(name = "idx_fds_patient", columnList = "patient_id"),
        @Index(name = "idx_fds_lettre", columnList = "lettre_garantie_id"),
        @Index(name = "idx_fds_structure", columnList = "structure_sanitaire_id"),
        @Index(name = "idx_fds_created_by", columnList = "created_by")
})
public class FeuilleSoins {
    @Id
    @UuidGenerator
    private String id;

    @Column(unique = true, nullable = false)
    private String numero;

    // ---- En-tête du formulaire papier : Date / Structure / N° ----
    /** Structure sanitaire de dépôt — renseignée quand le patient remet la feuille. */
    private String structureSanitaireId;
    private String structureNom;

    // ---- Rattachement au dossier CSU ----
    private String lettreGarantieId;
    private String lettreGarantieNumero;

    // ---- Bénéficiaire (dénormalisé : état du patient au moment de la délivrance) ----
    private String patientId;
    private String patientNom;
    private String patientPrenom;
    private String patientTelephone;
    /** Code assuré / N° immatriculation (rubrique dédiée du formulaire). */
    private String codeAssure;
    /** Sexe M/F (cases à cocher du formulaire). */
    private String sexe;
    private Integer age;
    private String diagnostic;

    // ---- Accompagnant ----
    private String accompagnantPrenomNom;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(255)")
    private Regime regime;

    // ---- Tableau « PRISE EN CHARGE » : rempli par la structure sanitaire ----
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<PrestationSoins> prestations = new ArrayList<>();

    private double montantTotal;
    private double montantTotalAssure;
    private double montantTotalAssureur;

    @Enumerated(EnumType.STRING)
    private StatutFeuilleSoins statut;

    // ---- Fin du circuit : facture de la structure à laquelle la feuille est rattachée ----
    private String factureStructureId;
    private String factureStructureNumero;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<HistoriqueDossier> historique = new ArrayList<>();

    private String commentaireAnnulation;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
}
