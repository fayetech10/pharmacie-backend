package com.csu.pharmacie.dto;

import com.csu.pharmacie.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserRequest {
    // Facultatifs pour un compte Service Régional (rattaché à une région, sans identité
    // personnelle : le formulaire masque ces champs) ; obligatoires pour les autres rôles.
    // Le contrôle conditionnel par rôle est fait dans UserService (@NotBlank ne peut pas
    // dépendre d'un autre champ).
    private String nom;

    private String prenom;

    @NotBlank(message = "L'email est obligatoire")
    @Email(message = "Format d'email invalide")
    private String email;

    // Optionnel en modification (mot de passe conservé si absent) ; s'il est
    // fourni, il doit faire au moins 8 caractères.
    @Size(min = 8, message = "Le mot de passe doit contenir au moins 8 caractères")
    private String password;

    @NotNull(message = "Le rôle est obligatoire")
    private Role role;

    private String pharmacieId;
    private String regionId;
    private String structureSanitaireId;
}
