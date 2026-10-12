package com.csu.pharmacie.commun.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import com.csu.pharmacie.commun.securite.FiltreJwt;

import java.util.Arrays;
import java.util.List;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * Chaîne de sécurité par défaut de tous les microservices : sans état, JWT obligatoire
 * sauf sur la santé et la documentation.
 *
 * <p>Un service qui a des règles propres (service-identite et sa route de connexion
 * publique) déclare son propre {@code SecurityFilterChain} : celui-ci s'efface alors.</p>
 *
 * <p>Les chemins {@code /interne/**} ne sont <em>pas</em> ouverts : un appel entre services
 * porte le jeton de l'utilisateur d'origine et reste soumis aux mêmes contrôles. Ils sont
 * en revanche exclus du routage de la passerelle, donc injoignables depuis l'extérieur.</p>
 */
@AutoConfiguration(after = ConfigurationCommune.class)
@EnableWebSecurity
public class ConfigurationSecurite {

    /** Chemins publics communs : santé du service et documentation OpenAPI. */
    public static final String[] CHEMINS_PUBLICS = {
            "/api/health", "/actuator/health", "/actuator/health/**",
            "/api-docs/**", "/api-docs.yaml", "/v3/api-docs/**",
            "/swagger-ui/**", "/swagger-ui.html"
    };

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain chaineParDefaut(HttpSecurity http, FiltreJwt filtreJwt) throws Exception {
        return chaineDeBase(http, filtreJwt)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(Arrays.stream(CHEMINS_PUBLICS)
                                .map(chemin -> antMatcher(chemin))
                                .toArray(org.springframework.security.web.util.matcher.AntPathRequestMatcher[]::new))
                        .permitAll()
                        .anyRequest().authenticated())
                .build();
    }

    /**
     * Socle réutilisable par les services qui définissent leurs propres règles :
     * CSRF désactivé (API sans cookie de session), sessions désactivées, filtre JWT posé.
     */
    public static HttpSecurity chaineDeBase(HttpSecurity http, FiltreJwt filtreJwt) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {
                })
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(filtreJwt, UsernamePasswordAuthenticationFilter.class);
    }

    /**
     * CORS déclaré aussi au niveau des services : en production le navigateur ne parle
     * qu'à la passerelle, mais en développement on attaque souvent un service directement.
     */
    @Bean
    @ConditionalOnMissingBean(CorsFilter.class)
    public CorsFilter corsFilter(
            @org.springframework.beans.factory.annotation.Value("${cors.allowed-origins:http://localhost:4200}")
            String originesAutorisees) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowCredentials(true);
        configuration.setAllowedOriginPatterns(Arrays.stream(originesAutorisees.split(","))
                .map(String::trim)
                .filter(origine -> !origine.isEmpty())
                .toList());
        configuration.setAllowedHeaders(List.of("Origin", "Content-Type", "Accept", "Authorization"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setExposedHeaders(List.of("Content-Disposition"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsFilter(source);
    }
}
