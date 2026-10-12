package com.csu.pharmacie.commun.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Demande de sortie de stock déclenchée par la facturation d'une prestation dépositaire
 * (§14 : « déclencher automatiquement une sortie de stock lors de la facturation d'un
 * médicament »).
 *
 * <p>{@code referenceFacture} + {@code referenceLigne} forment la clé d'idempotence :
 * rejouer la même demande (nouvel enregistrement de la facture, reprise après incident)
 * ne décrémente jamais le stock deux fois.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SortieStockDto {

    private String structureId;
    private String medicamentId;
    private String prestationId;
    private String designation;
    private int quantite;
    private LocalDate date;
    private String referenceFacture;
    private String referenceLigne;
    private String motif;
}
