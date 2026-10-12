package com.csu.pharmacie.exception;

/**
 * Levée lorsqu'une règle métier interdit l'opération demandée (transition de statut invalide,
 * médicament exclu, modification d'une facture déjà envoyée...). Mappée en HTTP 422.
 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
