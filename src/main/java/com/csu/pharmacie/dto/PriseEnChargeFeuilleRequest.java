package com.csu.pharmacie.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Remplissage du tableau « PRISE EN CHARGE » de la feuille de soins par la
 * structure sanitaire (le patient a déposé la feuille délivrée par l'agent bureau).
 */
@Data
public class PriseEnChargeFeuilleRequest {

    /** Complément éventuel du diagnostic posé par la structure. */
    private String diagnostic;

    @NotEmpty(message = "Au moins une prestation est requise")
    @Valid
    private List<PrestationSoinsDto> prestations;

    @Data
    public static class PrestationSoinsDto {
        @NotNull(message = "La date de la prestation est obligatoire")
        private LocalDate date;
        @NotBlank(message = "La désignation de la prestation est obligatoire")
        private String designation;
        @PositiveOrZero(message = "Le montant doit être positif")
        private double montant;
    }
}
