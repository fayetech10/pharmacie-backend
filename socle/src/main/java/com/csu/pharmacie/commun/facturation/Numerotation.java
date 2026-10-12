package com.csu.pharmacie.commun.facturation;

/**
 * Attribution des numéros de facture.
 *
 * <p>Chaque service tient sa propre séquence, en base : deux services distincts ne
 * peuvent pas se coordonner sur un compteur en mémoire, et un compteur global
 * introduirait une dépendance à l'écriture entre eux. Le préfixe suffit à garantir
 * l'unicité inter-services ({@code FP-} pour un poste, {@code FC-} pour un centre).</p>
 */
public interface Numerotation {

    /**
     * Prochain numéro de la séquence, au format {@code <prefixe>-<annee><mois>-<compteur>}.
     * L'implémentation doit être sûre en concurrence : deux factures créées simultanément
     * ne doivent jamais recevoir le même numéro.
     */
    String prochainNumero(String prefixe, int annee, int mois);
}
