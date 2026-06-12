package com.csu.pharmacie.entity;

public enum StatutFacture {
    BROUILLON,    // Non envoyée (saisie pharmacie)
    ENVOYEE,      // Envoyée au Service Régional
    VALIDEE_SR,   // Validée par le SR et transmise au niveau central
    REJETEE_SR,   // Rejetée -> renvoyée à la pharmacie pour correction
    VALIDEE_NC,   // Validée par le niveau central
    REJETEE_NC,   // Rejetée par le niveau central -> reçue par le SR
    PAYEE         // Payée par le niveau central
}
