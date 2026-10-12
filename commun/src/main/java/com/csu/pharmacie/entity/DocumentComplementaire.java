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
    /**
     * Patient auquel appartient le document quand il n'y a pas de lettre de garantie
     * (saisie d'un poste de santé) : la facture mensuelle regroupe plusieurs patients,
     * c'est ce matricule qui range la pièce dans le bon dossier. Stocké dans le jsonb
     * de la facture : aucune migration.
     */
    private String patientMatricule;
}
