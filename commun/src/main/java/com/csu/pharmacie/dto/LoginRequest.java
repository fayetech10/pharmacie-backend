package com.csu.pharmacie.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    // L'identifiant de connexion peut être un email OU le username attribué aux pharmacies
    // (ex. « dk.ph001 ») et structures sanitaires (ex. « DKCS01 »). Surtout PAS de @Email ici : il rejetterait
    // ces usernames (400) avant l'authentification, alors que le service et le frontend gèrent
    // déjà « email OU username ». Le champ garde le nom « email » (contrat inchangé côté client).
    @NotBlank(message = "L'identifiant est obligatoire")
    private String email;

    @NotBlank(message = "Le mot de passe est obligatoire")
    private String password;
}
