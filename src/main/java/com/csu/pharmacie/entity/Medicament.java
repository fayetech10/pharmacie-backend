package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
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
@Table(name = "medicaments")
public class Medicament {
    @Id
    @UuidGenerator
    private String id;
    private String code;
    private String nom; // Nom commercial
    private String dci;
    private String classeTherapeutique;
    private String liste;
    @Enumerated(EnumType.STRING)
    private StatutMedicament statut;
    @Column(columnDefinition = "text")
    private String description;
    @Column(columnDefinition = "text")
    private String motif; // Motif d'exclusion (renseigné pour les médicaments EXCLU)
    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
