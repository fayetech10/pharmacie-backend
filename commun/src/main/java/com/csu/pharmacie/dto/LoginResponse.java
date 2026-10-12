package com.csu.pharmacie.dto;

import com.csu.pharmacie.entity.Role;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {
    private String token;
    private String userId;
    private String email;
    /** Identifiant de connexion attribué à l'import (null pour les comptes créés à la main). */
    private String username;
    /** Vrai tant que le mot de passe générique n'a pas été changé : l'app force le changement. */
    private Boolean mustChangePassword;
    private String nom;
    private String prenom;
    private Role role;
    private String pharmacieId;
    private String regionId;
    private String structureSanitaireId;
    private String structureType;
    private String structureNom;

    // Contrôle du pointage à la connexion (agents BCSU) :
    // heure du pointage du jour et indicateur « première connexion de la journée ».
    private String pointageHeure;
    private Boolean premierPointage;
    private String pointageDepartHeure;
}

