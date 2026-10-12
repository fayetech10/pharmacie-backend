package com.csu.pharmacie.referentiel.config;

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
 * Le référentiel se lit partout et ne s'écrit qu'en haut.
 *
 * <p>La nomenclature CSU et ses prix sont administrés par le niveau central : laisser un
 * point de soins modifier un tarif reviendrait à lui laisser fixer ce que la SEN-CSU lui
 * remboursera. Seuls les tarifs <em>Hors CSU</em>, qui relèvent de la structure, sont
 * ouverts en écriture aux centres et aux postes.</p>
 */
@Configuration
public class SecuriteReferentiel {

    @Bean
    public SecurityFilterChain chaine(HttpSecurity http, FiltreJwt filtreJwt) throws Exception {
        return ConfigurationSecurite.chaineDeBase(http, filtreJwt)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(Arrays.stream(ConfigurationSecurite.CHEMINS_PUBLICS)
                                .map(chemin -> antMatcher(chemin))
                                .toArray(AntPathRequestMatcher[]::new))
                        .permitAll()

                        // Tarifs Hors CSU : administrés par la structure elle-même.
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/hors-csu/**"))
                        .hasAnyRole("ADMIN", "SERVICE_CENTRAL", "SERVICE_REGIONAL",
                                "CENTRE_SANTE", "POSTE_SANTE", "STRUCTURE_SANITAIRE")
                        .requestMatchers(antMatcher(HttpMethod.PUT, "/api/hors-csu/**"))
                        .hasAnyRole("ADMIN", "SERVICE_CENTRAL", "SERVICE_REGIONAL",
                                "CENTRE_SANTE", "POSTE_SANTE", "STRUCTURE_SANITAIRE")

                        // Nomenclature CSU : lecture pour tous, écriture pour le niveau central.
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/referentiel/**"))
                        .hasAnyRole("ADMIN", "SERVICE_CENTRAL")
                        .requestMatchers(antMatcher(HttpMethod.PUT, "/api/referentiel/**"))
                        .hasAnyRole("ADMIN", "SERVICE_CENTRAL")
                        .requestMatchers(antMatcher(HttpMethod.DELETE, "/api/referentiel/**"))
                        .hasAnyRole("ADMIN", "SERVICE_CENTRAL")

                        .anyRequest().authenticated())
                .build();
    }
}
