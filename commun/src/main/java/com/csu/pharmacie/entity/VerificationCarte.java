package com.csu.pharmacie.entity;

/**
 * Issue de la vérification de la carte CSU sur carte.sencsu.sn, pour les régimes qui en
 * exigent une (Classique, BSF, CEC) : la carte a été vérifiée valide, ou la vérification
 * était impossible parce que le site était en panne — seule dérogation admise.
 *
 * <p>Des chaînes plutôt qu'un enum : la colonne de la lettre de garantie reste un simple
 * varchar, sans contrainte CHECK à faire évoluer à chaque nouvelle valeur.</p>
 */
public final class VerificationCarte {

    public static final String VERIFIEE = "VERIFIEE";
    public static final String NON_VERIFIEE_PANNE = "NON_VERIFIEE_PANNE";

    private VerificationCarte() {
    }

    /** Valeur reconnue, ou {@code null} : rien d'autre ne doit être enregistré. */
    public static String normaliser(String valeur) {
        if (VERIFIEE.equals(valeur) || NON_VERIFIEE_PANNE.equals(valeur)) {
            return valeur;
        }
        return null;
    }

    /** Libellé de l'historique et des documents. */
    public static String libelle(String valeur) {
        if (VERIFIEE.equals(valeur)) {
            return "Carte CSU vérifiée sur carte.sencsu.sn";
        }
        if (NON_VERIFIEE_PANNE.equals(valeur)) {
            return "Carte CSU non vérifiée : site carte.sencsu.sn indisponible";
        }
        return null;
    }
}
