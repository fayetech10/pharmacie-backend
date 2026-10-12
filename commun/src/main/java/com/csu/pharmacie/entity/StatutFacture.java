package com.csu.pharmacie.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Cycle de vie d'une facture, du point de soins jusqu'au paiement.
 *
 * <pre>
 *   Poste de santé :  BROUILLON → SOUMISE_CS → (REJETEE_CS) → ENVOYEE → …
 *   Centre / EPS   :  BROUILLON → ENVOYEE → …
 *   Commun         :  ENVOYEE → VALIDEE_SR → VALIDEE_NC → PAYEE
 *                             ↘ REJETEE_SR          ↘ REJETEE_NC
 * </pre>
 */
public enum StatutFacture {

    BROUILLON("Brouillon"),
    // Noms historiques (« CS ») conservés en base ; le contrôle des factures de postes est
    // fait par le DISTRICT depuis le 08/10/2026 — d'où les libellés.
    SOUMISE_CS("Soumise au district"),
    REJETEE_CS("Rejetée par le district"),
    ENVOYEE("Transmise au service régional"),
    VALIDEE_SR("Validée par le service régional"),
    REJETEE_SR("Rejetée par le service régional"),
    VALIDEE_NC("Validée par le niveau central"),
    REJETEE_NC("Rejetée par le niveau central"),
    PAYEE("Payée");

    private final String libelle;

    StatutFacture(String libelle) {
        this.libelle = libelle;
    }

    public String getLibelle() {
        return libelle;
    }

    /** Statuts pour lesquels la structure émettrice peut encore modifier sa facture. */
    public static final Set<StatutFacture> MODIFIABLES =
            EnumSet.of(BROUILLON, REJETEE_CS, REJETEE_SR, REJETEE_NC);

    /** Statuts comptabilisés comme « facturé » dans les consolidations et tableaux de bord. */
    public static final Set<StatutFacture> COMPTABILISES =
            EnumSet.of(SOUMISE_CS, ENVOYEE, VALIDEE_SR, VALIDEE_NC, PAYEE);

    public boolean estModifiable() {
        return MODIFIABLES.contains(this);
    }

    public boolean estComptabilise() {
        return COMPTABILISES.contains(this);
    }
}
