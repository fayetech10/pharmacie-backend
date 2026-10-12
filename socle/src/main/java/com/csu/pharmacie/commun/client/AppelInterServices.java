package com.csu.pharmacie.commun.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import com.csu.pharmacie.commun.web.ExceptionMetier;

import java.util.function.Supplier;

/**
 * Fabrique de clients HTTP entre services, avec deux garanties utiles au découpage.
 *
 * <p><b>Propagation de l'identité</b> : l'en-tête {@code Authorization} de la requête en
 * cours est réémis vers le service appelé. L'appel conserve donc l'identité de
 * l'utilisateur d'origine — pas de compte technique tout-puissant qui contournerait les
 * contrôles d'accès du service amont.</p>
 *
 * <p><b>Panne isolée</b> : un service amont injoignable devient une 503 lisible
 * (« Le service X est momentanément indisponible ») au lieu d'une 500 opaque. Les appels
 * facultatifs passent par {@link #optionnel} qui renvoie une valeur de repli plutôt que
 * de faire échouer toute l'opération.</p>
 */
public final class AppelInterServices {

    private static final Logger log = LoggerFactory.getLogger(AppelInterServices.class);

    private AppelInterServices() {
    }

    /** Construit un client pointant sur {@code urlBase}, qui rejoue le jeton de l'appelant. */
    public static RestClient client(RestClient.Builder builder, String urlBase) {
        return builder
                .baseUrl(urlBase)
                .requestInterceptor((requete, corps, execution) -> {
                    String jeton = JetonCourant.entete();
                    if (jeton != null && requete.getHeaders().getFirst("Authorization") == null) {
                        requete.getHeaders().set("Authorization", jeton);
                    }
                    return execution.execute(requete, corps);
                })
                .build();
    }

    /** Exécute un appel obligatoire : l'indisponibilité du service amont est remontée en 503. */
    public static <T> T obligatoire(String nomService, Supplier<T> appel) {
        try {
            return appel.get();
        } catch (ExceptionMetier e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Appel à {} en échec", nomService, e);
            throw ExceptionMetier.serviceIndisponible(
                    "Le service « " + nomService + " » est momentanément indisponible. Réessayez dans un instant.");
        }
    }

    /**
     * Exécute un appel facultatif : en cas d'échec on trace et on renvoie {@code repli}.
     * Sert pour tout ce qui enrichit l'affichage sans conditionner l'opération (libellés,
     * compteurs), afin qu'un service secondaire arrêté ne bloque pas la facturation.
     */
    public static <T> T optionnel(String nomService, Supplier<T> appel, T repli) {
        try {
            return appel.get();
        } catch (RuntimeException e) {
            log.warn("Appel facultatif à {} en échec ({}) — valeur de repli utilisée.",
                    nomService, e.getMessage());
            return repli;
        }
    }
}
