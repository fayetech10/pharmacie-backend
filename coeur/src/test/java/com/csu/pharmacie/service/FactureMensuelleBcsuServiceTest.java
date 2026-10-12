package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LettreGarantie;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.PieceDossier;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.StatutLettreGarantie;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.TypeStructure;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.BusinessException;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.repository.DistrictRepository;
import com.csu.pharmacie.repository.FactureStructureRepository;
import com.csu.pharmacie.repository.PieceDossierRepository;
import com.csu.pharmacie.utils.CleRapprochement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Étape 1 de la facturation CS / EPS : la saisie du BCSU devient un dossier « à compléter »
 * de la facture du mois de sa structure. Ce qui est vérifié ici, c'est ce qui fera ou défera
 * le rapprochement avec l'import de fin de mois : la bonne facture, la bonne clé, une seule
 * ligne par visite — et un patient qu'on ne peut plus retirer une fois facturé.
 */
class FactureMensuelleBcsuServiceTest {

    private static final LocalDate LE_5_OCTOBRE = LocalDate.of(2026, 10, 5);

    /** Factures « en base », par identifiant. */
    private final Map<String, FactureStructure> factures = new LinkedHashMap<>();
    private PieceDossierRepository pieceRepository;
    private FactureMensuelleBcsuService service;
    private long compteurNumero;

    private final User agent = User.builder().id("BCSU-1").prenom("Awa").nom("Diop").build();
    private final StructureSanitaire centre = structure("CS-1", "CS");

    @BeforeEach
    void setUp() {
        FactureStructureRepository factureRepository = mock(FactureStructureRepository.class);
        pieceRepository = mock(PieceDossierRepository.class);
        CompteurFactureService compteur = mock(CompteurFactureService.class);

        when(factureRepository.save(any(FactureStructure.class))).thenAnswer(invocation -> {
            FactureStructure facture = invocation.getArgument(0);
            if (facture.getId() == null) {
                facture.setId(UUID.randomUUID().toString());
            }
            factures.put(facture.getId(), facture);
            return facture;
        });
        when(factureRepository.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(factures.get(invocation.<String>getArgument(0))));
        when(factureRepository.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(anyString(), any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> factures.values().stream()
                        .filter(f -> f.getStructureSanitaireId().equals(invocation.getArgument(0))
                                && f.getRegime() == invocation.getArgument(1)
                                && f.getMois() == invocation.<Integer>getArgument(2)
                                && f.getAnnee() == invocation.<Integer>getArgument(3))
                        .toList());
        when(pieceRepository.findFirstByDossierIdAndType(anyString(), anyString())).thenReturn(Optional.empty());
        when(compteur.incrementer(anyString())).thenAnswer(invocation -> ++compteurNumero);

        service = new FactureMensuelleBcsuService(factureRepository, pieceRepository,
                mock(DistrictRepository.class), compteur);
    }

    @Test
    void laSaisieOuvreLaFactureDuMoisAvecUnDossierACompleter() {
        LettreGarantie lettre = lettreSesame("L-1", "1 751 1990 01234", LE_5_OCTOBRE);
        lettre.setCniRecto("data:image/jpeg;base64,AAAA");

        service.rattacher(lettre, centre, agent);

        FactureStructure facture = seuleFacture();
        assertEquals("FS-202610-00001", facture.getNumero(), "numérotation partagée avec le socle");
        assertEquals(Regime.SESAME, facture.getRegime());
        assertEquals(10, facture.getMois());
        assertEquals(StatutFacture.BROUILLON, facture.getStatut());
        assertEquals(TypeStructure.CS, facture.getTypeStructure());
        assertEquals("D-1", facture.getDistrictId());

        DossierPatient dossier = facture.getDossiers().get(0);
        assertEquals(DossierPatient.A_COMPLETER, dossier.getStatut());
        assertEquals("1751199001234", dossier.getCleRapprochement(), "clé normalisée, sans espaces");
        assertEquals(LE_5_OCTOBRE, dossier.getDatePriseEnCharge());
        assertEquals("L-1", dossier.getLettreGarantieId());
        assertTrue(dossier.isIdentiteVerrouillee(), "la lettre fait foi pour l'identité");
        assertEquals(List.of(PieceDossier.CNI_RECTO), dossier.getPieces());
        verify(pieceRepository).save(any(PieceDossier.class));

        assertEquals(facture.getId(), lettre.getFactureStructureId());
        assertEquals(dossier.getId(), lettre.getDossierId());
    }

    @Test
    void uneSecondeSaisieDuMemeMoisRejointLaMemeFacture() {
        service.rattacher(lettreSesame("L-1", "1751199001234", LE_5_OCTOBRE), centre, agent);
        service.rattacher(lettreSesame("L-2", "2751199001234", LE_5_OCTOBRE.plusDays(3)), centre, agent);

        assertEquals(2, seuleFacture().getDossiers().size());
    }

    @Test
    void laMemeVisiteNePeutPasEtreSaisieDeuxFois() {
        service.rattacher(lettreSesame("L-1", "1751199001234", LE_5_OCTOBRE), centre, agent);

        LettreGarantie doublon = lettreSesame("L-2", "1751 1990 01234", LE_5_OCTOBRE);
        ConflictException erreur = assertThrows(ConflictException.class, () -> service.rattacher(doublon, centre, agent));
        assertTrue(erreur.getMessage().contains("G-L-1"), "le message désigne la lettre existante");
        assertEquals(1, seuleFacture().getDossiers().size());
    }

    @Test
    void leMemePatientRevenuUnAutreJourFaitUneSecondeLigne() {
        service.rattacher(lettreSesame("L-1", "1751199001234", LE_5_OCTOBRE), centre, agent);
        service.rattacher(lettreSesame("L-2", "1751199001234", LE_5_OCTOBRE.plusDays(1)), centre, agent);

        assertEquals(2, seuleFacture().getDossiers().size());
    }

    @Test
    void deuxEnfantsDuMemeAccompagnantLeMemeJourFontDeuxLignes() {
        service.rattacher(lettreEnfant("L-1", "Fatou", "EXT-001"), centre, agent);
        service.rattacher(lettreEnfant("L-2", "Moussa", "EXT-002"), centre, agent);

        FactureStructure facture = seuleFacture();
        assertEquals(2, facture.getDossiers().size());
        DossierPatient dossier = facture.getDossiers().get(0);
        assertEquals("1751199001234", dossier.getCleRapprochement(), "clé = CNI de l'accompagnant");
        assertEquals("EXT-001", dossier.getMatricule(), "le matricule d'un enfant est son n° d'extrait");
        assertEquals("Mariama Sow", dossier.getNomAccompagnant());

        assertThrows(ConflictException.class, () -> service.rattacher(lettreEnfant("L-3", "Fatou", "EXT 001"), centre, agent),
                "même accompagnant, même jour, même enfant : doublon");
    }

    @Test
    void desJumeauxSansExtraitSeDistinguentParLeurPrenom() {
        service.rattacher(lettreEnfant("L-1", "Awa", null), centre, agent);
        service.rattacher(lettreEnfant("L-2", "Adama", null), centre, agent);

        assertEquals(2, seuleFacture().getDossiers().size());
        assertThrows(ConflictException.class, () -> service.rattacher(lettreEnfant("L-3", "AWA", null), centre, agent));
    }

    @Test
    void unPosteDeSanteOuUnRegimeHorsModuleNeSontPasConcernes() {
        service.rattacher(lettreSesame("L-1", "1751199001234", LE_5_OCTOBRE), structure("PS-1", "PS"), agent);
        LettreGarantie dialyse = lettreSesame("L-2", "1751199001234", LE_5_OCTOBRE);
        dialyse.setRegime(Regime.HEMODIALYSE);
        service.rattacher(dialyse, centre, agent);

        assertTrue(factures.isEmpty());
        assertNull(dialyse.getFactureStructureId());
    }

    @Test
    void lesPrisesEnChargeAvantLaMiseEnServiceGardentLAncienCircuit() {
        ReflectionTestUtils.setField(service, "aPartirDe", "2026-11");

        service.rattacher(lettreSesame("L-1", "1751199001234", LE_5_OCTOBRE), centre, agent);
        assertTrue(factures.isEmpty());

        service.rattacher(lettreSesame("L-2", "1751199001234", LocalDate.of(2026, 11, 2)), centre, agent);
        assertEquals(1, factures.size());
    }

    @Test
    void uneFactureDejaTransmiseNeRecoitPlusDePatient() {
        service.rattacher(lettreSesame("L-1", "1751199001234", LE_5_OCTOBRE), centre, agent);
        seuleFacture().setStatut(StatutFacture.ENVOYEE);

        assertThrows(BusinessException.class,
                () -> service.rattacher(lettreSesame("L-2", "2751199001234", LE_5_OCTOBRE), centre, agent));
    }

    @Test
    void changerLaDateDeMoisDeplaceLePatientDansLaFactureDeSonMois() {
        LettreGarantie lettre = lettreSesame("L-1", "1751199001234", LE_5_OCTOBRE);
        service.rattacher(lettre, centre, agent);
        String octobre = lettre.getFactureStructureId();

        lettre.setDatePriseEnCharge(LocalDate.of(2026, 11, 2));
        service.rattacher(lettre, centre, agent);

        assertTrue(factures.get(octobre).getDossiers().isEmpty(), "le patient a quitté octobre");
        FactureStructure novembre = factures.get(lettre.getFactureStructureId());
        assertEquals(11, novembre.getMois());
        assertEquals(1, novembre.getDossiers().size());
    }

    @Test
    void lAnnulationRetireLePatientTantQuIlNAPasEteFacture() {
        LettreGarantie lettre = lettreSesame("L-1", "1751199001234", LE_5_OCTOBRE);
        service.rattacher(lettre, centre, agent);
        FactureStructure facture = seuleFacture();
        String dossierId = lettre.getDossierId();

        service.detacher(lettre, agent, "annulée");

        assertTrue(facture.getDossiers().isEmpty());
        verify(pieceRepository).deleteByDossierId(eq(dossierId));
        assertNull(lettre.getFactureStructureId());
    }

    @Test
    void lAnnulationEstRefuseeQuandLaStructureAFactureLePatient() {
        LettreGarantie lettre = lettreSesame("L-1", "1751199001234", LE_5_OCTOBRE);
        service.rattacher(lettre, centre, agent);
        FactureStructure facture = seuleFacture();
        facture.getLignes().add(LigneFactureStructure.builder().dossierId(lettre.getDossierId())
                .designation("Consultation").quantite(1).build());

        BusinessException erreur = assertThrows(BusinessException.class, () -> service.detacher(lettre, agent, "annulée"));
        assertTrue(erreur.getMessage().contains("1 prestation"));
        assertEquals(1, facture.getDossiers().size());
        verify(pieceRepository, never()).deleteByDossierId(anyString());
    }

    @Test
    void leDossierNEstCompletQuAvecPrestationsEtTicketMemeApresUneModificationDeLaLettre() {
        LettreGarantie lettre = lettreSesame("L-1", "1751199001234", LE_5_OCTOBRE);
        service.rattacher(lettre, centre, agent);
        FactureStructure facture = seuleFacture();
        DossierPatient dossier = facture.getDossiers().get(0);
        facture.getLignes().add(LigneFactureStructure.builder().dossierId(dossier.getId())
                .designation("Consultation").quantite(1).build());

        service.rattacher(lettre, centre, agent);
        assertEquals(DossierPatient.A_COMPLETER, dossier.getStatut(), "des prestations, mais pas de ticket");

        dossier.getPieces().add(PieceDossier.TICKET_CAISSE);
        service.rattacher(lettre, centre, agent);
        assertEquals(DossierPatient.COMPLET, dossier.getStatut());
        assertTrue(dossier.possedePiece(PieceDossier.TICKET_CAISSE), "la saisie du BCSU ne touche pas au ticket de la structure");
    }

    @Test
    void laCleIgnoreEspacesSeparateursEtCasse() {
        assertEquals("V8658Y2605", CleRapprochement.normaliser(" v8658-y2605 "));
        assertNull(CleRapprochement.normaliser(" - "));
        assertEquals("1751199001234", CleRapprochement.identifiant(Regime.CESARIENNE, null, "1 751 1990 01234", null));
        assertNull(CleRapprochement.identifiant(Regime.NDONGO_DARA, "V8658Y2605", null, null),
                "Ndongo Daara n'est pas du module");
    }

    // ══════════════════════════ jeux d'essai ══════════════════════════

    private FactureStructure seuleFacture() {
        assertEquals(1, factures.size());
        return factures.values().iterator().next();
    }

    private static StructureSanitaire structure(String id, String type) {
        StructureSanitaire structure = new StructureSanitaire();
        structure.setId(id);
        structure.setNom("Structure " + id);
        structure.setTypeStructure(type);
        structure.setDistrictId("D-1");
        structure.setRegionId("R-1");
        return structure;
    }

    private static LettreGarantie lettreSesame(String id, String cni, LocalDate date) {
        return LettreGarantie.builder()
                .id(id)
                .numero("G-" + id)
                .regime(Regime.SESAME)
                .statut(StatutLettreGarantie.BROUILLON)
                .patientNom("Ndiaye")
                .patientPrenom("Ousmane")
                .patientSexe("M")
                .patientDateNaissance(LocalDate.of(1950, 1, 1))
                .cniNumeroOcr(cni)
                .datePriseEnCharge(date)
                .historique(new ArrayList<>())
                .build();
    }

    private static LettreGarantie lettreEnfant(String id, String prenom, String extrait) {
        return LettreGarantie.builder()
                .id(id)
                .numero("G-" + id)
                .regime(Regime.ZERO_CINQ_ANS)
                .statut(StatutLettreGarantie.BROUILLON)
                .patientNom("Sow")
                .patientPrenom(prenom)
                .patientDateNaissance(LocalDate.of(2024, 3, 1))
                .accompagnantNom("Mariama Sow")
                .accompagnantCni("1 751 1990 01234")
                .numeroExtraitNaissance(extrait)
                .datePriseEnCharge(LE_5_OCTOBRE)
                .historique(new ArrayList<>())
                .build();
    }
}
