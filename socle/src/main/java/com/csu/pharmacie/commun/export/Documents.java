package com.csu.pharmacie.commun.export;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formats d'affichage communs à tous les documents produits (PDF et Excel). */
public final class Documents {

    private Documents() {
    }

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    public static final DateTimeFormatter DATE_HEURE = DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm");

    /** Montants en FCFA : pas de décimale, espace insécable comme séparateur de milliers. */
    private static final DecimalFormat MONTANT;

    static {
        DecimalFormatSymbols symboles = new DecimalFormatSymbols(Locale.FRANCE);
        symboles.setGroupingSeparator(' ');
        MONTANT = new DecimalFormat("#,##0", symboles);
    }

    public static synchronized String montant(double valeur) {
        return MONTANT.format(Math.round(valeur));
    }

    public static String montantAvecDevise(double valeur) {
        return montant(valeur) + " FCFA";
    }

    public static String ouVide(String valeur) {
        return valeur == null ? "" : valeur;
    }

    /** Nom de fichier sûr : accents et séparateurs remplacés, longueur bornée. */
    public static String nomFichier(String base, String extension) {
        String nettoye = java.text.Normalizer.normalize(ouVide(base), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9._-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (nettoye.isBlank()) {
            nettoye = "document";
        }
        if (nettoye.length() > 120) {
            nettoye = nettoye.substring(0, 120);
        }
        return nettoye + "." + extension;
    }
}
