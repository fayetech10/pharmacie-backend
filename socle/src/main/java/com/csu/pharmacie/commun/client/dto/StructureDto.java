package com.csu.pharmacie.commun.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.csu.pharmacie.entity.TypeStructure;

/**
 * Vue publique d'une structure sanitaire, telle que service-identite l'expose aux
 * autres services. Volontairement plus pauvre que l'entité : les services consommateurs
 * n'ont pas à connaître les champs de gestion (responsable, dates, drapeaux internes).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StructureDto {

    private String id;
    private String code;
    private String nom;
    /** Adresse de la structure (lieu des documents : « Thiès, le … »). */
    private String adresse;
    private TypeStructure typeStructure;
    /** Centre de santé / EPS de rattachement, renseigné pour les postes de santé. */
    private String structureRattachementId;
    private String structureRattachementNom;
    private String districtId;
    /** Centre de santé siège de son district (double casquette centre + district). */
    private boolean siegeDistrict;
    private String districtNom;
    private String regionId;
    private String regionNom;
    private boolean actif;
}
