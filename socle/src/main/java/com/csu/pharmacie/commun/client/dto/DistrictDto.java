package com.csu.pharmacie.commun.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Vue publique d'un district sanitaire (échelon entre la structure et la région). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DistrictDto {

    private String id;
    private String code;
    private String nom;
    private String regionId;
    private String regionNom;
    private boolean actif;
}
