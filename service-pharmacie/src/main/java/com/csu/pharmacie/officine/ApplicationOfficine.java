package com.csu.pharmacie.officine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Service pharmacie (port 8083) : officines, stock des articles dépositaires et caisse.
 *
 * <p>Nommée {@code ApplicationOfficine} et non « ApplicationPharmacie » : le cœur porte
 * déjà {@code PharmacieApplication}, et deux classes de démarrage aux noms quasi
 * identiques sont indiscernables dans la liste des configurations de lancement.</p>
 *
 * <p>Les entités viennent du domaine partagé « commun » : ce sont celles du backend
 * existant, il n'en existe pas de copie. Les dépôts communs (factures, Hors CSU,
 * compteurs) vivent dans le socle et sont explicitement inclus au scan.</p>
 */
@SpringBootApplication
@EntityScan("com.csu.pharmacie.entity")
@EnableJpaRepositories({"com.csu.pharmacie.officine.depot", "com.csu.pharmacie.commun.facturation", "com.csu.pharmacie.commun.horscsu"})
public class ApplicationOfficine {

    public static void main(String[] args) {
        SpringApplication.run(ApplicationOfficine.class, args);
    }
}
