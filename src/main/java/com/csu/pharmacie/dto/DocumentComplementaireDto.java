package com.csu.pharmacie.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DocumentComplementaireDto {
    @NotBlank(message = "Le titre du document est obligatoire")
    private String titre;

    @Size(max = 2097152, message = "Image trop volumineuse")
    private String image;

    private String lettreGarantieNumero;
}
