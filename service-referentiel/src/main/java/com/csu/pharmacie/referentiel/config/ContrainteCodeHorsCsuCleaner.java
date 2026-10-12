package com.csu.pharmacie.referentiel.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Retire l'unicité <b>globale</b> de {@code prestations_hors_csu.code}, remplacée par
 * l'unicité par couple {@code (code, structure_id)}.
 *
 * <p>Sur toute base créée avant ce changement, Hibernate a posé DEUX contraintes uniques
 * sur la seule colonne {@code code} : celle du {@code @Column(unique = true)} (nom généré,
 * {@code uk_…}) et celle du {@code @Index(unique = true)} ({@code idx_hors_csu_code}).
 * {@code ddl-auto=update} sait ajouter la nouvelle contrainte composite, jamais supprimer
 * les anciennes — elles continueraient donc de refuser ce que le modèle autorise
 * désormais :</p>
 * <ul>
 *   <li>un tarif propre à une structure portant le même code que le tarif national, alors
 *       que {@code ServiceHorsCsu.applicables} est écrit pour arbitrer précisément ce cas ;</li>
 *   <li>deux structures important le même fichier de tarifs — la seconde écrasait
 *       silencieusement les lignes de la première, faute de pouvoir insérer les siennes.</li>
 * </ul>
 *
 * <p>Idempotent : sans contrainte obsolète, il ne fait rien. Aucune donnée n'est touchée,
 * seule une règle d'unicité devenue fausse est levée. {@code @Order(0)} pour passer avant
 * l'amorçage du référentiel, qui insère des codes.</p>
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class ContrainteCodeHorsCsuCleaner implements CommandLineRunner {

    private static final String TABLE = "prestations_hors_csu";

    private final JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        for (String contrainte : contraintesUniquesSurCodeSeul()) {
            try {
                jdbc.execute("ALTER TABLE " + TABLE + " DROP CONSTRAINT IF EXISTS \"" + contrainte + "\"");
                log.info("Unicité globale obsolète « {} » supprimée sur {} (remplacée par (code, structure_id))",
                        contrainte, TABLE);
            } catch (Exception e) {
                log.warn("Suppression impossible de « {} » sur {} : {}", contrainte, TABLE, e.getMessage());
            }
        }
    }

    /**
     * Contraintes UNIQUE de la table portant sur la colonne {@code code} <b>et elle seule</b>.
     *
     * <p>Le {@code HAVING COUNT(*) = 1} est ce qui protège la nouvelle contrainte composite :
     * elle porte sur deux colonnes, elle n'est donc jamais renvoyée ici — inutile de la
     * reconnaître par son nom, et le nettoyeur reste correct si Hibernate le change.</p>
     */
    private List<String> contraintesUniquesSurCodeSeul() {
        try {
            return jdbc.queryForList("""
                    SELECT tc.constraint_name
                      FROM information_schema.table_constraints tc
                      JOIN information_schema.key_column_usage kcu
                        ON kcu.constraint_name = tc.constraint_name
                       AND kcu.constraint_schema = tc.constraint_schema
                     WHERE tc.table_name = ?
                       AND tc.constraint_type = 'UNIQUE'
                     GROUP BY tc.constraint_name
                    HAVING COUNT(*) = 1
                       AND MIN(kcu.column_name) = 'code'
                    """, String.class, TABLE);
        } catch (Exception e) {
            // Base non PostgreSQL (H2 en test) ou schéma pas encore créé : rien à nettoyer.
            log.debug("Contraintes de {} non inspectables : {}", TABLE, e.getMessage());
            return List.of();
        }
    }
}
