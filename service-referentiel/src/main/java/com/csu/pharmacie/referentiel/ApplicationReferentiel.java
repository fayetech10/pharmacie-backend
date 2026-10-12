package com.csu.pharmacie.referentiel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Référentiel des services, prestations et prix, et tarifs Hors CSU (port 8082).
 *
 * <p>Les entités viennent du domaine partagé « commun » : ce sont celles du backend
 * existant, il n'en existe pas de copie. Les dépôts communs (factures, Hors CSU,
 * compteurs) vivent dans le socle et sont explicitement inclus au scan.</p>
 */
@SpringBootApplication
@EntityScan("com.csu.pharmacie.entity")
@EnableJpaRepositories({"com.csu.pharmacie.referentiel.depot"})
public class ApplicationReferentiel {

    public static void main(String[] args) {
        SpringApplication.run(ApplicationReferentiel.class, args);
    }
}
