package com.csu.pharmacie.identite.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.csu.pharmacie.entity.Role;

/**
 * Contrats d'entrée/sortie de l'authentification.
 *
 * <p>La forme de {@link ReponseConnexion} reprend exactement celle du monolithe : le
 * dashboard Angular stocke la réponse telle quelle en session et lit ses champs partout.
 * Les nouveautés ({@code districtId}, {@code structureRattachementId}) sont ajoutées
 * <em>en plus</em>, jamais en remplacement.</p>
 */
public final class DtosAuth {

    private DtosAuth() {
    }

    @Data
    public static class RequeteConnexion {
        /** Email OU identifiant attribué à l'import — le champ historique du formulaire. */
        private String email;
        /** Alias accepté pour les nouveaux clients. */
        private String identifiant;
        @NotBlank(message = "le mot de passe est obligatoire")
        private String password;

        public String identifiantSaisi() {
            if (email != null && !email.isBlank()) {
                return email.trim();
            }
            return identifiant == null ? "" : identifiant.trim();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReponseConnexion {
        private String token;
        private long expiresIn;
        private String userId;
        private String email;
        private String username;
        private Boolean mustChangePassword;
        private String nom;
        private String prenom;
        private Role role;
        /** Rôle réellement appliqué (POSTE_SANTE / CENTRE_SANTE pour les comptes génériques). */
        private Role roleEffectif;
        private String pharmacieId;
        private String regionId;
        private String districtId;
        private String districtNom;
        private String structureSanitaireId;
        private String structureNom;
        private String structureType;
        private String structureRattachementId;
        private String structureRattachementNom;
        /** Centre de santé siège du district : l'interface ouvre aussi les écrans du district. */
        private boolean siegeDistrict;
        /** Pointage BCSU du jour (renseigné pour les agents BCSU) — voir ServiceAuthentification. */
        private String pointageHeure;
        private Boolean premierPointage;
        private String pointageDepartHeure;
    }

    @Data
    public static class RequeteChangementMotDePasse {
        @NotBlank(message = "le mot de passe actuel est obligatoire")
        private String ancienMotDePasse;
        @NotBlank(message = "le nouveau mot de passe est obligatoire")
        @Size(min = 8, message = "le nouveau mot de passe doit faire au moins 8 caractères")
        private String nouveauMotDePasse;
    }
}
