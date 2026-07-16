package com.csu.pharmacie.dto;

import com.csu.pharmacie.entity.Regime;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PatientRequest {
    @NotBlank(message = "Le nom est obligatoire")
    private String nom;
    @NotBlank(message = "Le prénom est obligatoire")
    private String prenom;
    @NotBlank(message = "Le téléphone est obligatoire")
    private String telephone;
    @NotNull(message = "Le régime est obligatoire")
    private Regime regime;
    private String numeroCni;
    private String numeroAssure;
    /** Date de naissance (yyyy-MM-dd) — utilisée pour calculer l'âge sur la feuille de soins. */
    private LocalDate dateNaissance;
    /** Sexe du bénéficiaire ("M"/"F") — rubrique de la feuille de soins. */
    private String sexe;
}
