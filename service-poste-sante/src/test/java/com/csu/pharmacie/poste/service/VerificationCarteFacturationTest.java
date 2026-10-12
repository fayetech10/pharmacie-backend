package com.csu.pharmacie.poste.service;

import com.csu.pharmacie.commun.facturation.RequeteFacturation;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.VerificationCarte;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** La mention de vérification de la carte suit le patient sur chacune de ses lignes. */
class VerificationCarteFacturationTest {

    private static List<LigneFactureStructure> lignes(String verification) {
        RequeteFacturation requete = new RequeteFacturation();
        requete.setVerificationCarte(verification);
        requete.setLignes(List.of(new LigneFactureStructure(), new LigneFactureStructure()));
        return requete.lignesCompletees();
    }

    @Test
    void recopieLaMentionSurChaqueLigne() {
        assertThat(lignes(VerificationCarte.NON_VERIFIEE_PANNE))
                .extracting(LigneFactureStructure::getVerificationCarte)
                .containsOnly(VerificationCarte.NON_VERIFIEE_PANNE);
        assertThat(lignes(VerificationCarte.VERIFIEE))
                .extracting(LigneFactureStructure::getVerificationCarte)
                .containsOnly(VerificationCarte.VERIFIEE);
    }

    @Test
    void ignoreUneValeurInconnue() {
        assertThat(lignes("N_IMPORTE_QUOI"))
                .extracting(LigneFactureStructure::getVerificationCarte)
                .containsOnlyNulls();
        assertThat(lignes(null))
                .extracting(LigneFactureStructure::getVerificationCarte)
                .containsOnlyNulls();
    }
}
