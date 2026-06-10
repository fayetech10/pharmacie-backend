package com.csu.pharmacie.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "medicaments")
public class Medicament {
    @Id
    private String id;
    private String code;
    private String nom; // Nom commercial
    private String dci;
    private String classeTherapeutique;
    private String liste;
    private StatutMedicament statut;
    private String description;
    @Builder.Default
    private boolean actif = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
