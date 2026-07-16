package com.csu.pharmacie.dto;

import com.csu.pharmacie.entity.Regime;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Délivrance d'une feuille de soins par l'agent bureau (BCSU).
 * Le patient et la lettre de garantie peuvent être rattachés à un dossier existant ;
 * la prise en charge (prestations) sera remplie plus tard par la structure sanitaire.
 */
@Data
public class FeuilleSoinsRequest {
    /** Si renseigné : rattache la feuille à un patient existant. */
    private String patientId;
    /** Si renseigné : rattache la feuille à une lettre de garantie existante (numéro). */
    private String lettreGarantieNumero;

    @NotBlank(message = "Le nom du patient est obligatoire")
    private String nom;
    @NotBlank(message = "Le prénom du patient est obligatoire")
    private String prenom;
    @NotNull(message = "Le régime est obligatoire")
    private Regime regime;

    private String telephone;
    /** Code assuré / N° immatriculation. */
    private String codeAssure;
    /** M ou F (cases du formulaire papier). */
    private String sexe;
    private Integer age;
    private String diagnostic;
    private String accompagnantPrenomNom;
}
