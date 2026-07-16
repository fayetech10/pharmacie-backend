package com.csu.pharmacie.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Supprime les contraintes CHECK obsolètes générées par Hibernate pour les colonnes
 * enum {@code regime}.
 *
 * <p>Hibernate crée, à la première génération du schéma, une contrainte
 * {@code CHECK (regime IN (...valeurs de l'enum...))}. Avec {@code ddl-auto: update},
 * cette contrainte n'est jamais mise à jour : lorsque l'énumération {@code Regime}
 * évolue (nouveaux régimes), l'insertion d'un enregistrement avec un régime récent
 * échoue (violation de contrainte). La validation reste assurée côté application par
 * l'enum Java, donc on peut retirer ces CHECK figées en toute sécurité.
 *
 * <p>Exécuté après l'initialisation du schéma ; idempotent (sans contrainte obsolète,
 * il ne fait rien) et sans effet destructif sur les données.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class RegimeCheckConstraintCleaner implements CommandLineRunner {

    /** Tables portant une colonne enum {@code regime}. */
    private static final List<String> TABLES = List.of(
            "PATIENTS", "LETTRES_GARANTIE", "FACTURES_STRUCTURE", "FEUILLES_SOINS", "BONS_COMMANDE");

    private final JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        for (String table : TABLES) {
            for (String name : findRegimeCheckConstraints(table)) {
                try {
                    jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + name + "\"");
                    log.info("Contrainte CHECK « regime » obsolète supprimée sur {} : {}", table, name);
                } catch (Exception e) {
                    log.warn("Suppression impossible de la contrainte {} sur {} : {}", name, table, e.getMessage());
                }
            }
        }
    }

    /** Contraintes CHECK d'une table dont la clause référence la colonne « regime ». */
    private List<String> findRegimeCheckConstraints(String table) {
        try {
            return jdbc.queryForList(
                    "SELECT tc.CONSTRAINT_NAME " +
                    "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                    "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc " +
                    "  ON tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME " +
                    " AND tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA " +
                    "WHERE tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'CHECK' " +
                    "  AND UPPER(cc.CHECK_CLAUSE) LIKE '%REGIME%'",
                    String.class, table);
        } catch (Exception e) {
            // Table absente ou SGBD ne supportant pas cette vue : on ignore (best effort).
            log.debug("Recherche des CHECK « regime » ignorée pour {} : {}", table, e.getMessage());
            return List.of();
        }
    }
}
