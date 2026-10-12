package com.csu.pharmacie.centre.config;

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
 * Accès au service du centre de santé.
 *
 * <p>La consolidation est ouverte plus largement que la facturation : un district, une
 * région ou le niveau central doivent pouvoir consolider sans être une structure. Le
 * périmètre réellement consultable est ensuite restreint en service, à partir du
 * rattachement porté par le jeton.</p>
 */
@Configuration
public class SecuriteCentre {

    @Bean
    public SecurityFilterChain chaine(HttpSecurity http, FiltreJwt filtreJwt) throws Exception {
        return ConfigurationSecurite.chaineDeBase(http, filtreJwt)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(Arrays.stream(ConfigurationSecurite.CHEMINS_PUBLICS)
                                .map(chemin -> antMatcher(chemin))
                                .toArray(AntPathRequestMatcher[]::new))
                        .permitAll()

                        // Étape service régional : validation / rejet d'une facture transmise.
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/factures-structure/*/valider"),
                                antMatcher(HttpMethod.POST, "/api/factures-structure/*/rejeter"))
                        .hasAnyRole("SERVICE_REGIONAL", "SERVICE_CENTRAL", "ADMIN")

                        // Reprise des factures de l'ancienne application (09/10/2026) : le
                        // Service Régional, pour les structures de sa région (contrôlé en service).
                        // AVANT la règle générale d'écriture, qui n'admet que centre et structure.
                        .requestMatchers(antMatcher(HttpMethod.GET, "/api/factures-structure/import/modele"),
                                antMatcher(HttpMethod.POST, "/api/factures-structure/import"))
                        .hasAnyRole("SERVICE_REGIONAL", "ADMIN")

                        // Contrôle des factures des POSTES DE SANTÉ : réservé au district
                        // (08/10/2026) — il remplace le centre de rattachement. Le service vérifie
                        // que le poste est bien dans le district de l'appelant.
                        .requestMatchers(antMatcher(HttpMethod.GET, "/api/factures-structure/postes-rattaches/**"),
                                antMatcher(HttpMethod.POST, "/api/factures-structure/postes-rattaches/**"),
                                antMatcher(HttpMethod.POST, "/api/factures-structure/*/valider-poste"),
                                antMatcher(HttpMethod.POST, "/api/factures-structure/*/rejeter-poste"))
                        .hasRole("DISTRICT")

                        // Pointage « patient vérifié » pendant le contrôle : district (factures
                        // de postes) ou service régional. Le service vérifie l'étape et le
                        // périmètre (district du poste, région de la facture).
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/factures-structure/*/verification"),
                                antMatcher(HttpMethod.POST, "/api/factures-structure/*/rejet-lignes"))
                        .hasAnyRole("DISTRICT", "SERVICE_REGIONAL", "SERVICE_CENTRAL", "ADMIN")

                        // Lecture élargie aux superviseurs : ils n'ont pas de structure mais
                        // répondent d'un échelon, et ServiceFacturation.facturesDuPerimetre()
                        // sait déjà leur servir région / district / national. Sans cette règle,
                        // la liste tombait en 403 AVANT d'atteindre ce code : le correctif de
                        // périmètre avait été fait en service, pas ici, et l'écran régional
                        // restait vide. En GET seulement — un superviseur ne saisit pas de
                        // facture (cf. Role.estSuperviseur), la saisie reste à la règle suivante.
                        .requestMatchers(antMatcher(HttpMethod.GET, "/api/factures-structure/**"))
                        .hasAnyRole("CENTRE_SANTE", "STRUCTURE_SANITAIRE", "DISTRICT",
                                "SERVICE_REGIONAL", "SERVICE_CENTRAL", "ADMIN")

                        .requestMatchers(antMatcher("/api/factures-structure/**"),
                                antMatcher("/api/hors-csu/**"))
                        .hasAnyRole("CENTRE_SANTE", "STRUCTURE_SANITAIRE", "ADMIN")

                        // Consolidation district : réservée STRICTEMENT au rôle District (08/10/2026).
                        .requestMatchers(antMatcher("/api/consolidation/**"))
                        .hasRole("DISTRICT")

                        .requestMatchers(antMatcher("/interne/**"))
                        .hasAnyRole("DISTRICT", "SERVICE_REGIONAL", "SERVICE_CENTRAL", "ADMIN")

                        .anyRequest().authenticated())
                .build();
    }
}
