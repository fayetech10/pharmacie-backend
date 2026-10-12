package com.csu.pharmacie.dto;

import com.csu.pharmacie.entity.Regime;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConstatRequest {

    @NotNull(message = "Le régime concerné est obligatoire")
    private Regime regime;

    @NotBlank(message = "La description du constat est obligatoire")
    private String description;
}
