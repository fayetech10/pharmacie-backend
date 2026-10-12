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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "patients", indexes = {
        @Index(name = "idx_patient_nom", columnList = "nom"),
        @Index(name = "idx_patient_cni", columnList = "numero_cni"),
        @Index(name = "idx_patient_assure", columnList = "numero_assure")
})
public class Patient {
    @Id
    @UuidGenerator
    private String id;
    private String nom;
    private String prenom;
    private String telephone;
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(255)")
    private Regime regime;
    // Renseignés par l'OCR des pièces (CNI / carte d'assuré) ou complétés manuellement.
    private String numeroCni;
    private String numeroAssure;
    private LocalDate dateNaissance;
    /** Sexe du bénéficiaire ("M"/"F") — requis par le classeur CS_EPS (colonne Sexe de chaque régime). */
    private String sexe;
    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
}
