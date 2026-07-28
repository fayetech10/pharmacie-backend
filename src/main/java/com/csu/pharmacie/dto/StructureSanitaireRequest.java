package com.csu.pharmacie.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StructureSanitaireRequest {
    // Généré automatiquement à la création ({REGION}{TYPE}{NN}, ex: DKCS01) si laissé vide.
    private String code;
    // Type d'établissement pour le code auto-généré : CS (centre de santé), EPS (hôpital)…
    private String typeStructure;
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
    // Poste de santé (typeStructure = PS) : structure sanitaire (CS/EPS) de rattachement.
    private String structureRattachementId;
}
