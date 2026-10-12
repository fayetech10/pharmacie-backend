package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Facture d'un patient payant — module Hors CSU (§8 du document).
 *
 * <p>Rien à voir avec une facture CSU : elle est individuelle, encaissée immédiatement à
 * la caisse, et n'entre dans aucun circuit de validation ni dans les consolidations
 * remboursées par la SEN-CSU. D'où une entité distincte plutôt qu'un régime de plus, qui
 * aurait fait remonter des patients payants dans les montants pris en charge.</p>
 *
 * <p>Une seule table pour les trois points d'encaissement (poste, centre, pharmacie) :
 * {@link #structureSanitaireId} cloisonne, et les situations de caisse sont calculées par
 * le même code partout.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "factures_hors_csu", indexes = {
        @Index(name = "idx_hors_csu_numero", columnList = "numero", unique = true),
        @Index(name = "idx_hors_csu_structure_date", columnList = "structure_sanitaire_id, date")
})
public class FactureHorsCsu {

    @Id
    @UuidGenerator
    private String id;

    @Column(unique = true, nullable = false)
    private String numero;

    private String structureSanitaireId;
    private String structureNom;
    private String districtId;
    private String regionId;
    /** Pharmacie émettrice, quand l'encaissement vient du comptoir de l'officine (§14). */
    private String pharmacieId;

    /** Date d'encaissement — c'est elle qui classe la facture dans la situation journalière. */
    private LocalDate date;

    private String patientNom;
    private String patientPrenom;
    private String patientTelephone;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<LigneHorsCsu> lignes = new ArrayList<>();

    private double montantTotal;
    /** Montant réellement encaissé — peut être inférieur au total (règlement partiel). */
    private double montantEncaisse;

    /** Espèces, mobile money, chèque… champ libre pour coller aux pratiques locales. */
    private String modePaiement;

    /**
     * Annulation plutôt que suppression : une facture encaissée doit rester dans la
     * situation de caisse, sinon la recette du jour ne se rapproche plus.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean annulee = false;
    private String motifAnnulation;

    private LocalDateTime createdAt;
    private String createdBy;
    private String createdByNom;

    @PrePersist
    void avantCreation() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (date == null) {
            date = LocalDate.now();
        }
    }

    public String patientNomComplet() {
        return ((patientPrenom == null ? "" : patientPrenom) + " "
                + (patientNom == null ? "" : patientNom)).trim();
    }
}
