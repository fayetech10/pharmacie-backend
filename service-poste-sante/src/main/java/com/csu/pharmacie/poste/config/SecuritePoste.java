package com.csu.pharmacie.poste.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import com.csu.pharmacie.commun.config.ConfigurationSecurite;
import com.csu.pharmacie.commun.securite.FiltreJwt;

import java.util.Arrays;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * Accès au service du poste de santé.
 *
 * <p>Deux publics bien distincts :</p>
 * <ul>
 *   <li>{@code /api/poste/**} — l'agent du poste lui-même (et l'administration en appui) ;</li>
 *   <li>{@code /interne/**} — le centre de rattachement qui valide, et les échelons de
 *       supervision qui consolident. Ces routes ne sont pas exposées par la passerelle,
 *       mais elles restent authentifiées : c'est le rôle porté par le jeton relayé qui
 *       décide, et le rattachement réel est revérifié en service.</li>
 * </ul>
 */
@Configuration
public class SecuritePoste {

    @Bean
    public SecurityFilterChain chaine(HttpSecurity http, FiltreJwt filtreJwt) throws Exception {
        return ConfigurationSecurite.chaineDeBase(http, filtreJwt)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(Arrays.stream(ConfigurationSecurite.CHEMINS_PUBLICS)
                                .map(chemin -> antMatcher(chemin))
                                .toArray(AntPathRequestMatcher[]::new))
                        .permitAll()

                        .requestMatchers(antMatcher("/api/factures-structure/**"),
                                antMatcher("/api/beneficiaires/**"),
                                antMatcher("/api/hors-csu/**"))
                        .hasAnyRole("POSTE_SANTE", "STRUCTURE_SANITAIRE", "ADMIN")

                        // Vérification des cartes CSU : Espace PS (poste et centre de santé) et
                        // agent BCSU (lettre de garantie).
                        .requestMatchers(antMatcher(HttpMethod.GET, "/api/carte-csu"))
                        .hasAnyRole("POSTE_SANTE", "CENTRE_SANTE", "STRUCTURE_SANITAIRE", "BCSU", "ADMIN")

                        // Étape service régional déléguée par service-centre-sante.
                        .requestMatchers(antMatcher(HttpMethod.POST, "/interne/factures/*/valider-sr"),
                                antMatcher(HttpMethod.POST, "/interne/factures/*/rejeter-sr"))
                        .hasAnyRole("SERVICE_REGIONAL", "SERVICE_CENTRAL", "ADMIN")

                        .requestMatchers(antMatcher("/interne/**"))
                        .hasAnyRole("CENTRE_SANTE", "STRUCTURE_SANITAIRE", "DISTRICT",
                                "SERVICE_REGIONAL", "SERVICE_CENTRAL", "ADMIN")

                        .anyRequest().authenticated())
                .build();
    }
}
