package com.csu.pharmacie.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Document complémentaire d'une facture (image base64 + titre personnalisé). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentComplementaire {
    private String titre;
    private String image; // data URL base64
    private String lettreGarantieNumero;
}
