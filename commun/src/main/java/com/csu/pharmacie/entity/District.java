package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/**
 * District sanitaire — échelon intermédiaire entre la structure et la région.
 *
 * <p>C'est la maille de consolidation exigée au §9 du document : les factures des postes
 * et des centres d'un même district sont regroupées en un classeur unique et une situation
 * globale. Le monolithe ne connaissait que la région, d'où l'ajout de cette entité.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "districts", indexes = {
        @Index(name = "idx_district_region", columnList = "region_id"),
        @Index(name = "idx_district_code", columnList = "code", unique = true)
})
public class District {

    @Id
    @UuidGenerator
    private String id;

    @Column(unique = true, nullable = false)
    private String code;

    @Column(nullable = false)
    private String nom;

    /** Région de rattachement — obligatoire : un district orphelin sort de toute consolidation. */
    private String regionId;

    /** Médecin-chef ou point focal du district (compte utilisateur de rôle DISTRICT). */
    private String responsableId;

    private String telephone;
    private String email;

    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;

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
}
