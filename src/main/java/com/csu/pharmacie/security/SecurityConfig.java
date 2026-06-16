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
                .authorizeHttpRequests(auth -> auth
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
                        .requestMatchers(antMatcher("/api/users/**")).hasRole("ADMIN")
                        // Gestion des pharmacies : réservée au Service Régional et à l'Admin
                        // (le Service Central peut consulter via GET mais pas créer/modifier/supprimer)
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/pharmacies/**")).hasAnyRole("SERVICE_REGIONAL", "ADMIN")
                        .requestMatchers(antMatcher(HttpMethod.PUT, "/api/pharmacies/**")).hasAnyRole("SERVICE_REGIONAL", "ADMIN")
                        .requestMatchers(antMatcher(HttpMethod.DELETE, "/api/pharmacies/**")).hasAnyRole("SERVICE_REGIONAL", "ADMIN")
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
