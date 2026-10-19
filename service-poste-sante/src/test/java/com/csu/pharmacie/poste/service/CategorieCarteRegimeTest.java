package com.csu.pharmacie.poste.service;

import com.csu.pharmacie.entity.Regime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Une carte CSU ne s'emploie que dans le régime de sa catégorie : une carte classique sur
 * BSF / CEC, ou l'inverse, est refusée à la facturation (19/10/2026).
 */
class CategorieCarteRegimeTest {

    @Test
    void carteClassiqueRefuseeSurBsfCec() {
        assertThat(Regime.BSF_CEC.compatibleAvecCarte("CLASSIQUE")).isFalse();
        assertThat(Regime.CEC.compatibleAvecCarte("CLASSIQUE")).isFalse();
        assertThat(Regime.BSF.compatibleAvecCarte("Classique")).isFalse();
    }

    @Test
    void carteCecRefuseeSurLeClassique() {
        assertThat(Regime.CONTRIBUTIF.compatibleAvecCarte("CEC")).isFalse();
        assertThat(Regime.NDONGO_DARA.compatibleAvecCarte("BSF")).isFalse();
        assertThat(Regime.ELEVES.compatibleAvecCarte("BSF / CEC")).isFalse();
    }

    @Test
    void carteDeSaCategorieAcceptee() {
        assertThat(Regime.CONTRIBUTIF.compatibleAvecCarte("CLASSIQUE")).isTrue();
        assertThat(Regime.ELEVES.compatibleAvecCarte("Élèves")).isTrue();
        assertThat(Regime.BSF_CEC.compatibleAvecCarte("CEC")).isTrue();
        assertThat(Regime.CEC.compatibleAvecCarte("BSF")).isTrue();
        assertThat(Regime.SESAME.compatibleAvecCarte("Sésame")).isTrue();
    }

    @Test
    void categorieInconnueSansObjection() {
        assertThat(Regime.BSF_CEC.compatibleAvecCarte(null)).isNull();
        assertThat(Regime.CONTRIBUTIF.compatibleAvecCarte("")).isNull();
        assertThat(Regime.CONTRIBUTIF.compatibleAvecCarte("AUTRE")).isNull();
    }
}
