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
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/**
 * Constat d'activité saisi par un agent BCSU : description libre d'une observation
 * (anomalie, remarque, incident…) rencontrée pour un régime donné dans sa structure.
 * Chaque agent saisit et consulte ses propres constats.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "constats", indexes = {
        @Index(name = "idx_constat_created_by", columnList = "created_by")
})
public class Constat {
    @Id
    @UuidGenerator
    private String id;

    // varchar(255) (pas d'enum SQL) : évite une contrainte CHECK figée quand l'enum Regime évolue.
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(255)")
    private Regime regime;

    @Column(columnDefinition = "text", nullable = false)
    private String description;

    // Agent BCSU dénormalisé (comme Pointage) : lister/afficher sans jointure.
    private String agentNom;
    private String agentPrenom;
    // Structure de rattachement de l'agent au moment de la saisie.
    private String structureNom;

    private LocalDateTime createdAt;
    private String createdBy;
}
