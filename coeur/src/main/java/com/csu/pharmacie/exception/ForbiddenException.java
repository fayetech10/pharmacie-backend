package com.csu.pharmacie.exception;

/**
 * Levée lorsqu'un utilisateur n'a pas les droits pour effectuer une action. Mappée en HTTP 403.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
