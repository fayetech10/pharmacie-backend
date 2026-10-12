package com.csu.pharmacie.commun.client;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Récupère l'en-tête {@code Authorization} de la requête HTTP en cours, s'il y en a une. */
public final class JetonCourant {

    private JetonCourant() {
    }

    /**
     * Valeur brute de l'en-tête ({@code Bearer …}) ou {@code null} hors contexte web
     * (tâche planifiée, initialisation au démarrage).
     */
    public static String entete() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributs)) {
            return null;
        }
        return attributs.getRequest().getHeader("Authorization");
    }
}
