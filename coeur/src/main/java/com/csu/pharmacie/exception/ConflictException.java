package com.csu.pharmacie.exception;

/**
 * Levée lors d'un conflit avec l'état existant (doublon de code, email, facture...). Mappée en HTTP 409.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
