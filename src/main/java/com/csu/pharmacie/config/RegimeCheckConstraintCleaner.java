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
 * enum : {@code regime}, {@code role} (users) et {@code statut} (medicaments).
 *
 * <p>Hibernate crée, à la première génération du schéma, une contrainte
 * {@code CHECK (colonne IN (...valeurs de l'enum...))}. Avec {@code ddl-auto: update},
 * cette contrainte n'est jamais mise à jour : lorsque l'énumération Java évolue
 * (nouveau régime, nouveau rôle, statut {@code NON_REPERTORIE} des médicaments),
 * l'insertion d'un enregistrement portant une valeur récente échoue (violation de
 * contrainte). La validation reste assurée côté application par l'enum Java, donc on
 * peut retirer ces CHECK figées en toute sécurité.
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
            "PATIENTS", "LETTRES_GARANTIE", "FACTURES_STRUCTURE", "FEUILLES_SOINS", "BONS_COMMANDE", "CONSTATS");

    private final JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        // ── AJOUT : Supprimer la contrainte obsolète sur le rôle utilisateur ──
        try {
            jdbc.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check");
            log.info("Contrainte CHECK users_role_check obsolète supprimée sur users");
        } catch (Exception e) {
            log.warn("Suppression impossible de users_role_check : {}", e.getMessage());
        }

        for (String table : TABLES) {
            supprimerCheckObsoletes(table, "%REGIME%", "regime");
        }

        // Statut des médicaments : l'énumération a gagné NON_REPERTORIE (médicament ajouté
        // par un pharmacien). La CHECK figée sur (ELIGIBLE, EXCLU) refuserait l'insertion.
        // Filtre sur « ELIGIBLE » (valeur de l'enum) : sans ambiguïté avec les contraintes
        // NOT NULL que PostgreSQL expose aussi dans CHECK_CONSTRAINTS.
        supprimerCheckObsoletes("MEDICAMENTS", "%ELIGIBLE%", "statut");

        // Statut des factures (structure + pharmacie) : StatutFacture a gagné SOUMISE_CS /
        // REJETEE_CS (circuit Poste de Santé → Centre de Santé). La CHECK figée à la création
        // des tables ignore ces valeurs → l'envoi d'une facture par un poste (statut SOUMISE_CS)
        // échoue en 500 sur toute base préexistante. Filtre sur « BROUILLON » (présent dans
        // toutes les versions de l'enum) pour viser la CHECK du statut sans toucher au NOT NULL.
        supprimerCheckObsoletes("FACTURES_STRUCTURE", "%BROUILLON%", "statut");
        supprimerCheckObsoletes("FACTURES", "%BROUILLON%", "statut");
    }

    /** Supprime les contraintes CHECK d'une table dont la clause correspond au motif donné. */
    private void supprimerCheckObsoletes(String table, String motifClause, String libelle) {
        for (String name : findCheckConstraints(table, motifClause)) {
            try {
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + name + "\"");
                log.info("Contrainte CHECK « {} » obsolète supprimée sur {} : {}", libelle, table, name);
            } catch (Exception e) {
                log.warn("Suppression impossible de la contrainte {} sur {} : {}", name, table, e.getMessage());
            }
        }
    }

    /** Contraintes CHECK d'une table dont la clause correspond au motif SQL LIKE fourni. */
    private List<String> findCheckConstraints(String table, String motifClause) {
        try {
            // UPPER(tc.TABLE_NAME) : H2 stocke les identifiants non quotés en MAJUSCULES, PostgreSQL
            // en minuscules. Sans ce UPPER, le filtre « = 'FACTURES_STRUCTURE' » ne matchait qu'en
            // H2 (test) et jamais en prod Postgres (table « factures_structure ») → les contraintes
            // figées survivaient au démarrage. Les libellés passés (table) sont déjà en MAJUSCULES.
            return jdbc.queryForList(
                    "SELECT tc.CONSTRAINT_NAME " +
                    "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                    "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc " +
                    "  ON tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME " +
                    " AND tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA " +
                    "WHERE UPPER(tc.TABLE_NAME) = ? AND tc.CONSTRAINT_TYPE = 'CHECK' " +
                    "  AND UPPER(cc.CHECK_CLAUSE) LIKE ?",
                    String.class, table, motifClause);
        } catch (Exception e) {
            // Table absente ou SGBD ne supportant pas cette vue : on ignore (best effort).
            log.debug("Recherche des CHECK {} ignorée pour {} : {}", motifClause, table, e.getMessage());
            return List.of();
        }
    }
}
