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
@Table(name = "lettres_garantie", indexes = {
        @Index(name = "idx_lettre_numero", columnList = "numero", unique = true),
        @Index(name = "idx_lettre_patient", columnList = "patient_id"),
        @Index(name = "idx_lettre_created_by", columnList = "created_by")
})
public class LettreGarantie {
    @Id
    @UuidGenerator
    private String id;

    @Column(unique = true, nullable = false)
    private String numero;

    private String patientId;
    // Dénormalisé (comme Facture.pharmacieNom) : capture l'état du patient au moment du dossier.
    private String patientNom;
    private String patientPrenom;
    private String patientTelephone;
    private java.time.LocalDate patientDateNaissance;
    /** Sexe du bénéficiaire ("M"/"F") capturé au moment du dossier — repris dans l'export par régime. */
    private String patientSexe;
    private String patientNumeroAssure;
    private String structureNom;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(255)")
    private Regime regime;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<PrestationGarantie> prestations = new ArrayList<>();

    private double montantTotal;
    private double montantTotalBeneficiaire;
    private double montantTotalSencsu;

    @Enumerated(EnumType.STRING)
    private StatutLettreGarantie statut;

    // Pièces jointes recto/verso, encodées en base64 / data URL (limitées à 2 Mo côté DTO).
    @Column(columnDefinition = "text")
    private String cniRecto;
    @Column(columnDefinition = "text")
    private String cniVerso;
    // Carte d'assuré (recto uniquement) : pièce demandée pour les régimes Classique et BSF/CEC.
    @Column(columnDefinition = "text")
    private String carteAssureRecto;
    // Document libre (extrait de naissance pour le régime 0-5 ans) + son intitulé.
    @Column(columnDefinition = "text")
    private String autreDocument;
    private String autreDocumentTitre;
    // Numéros capturés au moment du dossier (OCR ou saisie manuelle de repli).
    private String cniNumeroOcr;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<HistoriqueDossier> historique = new ArrayList<>();

    private String commentaireAnnulation;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
}
