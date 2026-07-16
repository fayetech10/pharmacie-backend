package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "structures_sanitaires", indexes = {
        @Index(name = "idx_structure_region", columnList = "region_id"),
        @Index(name = "idx_structure_bcsu", columnList = "bcsu_id")
})
public class StructureSanitaire {
    @Id
    @UuidGenerator
    private String id;
    private String code;
    private String nom;
    private String adresse;
    private String telephone;
    private String email;
    private String regionId;
    // Agent BCSU (utilisateur, rôle BCSU) auquel la structure est rattachée :
    // la structure ne peut facturer que sur les lettres de garantie émises par ce BCSU.
    private String bcsuId;
    private String responsableId;
    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
