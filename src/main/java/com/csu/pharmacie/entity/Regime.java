package com.csu.pharmacie.entity;

/**
 * Régime de couverture du bénéficiaire — détermine la répartition part bénéficiaire / part SEN-CSU.
 * Catégories alignées sur le classeur « GESTION DES FACTURES CS_EPS » (un onglet par catégorie) :
 * Classique, Enfants 0-5 ans, Césarienne, Dialyse péritonéale, Hémodialyse, BSF, CEC, Sésame, Ndongo Dara.
 */
public enum Regime {
    CONTRIBUTIF,
    SESAME,
    CESARIENNE,
    ZERO_CINQ_ANS,
    DIALYSE_PERITONEALE,
    HEMODIALYSE,
    BSF,
    CEC,
    NDONGO_DARA,
    /** @deprecated scindé en {@link #DIALYSE_PERITONEALE} / {@link #HEMODIALYSE} — conservé pour les données existantes. */
    @Deprecated
    DIALYSE,
    /** @deprecated scindé en {@link #BSF} / {@link #CEC} — conservé pour les données existantes. */
    @Deprecated
    BSF_CEC;

    /** CONTRIBUTIF = 20% à la charge du bénéficiaire ; tous les autres régimes = 0%. */
    public double tauxBeneficiaire() {
        return this == CONTRIBUTIF ? 0.20 : 0.0;
    }

    /** Complément à 100% de {@link #tauxBeneficiaire()} (80% pour CONTRIBUTIF, 100% sinon). */
    public double tauxSencsu() {
        return 1.0 - tauxBeneficiaire();
    }
}
