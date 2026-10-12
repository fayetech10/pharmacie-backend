package com.csu.pharmacie.exception;

/**
 * Levée lorsqu'une ressource demandée est introuvable. Mappée en HTTP 404.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
