package com.csu.pharmacie.commun.web;

import org.springframework.http.HttpStatus;

/**
 * Erreur fonctionnelle portant son propre statut HTTP.
 *
 * <p>Un seul type d'exception pour toute la couche métier : les services lèvent
 * {@code ExceptionMetier.introuvable(...)} ou {@code ExceptionMetier.invalide(...)} et
 * {@link GestionnaireErreurs} les traduit en réponse JSON. Cela évite la prolifération
 * de classes d'exception qui ne diffèrent que par leur code.</p>
 */
public class ExceptionMetier extends RuntimeException {

    private final HttpStatus statut;

    public ExceptionMetier(HttpStatus statut, String message) {
        super(message);
        this.statut = statut;
    }

    public HttpStatus getStatut() {
        return statut;
    }

    /** 400 — la requête est syntaxiquement correcte mais viole une règle métier. */
    public static ExceptionMetier invalide(String message) {
        return new ExceptionMetier(HttpStatus.BAD_REQUEST, message);
    }

    /** 401 — aucune identité exploitable. */
    public static ExceptionMetier nonAuthentifie(String message) {
        return new ExceptionMetier(HttpStatus.UNAUTHORIZED, message);
    }

    /** 403 — identité connue, mais non autorisée sur cette ressource. */
    public static ExceptionMetier interdit(String message) {
        return new ExceptionMetier(HttpStatus.FORBIDDEN, message);
    }

    /** 404 — ressource inexistante (ou hors du périmètre visible de l'appelant). */
    public static ExceptionMetier introuvable(String message) {
        return new ExceptionMetier(HttpStatus.NOT_FOUND, message);
    }

    /** 409 — conflit avec l'état courant (doublon, transition de statut impossible). */
    public static ExceptionMetier conflit(String message) {
        return new ExceptionMetier(HttpStatus.CONFLICT, message);
    }

    /** 503 — un service amont est injoignable (voir les clients inter-services). */
    public static ExceptionMetier serviceIndisponible(String message) {
        return new ExceptionMetier(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
