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
 * Pointage journalier des agents : une ligne par agent et par jour,
 * créée automatiquement lors de la première connexion de la journée.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pointages", indexes = {
        // Contrôle « déjà pointé aujourd'hui ? » à chaque login.
        @Index(name = "idx_pointage_user_date", columnList = "user_id, date", unique = true)
})
public class Pointage {
    @Id
    @UuidGenerator
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;
    // Dénormalisé pour lister les pointages sans jointure.
    private String userNom;
    private String userPrenom;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(nullable = false)
    private LocalDate date;

    /** Heure de la première connexion de la journée. */
    private LocalDateTime heureArrivee;

    /** Heure de départ déclarée par l'agent. */
    private LocalDateTime heureDepart;

    /** Heure de la dernière connexion de la journée. */
    private LocalDateTime derniereConnexion;
}

