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
        @Index(name = "idx_user_email", columnList = "email"),
        // Identifiant de connexion alternatif attribué automatiquement aux pharmacies/structures.
        @Index(name = "idx_user_username", columnList = "username")
})
public class User {
    @Id
    @UuidGenerator
    private String id;
    private String nom;
    private String prenom;
    private String email;
    /**
     * Identifiant de connexion attribué automatiquement à l'import (pharmacies, structures),
     * construit à partir du code de la région. Utilisable à la place de l'email pour se connecter.
     */
    private String username;
    // Le hash ne doit jamais être renvoyé dans les réponses JSON (mais reste accepté en entrée)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    @Enumerated(EnumType.STRING)
    private Role role;
    private String pharmacieId;
    private String regionId;
    // Structure sanitaire de rattachement (rôle STRUCTURE_SANITAIRE).
    private String structureSanitaireId;
    // Paramétrage BCSU : cachet et signature (images data-URL) apposés sur les lettres de garantie.
    @Column(columnDefinition = "text")
    private String cachetImage;
    @Column(columnDefinition = "text")
    private String signatureImage;
    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;
    /**
     * Mot de passe générique attribué à la création en masse : l'utilisateur doit le
     * changer à sa première connexion. Wrapper nullable → les comptes antérieurs ne
     * sont pas impactés (null = pas de changement imposé).
     */
    private Boolean mustChangePassword;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
