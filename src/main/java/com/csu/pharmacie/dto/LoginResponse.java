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
    private String nom;
    private String prenom;
    private Role role;
    private String pharmacieId;
    private String regionId;
    private String structureSanitaireId;

    // Contrôle du pointage à la connexion (agents BCSU) :
    // heure du pointage du jour et indicateur « première connexion de la journée ».
    private String pointageHeure;
    private Boolean premierPointage;
    private String pointageDepartHeure;
}

