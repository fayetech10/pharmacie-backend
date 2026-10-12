package com.csu.pharmacie.identite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Service d'identité et d'organisation sanitaire : comptes, régions, districts, structures (port 8081).
 *
 * <p>Les entités viennent du domaine partagé « commun » : ce sont celles du backend
 * existant, il n'en existe pas de copie. Les dépôts communs (factures, Hors CSU,
 * compteurs) vivent dans le socle et sont explicitement inclus au scan.</p>
 */
@SpringBootApplication
@EntityScan("com.csu.pharmacie.entity")
@EnableJpaRepositories({"com.csu.pharmacie.identite.depot"})
public class ApplicationIdentite {

    public static void main(String[] args) {
        SpringApplication.run(ApplicationIdentite.class, args);
    }
}
