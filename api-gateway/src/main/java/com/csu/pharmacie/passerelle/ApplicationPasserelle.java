package com.csu.pharmacie.passerelle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Passerelle d'entrée de la plateforme (port 8080).
 *
 * <p>Elle occupe le port que le monolithe utilisait : le dashboard Angular continue
 * d'appeler {@code http://localhost:8080/api/...} sans savoir qu'il y a désormais cinq
 * services derrière.</p>
 *
 * <p>Elle ne valide pas les jetons — chaque service le fait localement, et dupliquer la
 * vérification ici n'ajouterait qu'un endroit de plus où la logique peut diverger. Son
 * rôle est le routage, le CORS et le masquage 0des routes internes.</p>
 */
@SpringBootApplication
public class ApplicationPasserelle {

    public static void main(String[] args) {
        SpringApplication.run(ApplicationPasserelle.class, args);
    }
}
