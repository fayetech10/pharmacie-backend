package com.csu.pharmacie.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Garde-fou de la contrainte CHECK figée sur {@code medicaments.statut}.
 *
 * <p>Hibernate a créé, sur les bases existantes, un {@code CHECK (statut IN ('ELIGIBLE','EXCLU'))}
 * que {@code ddl-auto: update} ne met jamais à jour. L'arrivée du statut {@code NON_REPERTORIE}
 * (médicament ajouté par un pharmacien) faisait donc échouer l'insertion → HTTP 500 à
 * l'enregistrement d'un patient. Ce test reproduit l'ancien schéma et vérifie que le
 * nettoyage au démarrage débloque bien l'insertion.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:check-cleaner;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MedicamentStatutCheckConstraintTest {

    @Autowired
    private DataSource dataSource;

    private static final String INSERT_NON_REPERTORIE =
            "INSERT INTO medicaments (id, code, nom, statut, actif) "
            + "VALUES ('test-1', 'NR-TEST-1', 'MEDICAMENT TEST', 'NON_REPERTORIE', TRUE)";

    @Test
    void laContrainteFigeeEstSupprimeeEtNonRepertorieDevientInserable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 1. Reproduit l'ancien schéma : on remplace la CHECK courante (qui connaît
        //    NON_REPERTORIE) par celle d'origine, limitée aux deux valeurs historiques.
        for (String nom : jdbc.queryForList(
                "SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                + "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME "
                + "WHERE tc.TABLE_NAME = 'MEDICAMENTS' AND tc.CONSTRAINT_TYPE = 'CHECK' "
                + "  AND UPPER(cc.CHECK_CLAUSE) LIKE '%ELIGIBLE%'", String.class)) {
            jdbc.execute("ALTER TABLE MEDICAMENTS DROP CONSTRAINT IF EXISTS \"" + nom + "\"");
        }
        jdbc.execute("ALTER TABLE MEDICAMENTS ADD CONSTRAINT medicaments_statut_legacy "
                + "CHECK (statut IN ('ELIGIBLE','EXCLU'))");

        // 2. Avant nettoyage : l'insertion est refusée — c'est la cause du HTTP 500.
        assertThrows(Exception.class, () -> jdbc.execute(INSERT_NON_REPERTORIE),
                "La contrainte figée doit refuser NON_REPERTORIE");

        // 3. Nettoyage effectué au démarrage de l'application.
        new RegimeCheckConstraintCleaner(jdbc).run();

        // 4. Après nettoyage : l'insertion passe.
        assertDoesNotThrow(() -> jdbc.execute(INSERT_NON_REPERTORIE),
                "Après suppression de la contrainte, NON_REPERTORIE doit être accepté");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM medicaments WHERE statut = 'NON_REPERTORIE'", Integer.class));
    }
}
