package com.csu.pharmacie.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class ValidationRequest {
    @NotBlank(message = "Le commentaire est obligatoire")
    private String commentaire;
}
