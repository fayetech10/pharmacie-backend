package com.csu.pharmacie.dto;

import lombok.Data;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

@Data
public class FactureRequest {
    @Min(value = 1, message = "Le mois doit être entre 1 et 12")
    @Max(value = 12, message = "Le mois doit être entre 1 et 12")
    private int mois;

    @Min(value = 2000, message = "L'année n'est pas valide")
    private int annee;

    @NotEmpty(message = "La facture doit contenir au moins une ligne")
    @Valid
    private List<LigneFactureDto> lignes;
}
