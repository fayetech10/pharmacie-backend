package com.csu.pharmacie.utils;

import java.security.SecureRandom;

/** Génère des numéros courts, numériques et faciles à saisir (ex: LG-482910) pour lettres/bons. */
public final class NumeroGenerator {
    private static final String DIGITS = "0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private NumeroGenerator() {
    }

    public static String generer(String prefixe) {
        StringBuilder suffixe = new StringBuilder();
        // On génère 6 chiffres (très facile à taper sur pavé numérique)
        for (int i = 0; i < 6; i++) {
            suffixe.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        }
        return prefixe + "-" + suffixe;
    }
}
