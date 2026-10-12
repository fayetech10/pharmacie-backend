package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.commun.carte.ServiceCarteCsu;
import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.ClientPharmacie;
import com.csu.pharmacie.commun.client.ClientReferentiel;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LettreGarantie;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.PieceDossier;
import com.csu.pharmacie.entity.RapportRapprochement;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Application de l'import de fin de mois d'un centre (étape 2 de la facturation CS / EPS) :
 * les prestations importées d'un patient sont <b>remplacées</b> à chaque import — jamais
 * additionnées —, celles saisies à l'écran ne bougent pas, et les montants passent par les
 * règles de facturation du régime.
 */
class ImportFinDeMoisTest {

    private static final LocalDate LE_5_OCTOBRE = LocalDate.of(2026, 10, 5);

    private final DepotFactureStructure depot = mock(DepotFactureStructure.class);
    private final DepotLettreGarantie depotLettres = mock(DepotLettreGarantie.class);
    private final ServiceFacturation service = new ServiceFacturation(depot,
            new ReglesFacturation(new ParametresFacturation()), mock(ClientIdentite.class),
            mock(ClientReferentiel.class), mock(ClientPharmacie.class), mock(Numerotation.class),
            mock(GenerateurPdf.class), depotLettres, mock(DepotFeuilleSoins.class), mock(DepotPieceDossier.class),
            mock(ServiceCarteCsu.class));
    private FactureStructure facture;

    @BeforeEach
    void preparer() {
        DossierPatient dossier = DossierPatient.builder()
                .id("D1").nom("Ndiaye").prenom("Ousmane").numeroCni("1751199001234")
                .dateNaissance(LocalDate.of(1950, 1, 1)).datePriseEnCharge(LE_5_OCTOBRE)
                .lettreGarantieId("L-1").lettreGarantieNumero("G-1").identiteVerrouillee(true)
                .statut(DossierPatient.A_COMPLETER).build();
        facture = FactureStructure.builder()
                .id("F-1").numero("FS-202610-00001").structureSanitaireId("CS-1")
                .regime(Regime.CONTRIBUTIF).mois(10).annee(2026).statut(StatutFacture.BROUILLON)
                .dossiers(new ArrayList<>(List.of(dossier)))
                .lignes(new ArrayList<>(List.of(
                        // Saisie à l'écran : ne doit jamais être touchée par l'import.
                        LigneFactureStructure.builder().dossierId("D1").designation("Pansement")
                                .datePriseEnCharge(LE_5_OCTOBRE).quantite(1).prixUnitaire(500).build())))
                .historique(new ArrayList<>())
                .build();
        when(depot.findById("F-1")).thenReturn(Optional.of(facture));
        when(depot.save(any(FactureStructure.class))).thenAnswer(appel -> appel.getArgument(0));
        when(depotLettres.findAllById(anyIterable()))
                .thenReturn(List.of(LettreGarantie.builder().id("L-1").patientId("P-1").build()));

        UtilisateurAuthentifie agent = UtilisateurAuthentifie.builder()
                .id("agent-1").prenom("Agent").nom("Centre").role(Role.CENTRE_SANTE).structureId("CS-1").build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(agent, null, List.of()));
    }

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void lImportCompleteLeDossierAvecLIdentiteDuBcsuEtLesPartsDuRegime() {
        service.appliquerImport("F-1", Map.of("D1", List.of(prestation("Consultation", 1, 2000))), rapport());

        LigneFactureStructure importee = importees().get(0);
        assertEquals(LigneFactureStructure.ORIGINE_IMPORT, importee.getOrigine());
        assertEquals("Ousmane", importee.getPatientPrenom(), "l'identité vient du dossier, pas du fichier");
        assertEquals("G-1", importee.getLettreGarantieNumero());
        assertEquals("P-1", importee.getPatientId());
        assertEquals(400, importee.getMontantBeneficiaire(), 0.001, "Classique : 20 % à la charge du patient");
        assertEquals(1600, importee.getMontantSencsu(), 0.001);
        assertEquals(DossierPatient.A_COMPLETER, facture.getDossiers().get(0).getStatut(),
                "sans ticket de caisse, le dossier du BCSU reste à compléter");
        assertNotNull(facture.getDernierRapprochement(), "le rapport est conservé sur la facture");
        assertEquals(2500, facture.getMontantTotal(), 0.001);
    }

    @Test
    void avecSonTicketDeCaisseLeDossierImporteEstComplet() {
        facture.getDossiers().get(0).setPieces(new ArrayList<>(List.of(PieceDossier.TICKET_CAISSE)));

        service.appliquerImport("F-1", Map.of("D1", List.of(prestation("Consultation", 1, 2000))), rapport());

        assertEquals(DossierPatient.COMPLET, facture.getDossiers().get(0).getStatut());
    }

    @Test
    void reimporterRemplaceLesPrestationsImporteesSansToucherALaSaisieEcran() {
        service.appliquerImport("F-1", Map.of("D1", List.of(prestation("Consultation", 1, 2000),
                prestation("Radio", 1, 5000))), rapport());
        service.appliquerImport("F-1", Map.of("D1", List.of(prestation("Consultation", 1, 2000))), rapport());

        assertEquals(1, importees().size(), "le second import remplace le premier");
        assertEquals("Consultation", importees().get(0).getDesignation());
        assertEquals(1, facture.getLignes().stream().filter(l -> "Pansement".equals(l.getDesignation())).count(),
                "la prestation saisie à l'écran est conservée");
        assertEquals(2500, facture.getMontantTotal(), 0.001);
    }

    @Test
    void uneFactureTransmiseNeSeCompletePlus() {
        facture.setStatut(StatutFacture.ENVOYEE);
        Map<String, List<LigneFactureStructure>> lignes = Map.of("D1", List.of(prestation("Consultation", 1, 2000)));
        RapportRapprochement rapport = rapport();
        assertThrows(ExceptionMetier.class, () -> service.appliquerImport("F-1", lignes, rapport));
    }

    private List<LigneFactureStructure> importees() {
        return facture.getLignes().stream()
                .filter(l -> LigneFactureStructure.ORIGINE_IMPORT.equals(l.getOrigine()))
                .toList();
    }

    private static LigneFactureStructure prestation(String designation, int quantite, double prix) {
        return LigneFactureStructure.builder()
                .datePriseEnCharge(LE_5_OCTOBRE).designation(designation).quantite(quantite).prixUnitaire(prix).build();
    }

    private static RapportRapprochement rapport() {
        RapportRapprochement rapport = new RapportRapprochement();
        rapport.setFichier("octobre.xlsx");
        return rapport;
    }
}
