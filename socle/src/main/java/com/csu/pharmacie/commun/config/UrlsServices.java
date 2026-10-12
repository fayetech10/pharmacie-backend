package com.csu.pharmacie.commun.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Adresses des microservices (préfixe {@code services}).
 *
 * <p>Les valeurs par défaut correspondent au lancement local « tout sur la machine »
 * décrit dans le README ; en déploiement elles sont surchargées par variables
 * d'environnement ({@code SERVICES_IDENTITE_URL}, etc.).</p>
 *
 * <p>Pas d'annuaire de services (Eureka/Consul) : à cinq services, une configuration
 * explicite reste plus simple à diagnostiquer qu'un registre à opérer.</p>
 */
@Data
@ConfigurationProperties(prefix = "services")
public class UrlsServices {

    private String identiteUrl = "http://localhost:8081";
    private String referentielUrl = "http://localhost:8082";
    private String pharmacieUrl = "http://localhost:8083";
    private String posteSanteUrl = "http://localhost:8084";
    private String centreSanteUrl = "http://localhost:8085";
}
