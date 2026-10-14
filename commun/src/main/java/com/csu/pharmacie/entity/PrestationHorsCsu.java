package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * Tarif applicable aux patients payants (§8, module Hors CSU).
 *
 * <p>Référentiel distinct de celui des prestations CSU, et pas un simple drapeau sur
 * {@link Prestation} : les tarifs Hors CSU sont fixés par la structure et n'ont ni les
 * mêmes montants ni le même cycle de mise à jour que la nomenclature conventionnée.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "prestations_hors_csu",
        uniqueConstraints = @UniqueConstraint(name = "uk_hors_csu_code_structure",
                columnNames = {"code", "structure_id"}),
        indexes = {
                @Index(name = "idx_hors_csu_code_str", columnList = "code")
        })
public class PrestationHorsCsu {

    @Id
    @UuidGenerator
    private String id;

    /**
     * Unique <b>par structure</b>, et non globalement : le même code désigne le tarif
     * national et la version que la structure s'est fixée — c'est exactement ce que
     * {@code ServiceHorsCsu.applicables} arbitre (« la version propre à la structure
     * l'emporte »). Un unique global rendait cet arbitrage impossible et faisait pire :
     * deux postes important le même fichier écrasaient mutuellement leurs tarifs.
     */
    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String designation;

    /** Regroupement libre (Consultation, Analyses, Imagerie…) — sert au filtrage à la caisse. */
    private String categorie;

    private double prix;

    /**
     * Tarif propre à une structure. {@code null} = tarif national, applicable partout.
     * Permet à un centre de surcharger un tarif sans dupliquer tout le référentiel.
     */
    private String structureId;

    /**
     * Ligne nationale réservée à un type de structure : {@code "PS"} = prestations par défaut
     * des postes de santé (liste fournie le 14/10/2026), invisibles aux centres et EPS.
     * {@code null} = toutes les structures. Sans objet pour un tarif propre à une structure.
     */
    @Column(name = "type_structure", length = 10)
    private String typeStructure;

    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;
}
