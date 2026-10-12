package com.csu.pharmacie.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Fait utiliser à Hibernate l'ObjectMapper Spring (qui embarque le module JSR-310) pour
 * (dé)sérialiser les colonnes JSONB. Sans cela, Hibernate construit son propre ObjectMapper
 * qui ne gère pas LocalDateTime → l'historique des factures (HistoriqueAction.date) échouerait.
 */
@Component
@RequiredArgsConstructor
public class HibernateJsonConfig implements HibernatePropertiesCustomizer {

    private final ObjectMapper objectMapper;

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.JSON_FORMAT_MAPPER,
                new JacksonJsonFormatMapper(objectMapper));
    }
}
