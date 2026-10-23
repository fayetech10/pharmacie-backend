package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.commun.carte.ServiceCarteCsu;
import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.ClientPharmacie;
import com.csu.pharmacie.commun.client.ClientReferentiel;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.PieceDossier;
import com.csu.pharmacie.entity.Regime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Photos obligatoires pour valider (23/10/2026) : sans la photo de la carte d'assuré — et,
 * pour un régime contributif, du ticket de caisse — le dossier reste « à compléter », donc ni
 * validé ni envoyé. Les autres régimes ne changent pas.
 */
class PiecesObligatoiresTest {

    private final ServiceFacturation service = new ServiceFacturation(mock(DepotFactureStructure.class),
            new ReglesFacturation(new ParametresFacturation()), mock(ClientIdentite.class),
            mock(ClientReferentiel.class), mock(ClientPharmacie.class), mock(Numerotation.class),
            mock(GenerateurPdf.class), mock(DepotLettreGarantie.class), mock(DepotFeuilleSoins.class),
            mock(DepotPieceDossier.class), mock(ServiceCarteCsu.class));

    private static DossierPatient dossier(String... pieces) {
        return DossierPatient.builder().id("dossier-1").nom("DIOP").prenom("Awa")
                .pieces(new ArrayList<>(List.of(pieces))).build();
    }

    private String statut(Regime regime, DossierPatient dossier) {
        return service.statutDossier(regime, dossier, 1, false);
    }

    @Test
    void unContributifExigeLaCarteEtLeTicket() {
        assertEquals(DossierPatient.A_COMPLETER, statut(Regime.CONTRIBUTIF, dossier()));
        assertEquals(DossierPatient.A_COMPLETER, statut(Regime.CONTRIBUTIF, dossier(PieceDossier.CARTE_ASSURE)));
        assertEquals(DossierPatient.A_COMPLETER, statut(Regime.ELEVES, dossier(PieceDossier.TICKET_CAISSE)));
        assertEquals(DossierPatient.COMPLET, statut(Regime.NDONGO_DARA,
                dossier(PieceDossier.CARTE_ASSURE, PieceDossier.TICKET_CAISSE)));
    }

    @Test
    void bsfEtCecExigentLaCarteSeulement() {
        assertEquals(DossierPatient.A_COMPLETER, statut(Regime.BSF, dossier()));
        assertEquals(DossierPatient.COMPLET, statut(Regime.BSF, dossier(PieceDossier.CARTE_ASSURE)));
        assertEquals(DossierPatient.COMPLET, statut(Regime.CEC, dossier(PieceDossier.CARTE_ASSURE)));
    }

    @Test
    void lesAutresRegimesNeChangentPas() {
        assertEquals(DossierPatient.COMPLET, statut(Regime.ZERO_CINQ_ANS, dossier()));
        assertEquals(DossierPatient.A_COMPLETER, statut(Regime.SESAME, dossier()));
        assertEquals(DossierPatient.COMPLET, statut(Regime.SESAME,
                dossier(PieceDossier.CNI_RECTO, PieceDossier.CNI_VERSO)));
    }

    @Test
    void uneSaisieSurLettreDeGarantieNExigeAucunePhoto() {
        DossierPatient surLettre = dossier();
        surLettre.setLettreGarantieNumero("LG-THI-0001");

        assertEquals(DossierPatient.COMPLET, statut(Regime.CONTRIBUTIF, surLettre));
    }
}
