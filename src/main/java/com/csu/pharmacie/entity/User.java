package com.csu.pharmacie.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
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
// "user" est un mot réservé PostgreSQL → table "users".
@Table(name = "users", indexes = {
        // Recherché à chaque requête authentifiée (filtre JWT + getCurrentUser) → index indispensable.
        @Index(name = "idx_user_email", columnList = "email")
})
public class User {
    @Id
    @UuidGenerator
    private String id;
    private String nom;
    private String prenom;
    private String email;
    // Le hash ne doit jamais être renvoyé dans les réponses JSON (mais reste accepté en entrée)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    @Enumerated(EnumType.STRING)
    private Role role;
    private String pharmacieId;
    private String regionId;
    // Structure sanitaire de rattachement (rôle STRUCTURE_SANITAIRE).
    private String structureSanitaireId;
    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
