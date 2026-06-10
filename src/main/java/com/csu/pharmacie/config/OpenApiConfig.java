package com.csu.pharmacie.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("CSU Pharmacie API")
                        .version("1.0")
                        .description("""
                                API de gestion et validation des factures pharmaceutiques de la \
                                Couverture Sanitaire Universelle (CSU) du Sénégal.

                                **Authentification** : appelez `POST /api/auth/login` pour obtenir un token JWT, \
                                puis cliquez sur **Authorize** (en haut à droite) et collez le token \
                                (sans le préfixe « Bearer »).""")
                        .contact(new Contact()
                                .name("Équipe technique CSU Sénégal")
                                .email("support@csu.sn")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(
                        new Components()
                                .addSecuritySchemes(securitySchemeName,
                                        new SecurityScheme()
                                                .name(securitySchemeName)
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                )
                );
    }
}
