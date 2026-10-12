package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import com.csu.pharmacie.entity.Regime;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;

/**
 * Bénéficiaire enregistré au poste de santé (§3).
 *
 * <p>Le fichier des bénéficiaires est <em>local à la structure</em> : il évite la
 * ressaisie d'un mois sur l'autre, sans prétendre être un registre national. C'est aussi
 * ce qui rend la saisie possible hors ligne (§12) — aucun service distant n'est nécessaire
 * pour créer un patient.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "beneficiaires", indexes = {
        @Index(name = "idx_beneficiaire_structure", columnList = "structure_id"),
        @Index(name = "idx_beneficiaire_nom", columnList = "nom"),
        @Index(name = "idx_beneficiaire_matricule", columnList = "matricule")
})
public class Beneficiaire {

    @Id
    @UuidGenerator
    private String id;

    /** Structure qui a enregistré le bénéficiaire — cloisonne la recherche. */
    private String structureId;

    @Column(nullable = false)
    private String nom;
    private String prenom;
    /** « M » ou « F » — colonne exigée par les modèles officiels. */
    private String sexe;
    private LocalDate dateNaissance;
    private String telephone;
    private String adresse;
    private String numeroCni;

    /** Matricule du régime ; généré automatiquement pour le Plan Sésame (§4). */
    private String matricule;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(32)")
    private Regime regime;

    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;

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

    public String nomComplet() {
        return ((prenom == null ? "" : prenom) + " " + (nom == null ? "" : nom)).trim();
    }

    /** Âge en années à une date donnée ; {@code null} si la date de naissance manque. */
    public Integer ageA(LocalDate reference) {
        if (dateNaissance == null) {
            return null;
        }
        LocalDate date = reference == null ? LocalDate.now() : reference;
        return date.isBefore(dateNaissance) ? null : Period.between(dateNaissance, date).getYears();
    }
}
