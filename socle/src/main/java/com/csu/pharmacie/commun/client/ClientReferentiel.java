package com.csu.pharmacie.commun.client;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Accès au référentiel des prestations (service-referentiel).
 *
 * <p>Sert à une chose essentielle : la tarification. Les prix envoyés par le navigateur
 * ne sont jamais crus — la facture est retarifée côté serveur à partir de ce référentiel
 * (§4 : « calcul automatique des prix unitaires à partir du référentiel des prestations »).</p>
 */
@RequiredArgsConstructor
public class ClientReferentiel {

    private static final String NOM = "référentiel";
    private static final ParameterizedTypeReference<List<PrestationTarifee>> LISTE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;

    /** Reprise du DTO public, restreinte à ce dont la tarification a besoin. */
    public record PrestationTarifee(String id, String code, String designation, String serviceNom,
                                    double prix, boolean depositaire, String medicamentId, boolean actif) {
    }

    /**
     * Tarifs de plusieurs prestations en un seul appel, indexés par identifiant.
     * Le lot évite N appels HTTP quand une facture porte des dizaines de lignes.
     */
    public Map<String, PrestationTarifee> tarifs(Collection<String> prestationIds) {
        List<String> ids = prestationIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<PrestationTarifee> prestations = AppelInterServices.obligatoire(NOM, () -> client.post()
                .uri("/interne/prestations/tarifs")
                .body(ids)
                .retrieve()
                .body(LISTE));
        if (prestations == null) {
            return Map.of();
        }
        return prestations.stream().collect(Collectors.toMap(PrestationTarifee::id, Function.identity(),
                (a, b) -> a));
    }
}
