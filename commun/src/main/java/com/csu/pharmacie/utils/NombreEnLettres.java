package com.csu.pharmacie.utils;

/**
 * Conversion d'un entier en toutes lettres françaises, pour les mentions « Arrêtée la
 * présente … à la somme de … francs CFA » des documents officiels.
 *
 * <p>Placée dans {@code commun} pour servir tous les modules (factures et listes du
 * {@code coeur}, attestation et situation globale du {@code socle}) sans dupliquer la
 * logique.</p>
 */
public final class NombreEnLettres {

    private NombreEnLettres() {
    }

    private static final String[] U = {
            "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf"
    };
    private static final String[] D = {
            "", "", "vingt", "trente", "quarante", "cinquante", "soixante", "", "quatre-vingt", ""
    };

    /** Entier en toutes lettres (ex. 1544200 → « un million cinq cent quarante-quatre mille deux cents »). */
    public static String convertir(long n) {
        if (n == 0) {
            return "zéro";
        }
        if (n < 0) {
            return "moins " + convertir(-n);
        }
        StringBuilder sb = new StringBuilder();
        long milliards = n / 1_000_000_000L;
        n %= 1_000_000_000L;
        long millions = n / 1_000_000L;
        n %= 1_000_000L;
        long mille = n / 1000L;
        long reste = n % 1000L;

        if (milliards > 0) {
            sb.append(groupe((int) milliards)).append(milliards > 1 ? " milliards " : " milliard ");
        }
        if (millions > 0) {
            sb.append(groupe((int) millions)).append(millions > 1 ? " millions " : " million ");
        }
        if (mille > 0) {
            sb.append(mille == 1 ? "mille " : groupe((int) mille) + " mille ");
        }
        if (reste > 0) {
            sb.append(groupe((int) reste));
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    /** 0 à 999. */
    private static String groupe(int n) {
        if (n == 0) {
            return "";
        }
        int c = n / 100, r = n % 100;
        StringBuilder sb = new StringBuilder();
        if (c > 0) {
            sb.append(c == 1 ? "cent" : U[c] + " cent");
            if (c > 1 && r == 0) {
                sb.append("s"); // « deux cents », mais « deux cent un »
            }
            if (r > 0) {
                sb.append(" ");
            }
        }
        if (r > 0) {
            sb.append(deuxChiffres(r));
        }
        return sb.toString();
    }

    /** 0 à 99, avec les cas 70-79 (soixante-dix…), 80 (quatre-vingts) et 90-99. */
    private static String deuxChiffres(int n) {
        if (n < 20) {
            return U[n];
        }
        int d = n / 10, u = n % 10;
        switch (d) {
            case 7:
                return u == 1 ? "soixante-et-onze" : "soixante-" + U[10 + u];
            case 9:
                return "quatre-vingt-" + U[10 + u];
            case 8:
                return u == 0 ? "quatre-vingts" : "quatre-vingt-" + U[u];
            default:
                String base = D[d];
                if (u == 0) {
                    return base;
                }
                if (u == 1) {
                    return base + "-et-un";
                }
                return base + "-" + U[u];
        }
    }
}
