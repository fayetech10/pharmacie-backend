package com.csu.pharmacie.commun.facturation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paramètres métier de la facturation, communs à tous les services (préfixe {@code app.csu}).
 *
 * <p>Les valeurs vivent dans la configuration et non dans le code : les montants de forfait
 * et l'âge limite d'un régime sont des décisions administratives qui changent sans livraison
 * applicative.</p>
 */
@Data
@ConfigurationProperties(prefix = "app.csu")
public class ParametresFacturation {

    /**
     * Montant du forfait « Enfants de moins de 5 ans », en FCFA.
     * {@code 0} désactive le forfait : les prix du référentiel s'appliquent alors normalement.
     */
    private double forfaitZeroCinqAns = 0;

    /**
     * Comment le forfait est appliqué quand il est actif.
     * <ul>
     *   <li>{@code PAR_LIGNE} — le forfait remplace le prix unitaire de chaque prestation ;</li>
     *   <li>{@code PAR_BENEFICIAIRE} — une seule ligne par dossier patient (à défaut par
     *       enfant) est retenue au forfait, les autres passent à zéro (facturation à
     *       l'épisode). C'est celui de l'Espace PS : 1 400 FCFA par patient.</li>
     * </ul>
     */
    private ModeForfait modeForfait = ModeForfait.PAR_LIGNE;

    /**
     * Forfait « Enfants de moins de 5 ans » des patients identifiés directement depuis
     * l'Espace PS (poste comme centre de santé) : 1 400 FCFA par patient, 100 % SEN-CSU.
     *
     * <p>Distinct de {@link #forfaitZeroCinqAns}, qui reste celui des saisies sur lettre de
     * garantie d'un centre : le cahier « Espace PS » fixe le forfait de ses propres saisies
     * sans rien dire du circuit BCSU, qu'il n'y avait donc pas lieu de modifier.</p>
     */
    private double forfaitEspacePs = 1400;

    /** Un forfait par dossier patient (épisode de soins), quel que soit le nombre d'actes. */
    private ModeForfait modeForfaitEspacePs = ModeForfait.PAR_BENEFICIAIRE;

    /** Contrôle bloquant de l'âge (régimes Enfants 0-5 ans et Plan Sésame). */
    private boolean controleAgeBloquant = true;

    /** Préfixe des matricules générés pour le Plan Sésame. */
    private String prefixeMatriculeSesame = "SES";

    public enum ModeForfait {
        PAR_LIGNE,
        PAR_BENEFICIAIRE
    }
}
