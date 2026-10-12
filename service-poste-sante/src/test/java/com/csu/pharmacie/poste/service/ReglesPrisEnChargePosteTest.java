package com.csu.pharmacie.poste.service;

import com.csu.pharmacie.commun.facturation.ParametresFacturation;
import com.csu.pharmacie.commun.facturation.ReglesFacturation;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Règles de prise en charge du cahier « Poste de Santé » (§6). */
class ReglesPrisEnChargePosteTest {

    private static ReglesFacturation reglesDuPoste() {
        ParametresFacturation parametres = new ParametresFacturation();
        // Valeurs par défaut de l'Espace PS : 1 400 F par dossier patient.
        assertThat(parametres.getForfaitEspacePs()).isEqualTo(1400);
        return new ReglesFacturation(parametres);
    }

    private static LigneFactureStructure ligne(String dossierId, int quantite, double prix, LocalDate naissance) {
        return LigneFactureStructure.builder()
                .dossierId(dossierId)
                .patientNom("DIOP")
                .patientPrenom("Awa")
                .patientDateNaissance(naissance)
                .designation("Consultation")
                .quantite(quantite)
                .prixUnitaire(prix)
                .datePriseEnCharge(LocalDate.now())
                .build();
    }

    private static FactureStructure facture(Regime regime, LigneFactureStructure... lignes) {
        FactureStructure facture = new FactureStructure();
        facture.setRegime(regime);
        facture.setLignes(new ArrayList<>(List.of(lignes)));
        return facture;
    }

    @Test
    void regimesContributifsRepartis80Sur20() {
        for (Regime regime : List.of(Regime.CONTRIBUTIF, Regime.NDONGO_DARA, Regime.ELEVES)) {
            FactureStructure facture = facture(regime, ligne("d1", 2, 1000, LocalDate.of(1990, 1, 1)));
            reglesDuPoste().appliquer(facture);
            assertThat(facture.getMontantTotal()).as(regime.name()).isEqualTo(2000);
            assertThat(facture.getMontantTotalSencsu()).as(regime.name()).isEqualTo(1600);
            assertThat(facture.getMontantTotalBeneficiaire()).as(regime.name()).isEqualTo(400);
        }
    }

    @Test
    void forfaitDe1400ParPatientQuelQueSoitLeNombreDActes() {
        LocalDate enfant = LocalDate.now().minusYears(2);
        FactureStructure facture = facture(Regime.ZERO_CINQ_ANS,
                ligne("d1", 1, 0, enfant),
                ligne("d1", 3, 0, enfant),
                ligne("d2", 1, 0, enfant));
        reglesDuPoste().appliquer(facture);
        assertThat(facture.getMontantTotal()).isEqualTo(2800);
        assertThat(facture.getMontantTotalSencsu()).isEqualTo(2800);
        assertThat(facture.getMontantTotalBeneficiaire()).isZero();
    }

    @Test
    void saisieSurLettreDeGarantieGardeSonPropreForfait() {
        LocalDate enfant = LocalDate.now().minusYears(2);
        LigneFactureStructure surLettre = ligne("d1", 2, 500, enfant);
        surLettre.setLettreGarantieId("lg-1");
        FactureStructure facture = facture(Regime.ZERO_CINQ_ANS, surLettre);
        reglesDuPoste().appliquer(facture);
        // Forfait « lettre de garantie » à 0 par défaut : les prix saisis s'appliquent.
        assertThat(facture.getMontantTotal()).isEqualTo(1000);
    }

    @Test
    void sesameEtBsfCecPrisEnChargeA100Pourcent() {
        for (Regime regime : List.of(Regime.SESAME, Regime.BSF_CEC)) {
            FactureStructure facture = facture(regime, ligne("d1", 1, 2500, LocalDate.of(1950, 1, 1)));
            reglesDuPoste().appliquer(facture);
            assertThat(facture.getMontantTotalSencsu()).as(regime.name()).isEqualTo(2500);
            assertThat(facture.getMontantTotalBeneficiaire()).as(regime.name()).isZero();
        }
    }

    @Test
    void regimesACarte() {
        assertThat(Regime.CONTRIBUTIF.exigeCarteCsu()).isTrue();
        assertThat(Regime.NDONGO_DARA.exigeCarteCsu()).isTrue();
        assertThat(Regime.ELEVES.exigeCarteCsu()).isTrue();
        assertThat(Regime.BSF_CEC.exigeCarteCsu()).isTrue();
        assertThat(Regime.SESAME.exigeCarteCsu()).isFalse();
        assertThat(Regime.ZERO_CINQ_ANS.exigeCarteCsu()).isFalse();
    }
}
