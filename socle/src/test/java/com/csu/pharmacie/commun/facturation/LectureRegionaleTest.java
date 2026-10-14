package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.commun.carte.ServiceCarteCsu;
import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.ClientPharmacie;
import com.csu.pharmacie.commun.client.ClientReferentiel;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Le service régional ne lit — ni ne contrôle — que les factures de SA région (14/10/2026) :
 * région figée sur la facture, à défaut celle de sa structure. Connaître l'identifiant d'une
 * facture d'une autre région ne suffit plus ; le niveau central garde la vue nationale.
 */
class LectureRegionaleTest {

    private static final String THIES = "region-thies";
    private static final String DAKAR = "region-dakar";

    private final DepotFactureStructure depot = mock(DepotFactureStructure.class);
    private final ClientIdentite clientIdentite = mock(ClientIdentite.class);
    private final DepotPieceDossier depotPieces = mock(DepotPieceDossier.class);
    private final ServiceFacturation service = new ServiceFacturation(depot,
            new ReglesFacturation(new ParametresFacturation()), clientIdentite, mock(ClientReferentiel.class),
            mock(ClientPharmacie.class), mock(Numerotation.class), mock(GenerateurPdf.class),
            mock(DepotLettreGarantie.class), mock(DepotFeuilleSoins.class), depotPieces,
            mock(ServiceCarteCsu.class));

    @BeforeEach
    void preparer() {
        when(clientIdentite.structure("cs-thies")).thenReturn(StructureDto.builder()
                .id("cs-thies").regionId(THIES).build());
        when(clientIdentite.structure("cs-dakar")).thenReturn(StructureDto.builder()
                .id("cs-dakar").regionId(DAKAR).build());
        when(depot.save(any(FactureStructure.class))).thenAnswer(appel -> appel.getArgument(0));
        connecter(Role.SERVICE_REGIONAL, THIES);
    }

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════ lecture ══════════════════════════

    @Test
    void leServiceRegionalLitUneFactureDeSaRegion() {
        FactureStructure facture = facture("F-THIES", "cs-thies", THIES);
        assertSame(facture, service.parId("F-THIES"));
    }

    @Test
    void uneFactureDUneAutreRegionEstRefusee() {
        facture("F-DAKAR", "cs-dakar", DAKAR);

        refuse(() -> service.parId("F-DAKAR"));
        // Pièces et documents passent par la même porte : rien n'est lu.
        refuse(() -> service.piece("F-DAKAR", "D1", "PHOTO_ASSURE"));
        refuse(() -> service.tickets("F-DAKAR", "D1"));
        refuse(() -> service.nomFichierFacture("F-DAKAR", "Facture", "pdf"));
        verifyNoInteractions(depotPieces);
    }

    @Test
    void uneFactureSansRegionEstRattrapeeParSaStructure() {
        FactureStructure thies = facture("F-COEUR-THIES", "cs-thies", null);
        facture("F-COEUR-DAKAR", "cs-dakar", null);

        assertSame(thies, service.parId("F-COEUR-THIES"));
        refuse(() -> service.parId("F-COEUR-DAKAR"));
    }

    @Test
    void laRegionFigeeSurLaFacturePrimeSurCelleDeLaStructure() {
        // Structure passée depuis à Dakar : l'historique reste à Thiès.
        facture("F-HISTO", "cs-dakar", THIES);
        assertNotNull(service.parId("F-HISTO"));
        verify(clientIdentite, never()).structure(anyString());
    }

    @Test
    void unServiceRegionalSansRegionNeVoitRien() {
        connecter(Role.SERVICE_REGIONAL, null);
        facture("F-THIES", "cs-thies", THIES);

        refuse(() -> service.parId("F-THIES"));
        assertTrue(service.mesFactures(null, null, null, null).isEmpty(), "pas la vue nationale");
        verify(depot, never()).findAll();
    }

    @Test
    void leNiveauCentralGardeLaVueNationale() {
        connecter(Role.SERVICE_CENTRAL, null);
        FactureStructure dakar = facture("F-DAKAR", "cs-dakar", DAKAR);
        assertSame(dakar, service.parId("F-DAKAR"));

        connecter(Role.ADMIN, null);
        assertSame(dakar, service.parId("F-DAKAR"));
    }

    // ══════════════════════════ contrôle ══════════════════════════

    @Test
    void leServiceRegionalNeControleQueSaRegion() {
        facture("F-DAKAR", "cs-dakar", DAKAR);
        refuse(() -> service.validerParServiceRegional("F-DAKAR", null));
        refuse(() -> service.rejeterParServiceRegional("F-DAKAR", "Hors périmètre"));
        refuse(() -> service.pointerDossiers("F-DAKAR", List.of("D1"), true));
        refuse(() -> service.rejeterDossiers("F-DAKAR", List.of("D1"), "Motif", true));
        verify(depot, never()).save(any(FactureStructure.class));
    }

    @Test
    void uneFactureDuCoeurDeSaRegionPeutEtreValidee() {
        // Avant : sans région figée, la facture était refusée à son propre service régional.
        facture("F-COEUR-THIES", "cs-thies", null);
        assertEquals(StatutFacture.VALIDEE_SR,
                service.validerParServiceRegional("F-COEUR-THIES", null).getStatut());
    }

    // ══════════════════════════ jeux d'essai ══════════════════════════

    private static void refuse(Executable appel) {
        ExceptionMetier refus = assertThrows(ExceptionMetier.class, appel);
        assertEquals(HttpStatus.FORBIDDEN, refus.getStatut(), refus.getMessage());
    }

    private static void connecter(Role role, String regionId) {
        UtilisateurAuthentifie utilisateur = UtilisateurAuthentifie.builder()
                .id("u-1").prenom("Service").nom("Régional").role(role).regionId(regionId).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(utilisateur, null, List.of()));
    }

    private FactureStructure facture(String id, String structureId, String regionId) {
        FactureStructure facture = FactureStructure.builder()
                .id(id)
                .numero("FS-202610-" + id)
                .structureSanitaireId(structureId)
                .regionId(regionId)
                .regime(Regime.SESAME)
                .mois(10)
                .annee(2026)
                .statut(StatutFacture.ENVOYEE)
                .dossiers(new ArrayList<>())
                .lignes(new ArrayList<>())
                .historique(new ArrayList<>())
                .build();
        when(depot.findById(id)).thenReturn(Optional.of(facture));
        return facture;
    }
}
