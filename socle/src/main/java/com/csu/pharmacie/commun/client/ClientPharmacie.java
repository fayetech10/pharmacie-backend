package com.csu.pharmacie.commun.client;

import lombok.RequiredArgsConstructor;
import org.springframework.web.client.RestClient;
import com.csu.pharmacie.commun.client.dto.SortieStockDto;

/**
 * Déclenchement des sorties de stock côté pharmacie (§14).
 *
 * <p>L'appel est délibérément <em>facultatif</em> : si le service pharmacie est arrêté,
 * la facture est quand même enregistrée. Une facture perdue coûte plus cher qu'un
 * mouvement de stock à rejouer — et l'idempotence de {@link SortieStockDto} permet
 * précisément de le rejouer sans risque.</p>
 */
@RequiredArgsConstructor
public class ClientPharmacie {

    private static final String NOM = "pharmacie";

    private final RestClient client;

    /**
     * Enregistre une sortie de stock et renvoie l'identifiant du mouvement,
     * ou {@code null} si la pharmacie n'a pas pu être jointe.
     */
    public String sortieStock(SortieStockDto demande) {
        return AppelInterServices.optionnel(NOM, () -> {
            Reponse reponse = client.post()
                    .uri("/interne/stock/sorties")
                    .body(demande)
                    .retrieve()
                    .body(Reponse.class);
            return reponse == null ? null : reponse.mouvementId();
        }, null);
    }

    public record Reponse(String mouvementId, Integer stockRestant, String message) {
    }
}
