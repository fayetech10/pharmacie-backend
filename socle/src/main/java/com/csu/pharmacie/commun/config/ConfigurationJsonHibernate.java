package com.csu.pharmacie.commun.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;

import java.util.Map;

/**
 * Branche l'{@code ObjectMapper} de Spring (qui embarque JSR-310) sur Hibernate pour les
 * colonnes JSON.
 *
 * <p>Sans cela Hibernate se fabrique son propre mapper, incapable de sérialiser un
 * {@code LocalDate} : les lignes de facturation et l'historique des dossiers — stockés en
 * {@code jsonb} — échoueraient à l'écriture.</p>
 */
@RequiredArgsConstructor
public class ConfigurationJsonHibernate implements HibernatePropertiesCustomizer {

    private final ObjectMapper objectMapper;

    @Override
    public void customize(Map<String, Object> proprietes) {
        proprietes.put(AvailableSettings.JSON_FORMAT_MAPPER, new JacksonJsonFormatMapper(objectMapper));
    }
}
