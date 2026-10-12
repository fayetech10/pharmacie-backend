package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Article tenu en stock (médicament, consommable).
 *
 * <p>Le stock est tenu <em>par structure</em> : le même médicament existe dans plusieurs
 * dépôts avec des quantités différentes, et une sortie déclenchée par un poste ne doit
 * jamais décrémenter le stock d'un autre.</p>
 *
 * <p>{@code @Version} : deux sorties simultanées sur le même article sont détectées au
 * lieu d'écraser silencieusement l'une des deux quantités.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "articles_stock", indexes = {
        @Index(name = "idx_article_structure_code", columnList = "structure_id, code", unique = true),
        @Index(name = "idx_article_structure", columnList = "structure_id")
})
public class ArticleStock {

    @Id
    @UuidGenerator
    private String id;

    /** Structure (ou pharmacie) qui détient ce stock. */
    private String structureId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String designation;

    private String forme;
    private String dosage;

    @Builder.Default
    @Column(nullable = false)
    private int quantite = 0;

    /** En dessous de ce seuil, l'article remonte dans les alertes de réapprovisionnement. */
    @Builder.Default
    private int seuilAlerte = 0;

    private double prixUnitaire;
    private LocalDate datePeremption;

    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;

    @Version
    private Long version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void avantCreation() {
        LocalDateTime maintenant = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = maintenant;
        }
        updatedAt = maintenant;
    }

    @PreUpdate
    void avantMiseAJour() {
        updatedAt = LocalDateTime.now();
    }

    public boolean enAlerte() {
        return actif && seuilAlerte > 0 && quantite <= seuilAlerte;
    }
}
