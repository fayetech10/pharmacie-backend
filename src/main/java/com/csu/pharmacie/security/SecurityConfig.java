package com.csu.pharmacie.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final CustomUserDetailsService userDetailsService;

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      http
              .cors(cors -> cors.configure(http))
              .csrf(AbstractHttpConfigurer::disable)
              // SAMEORIGIN (et non disable) : suffit à la console H2 locale dans une frame,
              // tout en protégeant l'application du clickjacking depuis un site tiers.
              .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
              .authorizeHttpRequests(auth -> auth
                      // Console H2 en local
                      .requestMatchers(antMatcher("/h2-console/**")).permitAll()
                      // API 100% REST : on matche les chemins directement (AntPathRequestMatcher)
                      // au lieu des MvcRequestMatcher, ce qui évite le lookup HandlerMappingIntrospector
                      // de Spring MVC à chaque requête (et l'avertissement « Cache miss for REQUEST dispatch »).
                      // Seule la connexion est publique (PAS l'inscription : voir ci-dessous)
                      .requestMatchers(antMatcher("/api/auth/login")).permitAll()
                      // Endpoint de santé public (cible du keep-alive anti cold-start Render)
                      .requestMatchers(antMatcher(HttpMethod.GET, "/api/health")).permitAll()
                      .requestMatchers(
                              // Documentation OpenAPI / Swagger (le chemin du spec est /api-docs, cf. application.yml)
                              antMatcher("/api-docs/**"), antMatcher("/api-docs.yaml"),
                              antMatcher("/v3/api-docs/**"),
                              antMatcher("/swagger-ui/**"), antMatcher("/swagger-ui.html")
                      ).permitAll()
                      // Création de comptes : réservée à l'Administrateur (empêche l'escalade de privilèges)
                      .requestMatchers(antMatcher(HttpMethod.POST, "/api/auth/register")).hasRole("ADMIN")
                      // Liste des agents BCSU (formulaire structures sanitaires) : ouverte au SR.
                      // DOIT précéder la règle générale /api/users/** (premier matcher gagnant).
                      .requestMatchers(antMatcher(HttpMethod.GET, "/api/users/bcsu")).hasAnyRole("SERVICE_REGIONAL", "ADMIN")
                      // Paramétrage cachet/signature : lecture par tout utilisateur authentifié
                      // (affichage sur la LG), mise à jour par l'agent lui-même (BCSU).
                      // DOIT précéder la règle générale /api/users/**.
                      .requestMatchers(antMatcher(HttpMethod.GET, "/api/users/*/parametrage")).authenticated()
                      .requestMatchers(antMatcher(HttpMethod.PUT, "/api/users/parametrage")).hasAnyRole("BCSU", "ADMIN")
                      .requestMatchers(antMatcher("/api/users/**")).hasAnyRole("ADMIN", "SERVICE_REGIONAL")
                      // Gestion des pharmacies : réservée au Service Régional et à l'Admin
                      // (le Service Central peut consulter via GET mais pas créer/modifier/supprimer)
                      .requestMatchers(antMatcher(HttpMethod.POST, "/api/pharmacies/**")).hasAnyRole("SERVICE_REGIONAL", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.PUT, "/api/pharmacies/**")).hasAnyRole("SERVICE_REGIONAL", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.DELETE, "/api/pharmacies/**")).hasAnyRole("SERVICE_REGIONAL", "ADMIN")
                      // Espace BCSU : patients, lettres de garantie, bons de commande.
                      // Les GET restent couverts par anyRequest().authenticated() ; le filtrage fin
                      // (dossiers propres à l'agent, recherche ouverte aux pharmacies) est fait en service.
                      .requestMatchers(antMatcher(HttpMethod.POST, "/api/patients/**")).hasAnyRole("BCSU", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.PUT, "/api/patients/**")).hasAnyRole("BCSU", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.DELETE, "/api/patients/**")).hasAnyRole("BCSU", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.POST, "/api/lettres-garantie/**")).hasAnyRole("BCSU", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.PUT, "/api/lettres-garantie/**")).hasAnyRole("BCSU", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.DELETE, "/api/lettres-garantie/**")).hasAnyRole("BCSU", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.POST, "/api/bons-commande/**")).hasAnyRole("BCSU", "ADMIN")
                      // Constats d'activité : saisie et suppression réservées aux agents BCSU (contrôle fin en service).
                      .requestMatchers(antMatcher(HttpMethod.POST, "/api/constats/**")).hasAnyRole("BCSU", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.DELETE, "/api/constats/**")).hasAnyRole("BCSU", "ADMIN")
                      // Structures sanitaires : gestion réservée au Service Régional et à l'Admin (comme les pharmacies).
                      .requestMatchers(antMatcher(HttpMethod.POST, "/api/structures-sanitaires/**")).hasAnyRole("SERVICE_REGIONAL", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.PUT, "/api/structures-sanitaires/**")).hasAnyRole("SERVICE_REGIONAL", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.DELETE, "/api/structures-sanitaires/**")).hasAnyRole("SERVICE_REGIONAL", "ADMIN")
                      // Feuilles de soins : délivrance (BCSU), prise en charge (structure), annulation (BCSU) ; contrôle fin en service.
                      .requestMatchers(antMatcher(HttpMethod.POST, "/api/feuilles-soins/**")).hasAnyRole("BCSU", "STRUCTURE_SANITAIRE", "ADMIN")
                      // Facturation des structures : création/envoi (structure) + validation/rejet (SR) ; contrôle fin en service.
                      .requestMatchers(antMatcher(HttpMethod.POST, "/api/factures-structure/**")).hasAnyRole("STRUCTURE_SANITAIRE", "SERVICE_REGIONAL", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.PUT, "/api/factures-structure/**")).hasAnyRole("STRUCTURE_SANITAIRE", "ADMIN")
                      // Purge des brouillons depuis le paramétrage des structures : ouverte au SR
                      // (déclarée avant la règle générale, plus spécifique d'abord).
                      .requestMatchers(antMatcher(HttpMethod.DELETE, "/api/factures-structure/brouillons")).hasAnyRole("SERVICE_REGIONAL", "ADMIN")
                      .requestMatchers(antMatcher(HttpMethod.DELETE, "/api/factures-structure/**")).hasAnyRole("STRUCTURE_SANITAIRE", "ADMIN")
                      .anyRequest().authenticated()
              )
              .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
              .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

      return http.build();
  }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        // setAllowedOriginPatterns (et non setAllowedOrigins) : autorise les motifs avec wildcard
        // (ex: https://*.vercel.app pour les déploiements de prévisualisation) tout en gardant
        // la compatibilité avec des origines exactes.
        config.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .toList());
        config.setAllowedHeaders(Arrays.asList("Origin", "Content-Type", "Accept", "Authorization"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
