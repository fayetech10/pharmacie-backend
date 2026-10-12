package com.csu.pharmacie.centre;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Service du centre de santé / EPS : facturation, validation des postes polarisés, consolidation district (port 8085).
 *
 * <p>Les entités viennent du domaine partagé « commun » : ce sont celles du backend
 * existant, il n'en existe pas de copie. Les dépôts communs (factures, Hors CSU,
 * compteurs) vivent dans le socle et sont explicitement inclus au scan.</p>
 */
@SpringBootApplication
@EntityScan("com.csu.pharmacie.entity")
@EnableJpaRepositories({"com.csu.pharmacie.centre.depot", "com.csu.pharmacie.commun.facturation", "com.csu.pharmacie.commun.horscsu"})
public class ApplicationCentreSante {

    public static void main(String[] args) {
        SpringApplication.run(ApplicationCentreSante.class, args);
    }
}
