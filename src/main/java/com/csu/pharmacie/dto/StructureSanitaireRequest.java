package com.csu.pharmacie.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StructureSanitaireRequest {
    @NotBlank(message = "Le code est obligatoire")
    private String code;
    @NotBlank(message = "Le nom est obligatoire")
    private String nom;
    private String adresse;
    private String telephone;
    private String email;
    @NotBlank(message = "La région est obligatoire")
    private String regionId;
    // BCSU de rattachement (optionnel — le rattachement se fait aussi via la création d'utilisateur).
    private String bcsuId;
    // Mot de passe du compte agent créé avec la structure (optionnel : défaut password123).
    private String password;
}
