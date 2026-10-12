package com.csu.pharmacie.identite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import com.csu.pharmacie.commun.config.ConfigurationSecurite;
import com.csu.pharmacie.commun.securite.FiltreJwt;

import java.util.Arrays;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * Règles d'accès du service d'identité.
 *
 * <p>La connexion est la seule route réellement publique. Tout le reste — y compris la
 * création de comptes — exige un jeton : ouvrir {@code /register} laisserait n'importe
 * qui se créer un compte administrateur.</p>
 */
@Configuration
public class SecuriteIdentite {

    @Bean
    public SecurityFilterChain chaine(HttpSecurity http, FiltreJwt filtreJwt) throws Exception {
        return ConfigurationSecurite.chaineDeBase(http, filtreJwt)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(Arrays.stream(ConfigurationSecurite.CHEMINS_PUBLICS)
                                .map(chemin -> antMatcher(chemin))
                                .toArray(org.springframework.security.web.util.matcher.AntPathRequestMatcher[]::new))
                        .permitAll()
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/auth/login")).permitAll()

                        // Administration des comptes : national ou régional uniquement.
                        .requestMatchers(antMatcher("/api/utilisateurs/**"))
                        .hasAnyRole("ADMIN", "SERVICE_REGIONAL", "SERVICE_CENTRAL")

                        // Districts et structures : lecture ouverte à tout compte authentifié
                        // (les formulaires de facturation ont besoin des libellés),
                        // écriture réservée à l'administration.
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/districts/**"))
                        .hasAnyRole("ADMIN", "SERVICE_CENTRAL", "SERVICE_REGIONAL")
                        .requestMatchers(antMatcher(HttpMethod.PUT, "/api/districts/**"))
                        .hasAnyRole("ADMIN", "SERVICE_CENTRAL", "SERVICE_REGIONAL")
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/structures-sanitaires/**"))
                        .hasAnyRole("ADMIN", "SERVICE_CENTRAL", "SERVICE_REGIONAL")
                        .requestMatchers(antMatcher(HttpMethod.PUT, "/api/structures-sanitaires/**"))
                        .hasAnyRole("ADMIN", "SERVICE_CENTRAL", "SERVICE_REGIONAL")
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/regions/**")).hasRole("ADMIN")
                        .requestMatchers(antMatcher(HttpMethod.PUT, "/api/regions/**")).hasRole("ADMIN")

                        .anyRequest().authenticated())
                .build();
    }
}
