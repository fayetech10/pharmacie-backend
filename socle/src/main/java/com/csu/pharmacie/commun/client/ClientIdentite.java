package com.csu.pharmacie.commun.client;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import com.csu.pharmacie.commun.client.dto.DistrictDto;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.web.ExceptionMetier;

import java.util.List;

/**
 * Accès en lecture au référentiel des structures et districts détenu par service-identite.
 *
 * <p>C'est la seule façon pour un service métier de connaître l'organisation sanitaire :
 * personne d'autre ne stocke la table des structures. Les identifiants et libellés
 * effectivement utilisés dans une facture y sont recopiés au moment de l'émission, de
 * sorte qu'une facture déjà émise reste lisible même si la structure est renommée ou si
 * service-identite est arrêté.</p>
 */
@RequiredArgsConstructor
public class ClientIdentite {

    private static final String NOM = "identité";
    private static final ParameterizedTypeReference<List<StructureDto>> LISTE_STRUCTURES =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;

    /** Structure par identifiant — 404 si elle n'existe pas. */
    public StructureDto structure(String id) {
        if (id == null || id.isBlank()) {
            throw ExceptionMetier.invalide("Identifiant de structure manquant");
        }
        StructureDto structure = AppelInterServices.obligatoire(NOM, () -> client.get()
                .uri("/interne/structures/{id}", id)
                .retrieve()
                .body(StructureDto.class));
        if (structure == null) {
            throw ExceptionMetier.introuvable("Structure sanitaire introuvable : " + id);
        }
        return structure;
    }

    /** Postes de santé polarisés par un centre de santé / EPS. */
    public List<StructureDto> postesRattaches(String centreId) {
        return AppelInterServices.obligatoire(NOM, () -> client.get()
                .uri(uri -> uri.path("/interne/structures").queryParam("rattachementId", centreId).build())
                .retrieve()
                .body(LISTE_STRUCTURES));
    }

    /** Toutes les structures d'un district (postes, centres et EPS confondus). */
    public List<StructureDto> structuresDuDistrict(String districtId) {
        return AppelInterServices.obligatoire(NOM, () -> client.get()
                .uri(uri -> uri.path("/interne/structures").queryParam("districtId", districtId).build())
                .retrieve()
                .body(LISTE_STRUCTURES));
    }

    /** Toutes les structures d'une région. */
    public List<StructureDto> structuresDeLaRegion(String regionId) {
        return AppelInterServices.obligatoire(NOM, () -> client.get()
                .uri(uri -> uri.path("/interne/structures").queryParam("regionId", regionId).build())
                .retrieve()
                .body(LISTE_STRUCTURES));
    }

    public DistrictDto district(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return AppelInterServices.optionnel(NOM, () -> client.get()
                .uri("/interne/districts/{id}", id)
                .retrieve()
                .body(DistrictDto.class), null);
    }
}
