package com.csu.pharmacie.centre.service;

import com.csu.pharmacie.commun.carte.ServiceCarteCsu;
import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.ClientPharmacie;
import com.csu.pharmacie.commun.client.ClientReferentiel;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.facturation.DepotFactureStructure;
import com.csu.pharmacie.commun.facturation.DepotFeuilleSoins;
import com.csu.pharmacie.commun.facturation.DepotLettreGarantie;
import com.csu.pharmacie.commun.facturation.DepotPieceDossier;
import com.csu.pharmacie.commun.facturation.Numerotation;
import com.csu.pharmacie.commun.facturation.ParametresFacturation;
import com.csu.pharmacie.commun.facturation.ReglesFacturation;
import com.csu.pharmacie.commun.facturation.ServiceFacturation;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Le service régional supprime une facture de SA région, ou un patient / une prestation qu'elle
 * contient — brouillon compris (23/10/2026) — mais ni hors de sa région, ni une fois la facture
 * validée par le niveau central. Et quel que soit l'auteur du retrait, une facture vidée de sa
 * dernière prestation est supprimée, sauf si un patient y attend encore les siennes.
 */
class SuppressionServiceRegionalTest {

    private static final String FACTURE = "facture-1";

    private final DepotFactureStructure depot = mock(DepotFactureStructure.class);
    private final DepotPieceDossier pieces = mock(DepotPieceDossier.class);
    private final ClientReferentiel referentiel = mock(ClientReferentiel.class);
    private final ServiceFacturation service = new ServiceFacturation(depot,
            new ReglesFacturation(new ParametresFacturation()), mock(ClientIdentite.class), referentiel,
            mock(ClientPharmacie.class), mock(Numerotation.class), mock(GenerateurPdf.class),
            mock(DepotLettreGarantie.class), mock(DepotFeuilleSoins.class), pieces, mock(ServiceCarteCsu.class));

    @BeforeEach
    void preparer() {
        when(depot.save(any(FactureStructure.class))).thenAnswer(appel -> appel.getArgument(0));
        connecter("region-thies");
    }

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    private static void connecter(String regionId) {
        authentifier(UtilisateurAuthentifie.builder()
                .id("sr-1").prenom("Service").nom("Régional").role(Role.SERVICE_REGIONAL).regionId(regionId).build());
    }

    /** L'agent du poste qui a saisi la facture. */
    private static void connecterPoste() {
        authentifier(UtilisateurAuthentifie.builder()
                .id("agent-1").prenom("Agent").nom("Poste").role(Role.POSTE_SANTE).structureId("poste-1").build());
    }

    private static void authentifier(UtilisateurAuthentifie utilisateur) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(utilisateur, null, List.of()));
    }

    /** Facture contributive d'un poste de Thiès : deux patients, une prestation chacun (1 000 F et 3 000 F). */
    private FactureStructure facture(StatutFacture statut) {
        FactureStructure facture = FactureStructure.builder()
                .id(FACTURE).numero("FS-202610-00001").structureSanitaireId("poste-1").regionId("region-thies")
                .regime(Regime.CONTRIBUTIF).mois(10).annee(2026).statut(statut).build();
        facture.getDossiers().add(dossier("dossier-diop", "DIOP"));
        facture.getDossiers().add(dossier("dossier-fall", "FALL"));
        facture.getLignes().add(ligne("dossier-diop", "DIOP", 1000));
        facture.getLignes().add(ligne("dossier-fall", "FALL", 3000));
        when(depot.findById(FACTURE)).thenReturn(Optional.of(facture));
        return facture;
    }

    private static DossierPatient dossier(String id, String nom) {
        return DossierPatient.builder().id(id).statut(DossierPatient.COMPLET).nom(nom).prenom("Awa").build();
    }

    private static LigneFactureStructure ligne(String dossierId, String nom, double prix) {
        LigneFactureStructure ligne = new LigneFactureStructure();
        ligne.setDossierId(dossierId);
        ligne.setPatientNom(nom);
        ligne.setPatientPrenom("Awa");
        ligne.setDesignation("Consultation");
        ligne.setQuantite(1);
        ligne.setPrixUnitaire(prix);
        return ligne;
    }

    @Test
    void supprimeUnBrouillonDeSaRegion() {
        FactureStructure facture = facture(StatutFacture.BROUILLON);

        service.supprimer(FACTURE);

        verify(pieces).deleteByFactureId(FACTURE);
        verify(depot).delete(facture);
    }

    @Test
    void supprimeUneFactureDejaTransmise() {
        FactureStructure facture = facture(StatutFacture.ENVOYEE);

        service.supprimer(FACTURE);

        verify(depot).delete(facture);
    }

    @Test
    void refuseUneFactureDUneAutreRegion() {
        facture(StatutFacture.BROUILLON);
        connecter("region-dakar");

        ExceptionMetier erreur = assertThrows(ExceptionMetier.class, () -> service.supprimer(FACTURE));

        assertEquals(HttpStatus.FORBIDDEN, erreur.getStatut());
        verify(depot, never()).delete(any(FactureStructure.class));
    }

    @Test
    void refuseUneFactureValideeParLeNiveauCentral() {
        facture(StatutFacture.VALIDEE_NC);

        ExceptionMetier erreur = assertThrows(ExceptionMetier.class, () -> service.supprimerLigne(FACTURE, 0));

        assertEquals(HttpStatus.CONFLICT, erreur.getStatut());
        verify(depot, never()).save(any(FactureStructure.class));
    }

    @Test
    void retireUnPatientEtRecalculeSansRetarifer() {
        facture(StatutFacture.ENVOYEE);

        FactureStructure resultat = service.supprimerDossier(FACTURE, "dossier-diop");

        assertEquals(1, resultat.getLignes().size());
        assertEquals(1, resultat.getDossiers().size());
        assertEquals(3000, resultat.getMontantTotal());
        assertEquals(2400, resultat.getMontantTotalSencsu());
        verify(pieces).deleteByDossierId("dossier-diop");
        // Les prix de la facture transmise restent ceux de la saisie.
        verifyNoInteractions(referentiel);
    }

    @Test
    void supprimeUnePrestationDUnBrouillon() {
        facture(StatutFacture.BROUILLON);

        FactureStructure resultat = service.supprimerLigne(FACTURE, 0);

        assertEquals(1, resultat.getLignes().size());
        assertEquals("FALL", resultat.getLignes().get(0).getPatientNom());
        assertEquals(3000, resultat.getMontantTotal());
    }

    // ─────────────── facture vidée de sa dernière prestation ───────────────

    @Test
    void retirerLeDernierPatientSupprimeLaFacture() {
        FactureStructure facture = facture(StatutFacture.ENVOYEE);

        service.supprimerDossier(FACTURE, "dossier-diop");
        FactureStructure resultat = service.supprimerDossier(FACTURE, "dossier-fall");

        assertNull(resultat);
        verify(pieces).deleteByFactureId(FACTURE);
        verify(depot).delete(facture);
    }

    @Test
    void lePosteQuiRetireSaDernierePrestationSupprimeSonBrouillon() {
        FactureStructure facture = facture(StatutFacture.BROUILLON);
        connecterPoste();

        service.supprimerLigne(FACTURE, 0);
        FactureStructure resultat = service.supprimerLigne(FACTURE, 0);

        assertNull(resultat);
        verify(depot).delete(facture);
    }

    @Test
    void unPatientEncoreACompleterGardeLaFacture() {
        FactureStructure facture = facture(StatutFacture.BROUILLON);
        facture.getDossiers().add(DossierPatient.builder()
                .id("dossier-ndiaye").statut(DossierPatient.A_COMPLETER).nom("NDIAYE").prenom("Moussa").build());

        service.supprimerLigne(FACTURE, 0);
        FactureStructure resultat = service.supprimerLigne(FACTURE, 0);

        assertNotNull(resultat);
        assertTrue(resultat.getLignes().isEmpty());
        assertEquals(0, resultat.getMontantTotal());
        verify(depot, never()).delete(any(FactureStructure.class));
    }
}
