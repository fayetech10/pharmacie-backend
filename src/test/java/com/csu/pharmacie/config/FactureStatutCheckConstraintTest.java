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
 * Garde-fou de la contrainte CHECK figée sur {@code factures_structure.statut}.
 *
 * <p>Hibernate a créé, sur les bases existantes, un {@code CHECK (statut IN (...))} que
 * {@code ddl-auto: update} ne met jamais à jour. L'arrivée de {@code SOUMISE_CS} / {@code REJETEE_CS}
 * (circuit Poste de Santé → Centre de Santé, commit 035cee5) faisait donc échouer l'envoi d'une
 * facture par un poste de santé (passage à {@code SOUMISE_CS}) → HTTP 500. Ce test reproduit
 * l'ancien schéma et vérifie que le nettoyage au démarrage débloque bien la transition.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:check-cleaner-facture;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FactureStatutCheckConstraintTest {

    @Autowired
    private DataSource dataSource;

    private static final String INSERT_SOUMISE_CS =
            "INSERT INTO factures_structure (id, numero, statut, mois, annee, "
            + "montant_total, montant_total_beneficiaire, montant_total_sencsu) "
            + "VALUES ('test-fs-1', 'FS-TEST-1', 'SOUMISE_CS', 1, 2026, 0, 0, 0)";

    @Test
    void laContrainteFigeeEstSupprimeeEtSoumiseCsDevientInserable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 1. Reproduit l'ancien schéma : on remplace la CHECK courante (qui connaît SOUMISE_CS)
        //    par celle d'origine, limitée aux valeurs antérieures au circuit Poste → Centre.
        for (String nom : jdbc.queryForList(
                "SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                + "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME "
                + "WHERE tc.TABLE_NAME = 'FACTURES_STRUCTURE' AND tc.CONSTRAINT_TYPE = 'CHECK' "
                + "  AND UPPER(cc.CHECK_CLAUSE) LIKE '%BROUILLON%'", String.class)) {
            jdbc.execute("ALTER TABLE FACTURES_STRUCTURE DROP CONSTRAINT IF EXISTS \"" + nom + "\"");
        }
        jdbc.execute("ALTER TABLE FACTURES_STRUCTURE ADD CONSTRAINT factures_structure_statut_legacy "
                + "CHECK (statut IN ('BROUILLON','ENVOYEE','VALIDEE_SR','REJETEE_SR','VALIDEE_NC','REJETEE_NC','PAYEE'))");

        // 2. Avant nettoyage : la transition SOUMISE_CS est refusée — c'est la cause du HTTP 500.
        assertThrows(Exception.class, () -> jdbc.execute(INSERT_SOUMISE_CS),
                "La contrainte figée doit refuser SOUMISE_CS");

        // 3. Nettoyage effectué au démarrage de l'application.
        new RegimeCheckConstraintCleaner(jdbc).run();

        // 4. Après nettoyage : la transition passe.
        assertDoesNotThrow(() -> jdbc.execute(INSERT_SOUMISE_CS),
                "Après suppression de la contrainte, SOUMISE_CS doit être accepté");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM factures_structure WHERE statut = 'SOUMISE_CS'", Integer.class));
    }
}
