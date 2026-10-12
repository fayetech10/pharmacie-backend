package com.csu.pharmacie.officine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import com.csu.pharmacie.commun.config.ConfigurationSecurite;
import com.csu.pharmacie.commun.securite.FiltreJwt;

import java.util.Arrays;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * Accès au service pharmacie.
 *
 * <p>{@code /interne/stock/**} est ouvert aux points de soins : c'est eux qui déclenchent
 * les sorties automatiques lorsqu'ils facturent une prestation dépositaire. Le jeton
 * relayé porte leur identité, et le service ne touche qu'au stock de leur propre structure.</p>
 */
@Configuration
public class SecuritePharmacie {

    @Bean
    public SecurityFilterChain chaine(HttpSecurity http, FiltreJwt filtreJwt) throws Exception {
        return ConfigurationSecurite.chaineDeBase(http, filtreJwt)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(Arrays.stream(ConfigurationSecurite.CHEMINS_PUBLICS)
                                .map(chemin -> antMatcher(chemin))
                                .toArray(AntPathRequestMatcher[]::new))
                        .permitAll()

                        .requestMatchers(antMatcher("/api/pharmacie/**"))
                        .hasAnyRole("PHARMACIEN", "POSTE_SANTE", "CENTRE_SANTE", "STRUCTURE_SANITAIRE",
                                "SERVICE_REGIONAL", "SERVICE_CENTRAL", "ADMIN")

                        .requestMatchers(antMatcher("/interne/stock/**"))
                        .hasAnyRole("POSTE_SANTE", "CENTRE_SANTE", "STRUCTURE_SANITAIRE",
                                "PHARMACIEN", "ADMIN")

                        .anyRequest().authenticated())
                .build();
    }
}
