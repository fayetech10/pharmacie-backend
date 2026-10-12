package com.csu.pharmacie.commun.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prestation tarifée du référentiel (§5 : Service → Prestation → Prix).
 *
 * <p>{@code depositaire} signale une prestation servie par la pharmacie : sa facturation
 * doit déclencher une sortie de stock (§14).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrestationDto {

    private String id;
    private String code;
    private String designation;
    private String serviceId;
    private String serviceNom;
    private double prix;
    private boolean depositaire;
    /** Article de stock associé quand la prestation est de type dépositaire. */
    private String medicamentId;
    private boolean actif;
}
