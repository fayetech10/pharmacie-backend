package com.csu.pharmacie.entity;

/**
 * Circuit de la feuille de soins (formulaire papier ASCSU) :
 * délivrée au patient par l'agent bureau (BCSU), remise à la structure sanitaire
 * qui remplit la prise en charge, puis rattachée à la facture de la structure.
 */
public enum StatutFeuilleSoins {
    /** Délivrée au patient par l'agent bureau — en attente de dépôt en structure. */
    EMISE,
    /** Prise en charge remplie par la structure sanitaire (le patient a déposé la feuille). */
    DEPOSEE_STRUCTURE,
    /** Rattachée à une facture de la structure sanitaire (fin du circuit). */
    FACTUREE,
    ANNULEE
}
