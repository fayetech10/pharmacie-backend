package com.csu.pharmacie.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class FixConstraintConfig {
    private static final Logger log = LoggerFactory.getLogger(FixConstraintConfig.class);

    @Bean
    public CommandLineRunner fixCheckConstraints(JdbcTemplate jdbcTemplate) {
        return args -> {
            try {
                log.info("Checking and dropping stale H2 CHECK constraints for StatutFacture...");
                jdbcTemplate.query("SELECT c.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                    "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS c ON tc.CONSTRAINT_NAME = c.CONSTRAINT_NAME " +
                    "WHERE tc.TABLE_NAME = 'FACTURES_STRUCTURE' AND (c.CHECK_CLAUSE LIKE '%STATUT%' OR c.CHECK_CLAUSE LIKE '%BROUILLON%')",
                    (rs, rowNum) -> {
                        String name = rs.getString(1);
                        log.info("Dropping constraint: {}", name);
                        jdbcTemplate.execute("ALTER TABLE factures_structure DROP CONSTRAINT " + name);
                        return name;
                    });
            } catch (Exception e) {
                log.warn("Could not drop check constraint: {}", e.getMessage());
            }
        };
    }
}
