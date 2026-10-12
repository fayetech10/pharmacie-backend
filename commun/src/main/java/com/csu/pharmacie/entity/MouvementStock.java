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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Mouvement de stock — la trace de tout ce qui entre et sort.
 *
 * <p>{@code referenceLigne} porte un index unique : c'est lui qui rend la sortie
 * automatique idempotente (§14). Si le point de soins rejoue l'appel — réenregistrement
 * de la facture, reprise après une coupure réseau — la base refuse le doublon et le
 * service renvoie le mouvement déjà enregistré au lieu de décrémenter une seconde fois.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "mouvements_stock", indexes = {
        @Index(name = "idx_mouvement_article", columnList = "article_id"),
        @Index(name = "idx_mouvement_structure_date", columnList = "structure_id, date"),
        @Index(name = "idx_mouvement_reference", columnList = "reference_ligne", unique = true)
})
public class MouvementStock {

    public enum Type {
        ENTREE,
        SORTIE,
        /** Correction d'inventaire — ajuste sans prétendre à une origine commerciale. */
        AJUSTEMENT
    }

    @Id
    @UuidGenerator
    private String id;

    private String articleId;
    private String structureId;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(16)")
    private Type type;

    private String designation;
    private int quantite;
    /** Quantité en stock après application du mouvement — fige l'historique. */
    private int stockApres;

    private LocalDate date;
    private String motif;

    /** Facture à l'origine du mouvement (sortie automatique). */
    private String referenceFacture;

    /**
     * Clé d'idempotence : {@code <numéro de facture>#<index de ligne>}.
     * Unique en base — c'est la garantie technique du « une seule sortie par ligne ».
     */
    @Column(unique = true)
    private String referenceLigne;

    private String createdBy;
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
