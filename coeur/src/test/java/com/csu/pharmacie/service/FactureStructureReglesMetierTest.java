package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.FactureStructureRequest;
import com.csu.pharmacie.dto.LigneFactureStructureDto;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.BusinessException;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.repository.FactureStructureRepository;
import com.csu.pharmacie.repository.LettreGarantieRepository;
import com.csu.pharmacie.repository.PatientRepository;
import com.csu.pharmacie.repository.StructureSanitaireRepository;
import com.csu.pharmacie.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Règles métier de la facturation des structures, vérifiées sur le service réel
 * (dépendances mockées) :
 *  - le forfait « Gratuité 0-5 ans » au niveau POSTE DE SANTÉ est imposé par le serveur,
 *    quel que soit le prix envoyé par le client ;
 *  - les transitions de statut du circuit poste → centre de santé (validation, rejet)
 *    et leurs garde-fous (motif obligatoire, statut de départ, poste réellement rattaché).
 *
 * Ces règles portent sur des montants payés et sur qui peut valider quoi : une régression
 * silencieuse y coûte cher, d'où leur couverture ici plutôt qu'un contrôle manuel.
 */
class FactureStructureReglesMetierTest {

    private static final String EMAIL_POSTE = "poste@test.sn";
    private static final String EMAIL_CENTRE = "centre@test.sn";
    private static final String ID_POSTE = "PS-1";
    private static final String ID_CENTRE = "CS-1";

    private FactureStructureRepository factureRepository;
    private StructureSanitaireRepository structureRepository;
    private UserRepository userRepository;
    private LettreGarantieRepository lettreRepository;
    private FactureStructureService service;

    @BeforeEach
    void setUp() {
        factureRepository = mock(FactureStructureRepository.class);
        structureRepository = mock(StructureSanitaireRepository.class);
        userRepository = mock(UserRepository.class);
        lettreRepository = mock(LettreGarantieRepository.class);

        service = new FactureStructureService(
                factureRepository,
                lettreRepository,
                structureRepository,
                userRepository,
                mock(FeuilleSoinsService.class),
                mock(PatientRepository.class),
                mock(FactureMensuelleBcsuService.class));

        // La facture enregistrée est renvoyée telle quelle : on inspecte ce que le service a construit.
        when(factureRepository.save(any(FactureStructure.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(factureRepository.findByNumero(anyString())).thenReturn(Optional.empty());
        when(factureRepository.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----- Fixtures -----

    /** Authentifie un utilisateur rattaché à une structure (le principal JWT est l'email). */
    private void connecter(String email, String structureId) {
        User user = User.builder()
                .id("U-" + structureId)
                .email(email)
                .role(Role.STRUCTURE_SANITAIRE)
                .structureSanitaireId(structureId)
                .actif(true)
                .build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    /** Déclare une structure : « PS » = poste de santé, rattaché ou non à un centre. */
    private void declarerStructure(String id, String type, String rattachementId) {
        when(structureRepository.findById(id)).thenReturn(Optional.of(StructureSanitaire.builder()
                .id(id)
                .nom("Structure " + id)
                .code(id)
                .typeStructure(type)
                .structureRattachementId(rattachementId)
                .regionId("R1")
                .actif(true)
                .build()));
    }

    /** Saisie d'un patient avec un prix unitaire librement choisi par le client. */
    private FactureStructureRequest saisie(Regime regime, double prixUnitaire) {
        LigneFactureStructureDto ligne = new LigneFactureStructureDto();
        ligne.setDesignation("Consultation générale");
        ligne.setDatePriseEnCharge(LocalDate.of(2026, 8, 10));
        ligne.setQuantite(1);
        ligne.setPrixUnitaire(prixUnitaire);

        FactureStructureRequest request = new FactureStructureRequest();
        request.setRegime(regime);
        request.setPatientNom("Diop");
        request.setPatientPrenom("Awa");
        request.setPatientTelephone("770000000");
        request.setLignes(List.of(ligne));
        return request;
    }

    private FactureStructure factureDuPoste(StatutFacture statut) {
        return FactureStructure.builder()
                .id("F-1")
                .numero("FS-000001")
                .structureSanitaireId(ID_POSTE)
                .regionId("R1")
                .regime(Regime.ZERO_CINQ_ANS)
                .statut(statut)
                .mois(8)
                .annee(2026)
                .lignes(new ArrayList<>())
                .historique(new ArrayList<>())
                .build();
    }

    // ===== Forfait 0-5 ans au niveau poste de santé =====

    /**
     * Le prix unitaire envoyé par le client est ignoré : au niveau poste, la gratuité
     * 0-5 ans est facturée au forfait. Sans ce contrôle serveur, n'importe quel appel
     * API pourrait facturer le montant de son choix.
     */
    @Test
    void forfaitImposeAuPosteDeSantePourLaGratuiteZeroCinqAns() {
        connecter(EMAIL_POSTE, ID_POSTE);
        declarerStructure(ID_POSTE, "PS", ID_CENTRE);

        FactureStructure facture = service.creer(saisie(Regime.ZERO_CINQ_ANS, 9999));

        assertEquals(1, facture.getLignes().size());
        assertEquals(FactureStructureService.FORFAIT_POSTE_ZERO_CINQ_ANS,
                facture.getLignes().get(0).getPrixUnitaire(), 0.001,
                "le prix unitaire doit être ramené au forfait");
        assertEquals(FactureStructureService.FORFAIT_POSTE_ZERO_CINQ_ANS,
                facture.getLignes().get(0).getMontant(), 0.001,
                "le montant doit être recalculé sur le forfait, pas sur le prix envoyé");
        // Gratuité : la totalité reste à la charge de la SEN-CSU.
        assertEquals(0.0, facture.getLignes().get(0).getMontantBeneficiaire(), 0.001);
        assertEquals(FactureStructureService.FORFAIT_POSTE_ZERO_CINQ_ANS,
                facture.getLignes().get(0).getMontantSencsu(), 0.001);
    }

    /** Le forfait ne concerne QUE le régime 0-5 ans : les autres gardent leur tarification. */
    @Test
    void leForfaitNeSAppliquePasAuxAutresRegimes() {
        connecter(EMAIL_POSTE, ID_POSTE);
        declarerStructure(ID_POSTE, "PS", ID_CENTRE);

        FactureStructure facture = service.creer(saisie(Regime.SESAME, 5000));

        assertEquals(5000.0, facture.getLignes().get(0).getPrixUnitaire(), 0.001);
        assertEquals(5000.0, facture.getLignes().get(0).getMontant(), 0.001);
    }

    /**
     * Le forfait est propre au niveau POSTE : un centre de santé conserve sa tarification.
     * Un CS facture toujours à partir d'une lettre de garantie (contrairement au poste,
     * qui saisit le patient directement) — d'où la lettre montée ici.
     */
    @Test
    void leForfaitNeSAppliquePasAuCentreDeSante() {
        connecter(EMAIL_CENTRE, ID_CENTRE);
        declarerStructure(ID_CENTRE, "CS", null);
        declarerLettreEmise("LG-1", Regime.ZERO_CINQ_ANS);

        FactureStructureRequest request = saisie(Regime.ZERO_CINQ_ANS, 3000);
        request.setLettreGarantieNumero("LG-1");

        FactureStructure facture = service.creer(request);

        assertEquals(3000.0, facture.getLignes().get(0).getPrixUnitaire(), 0.001);
    }

    /** Lettre de garantie valide (émise, dans le délai de 30 jours) pour la facturation d'un CS. */
    private void declarerLettreEmise(String numero, Regime regime) {
        when(lettreRepository.findByNumero(numero)).thenReturn(Optional.of(
                com.csu.pharmacie.entity.LettreGarantie.builder()
                        .id("L-1")
                        .numero(numero)
                        .regime(regime)
                        .statut(com.csu.pharmacie.entity.StatutLettreGarantie.EMISE)
                        .patientNom("Diop")
                        .patientPrenom("Awa")
                        .patientTelephone("770000000")
                        .createdAt(java.time.LocalDateTime.now())
                        .build()));
    }

    // ===== Circuit poste → centre de santé =====

    /** Validation par le centre de santé : la facture part au service régional. */
    @Test
    void leCentreValideLaFactureDeSonPosteRattache() {
        connecter(EMAIL_CENTRE, ID_CENTRE);
        declarerStructure(ID_POSTE, "PS", ID_CENTRE);
        when(factureRepository.findById("F-1")).thenReturn(Optional.of(factureDuPoste(StatutFacture.SOUMISE_CS)));

        FactureStructure validee = service.validerPoste("F-1", null);

        assertEquals(StatutFacture.ENVOYEE, validee.getStatut());
    }

    /** Le rejet renvoie la facture au poste et exige un motif : il est transmis à l'agent. */
    @Test
    void leRejetExigeUnMotifEtLeConserve() {
        connecter(EMAIL_CENTRE, ID_CENTRE);
        declarerStructure(ID_POSTE, "PS", ID_CENTRE);
        when(factureRepository.findById("F-1")).thenReturn(Optional.of(factureDuPoste(StatutFacture.SOUMISE_CS)));

        assertThrows(BusinessException.class, () -> service.rejeterPoste("F-1", "  "),
                "un rejet sans motif doit être refusé");

        FactureStructure rejetee = service.rejeterPoste("F-1", "Montants à corriger");
        assertEquals(StatutFacture.REJETEE_CS, rejetee.getStatut());
        assertEquals("Montants à corriger", rejetee.getCommentaireRejet());
    }

    /** Une facture déjà transmise ne doit pas pouvoir être validée une seconde fois. */
    @Test
    void seulesLesFacturesSoumisesSontValidables() {
        connecter(EMAIL_CENTRE, ID_CENTRE);
        declarerStructure(ID_POSTE, "PS", ID_CENTRE);
        when(factureRepository.findById("F-1")).thenReturn(Optional.of(factureDuPoste(StatutFacture.ENVOYEE)));

        assertThrows(BusinessException.class, () -> service.validerPoste("F-1", null));
    }

    /**
     * Cloisonnement : un centre de santé ne peut pas valider la facture d'un poste
     * qui n'est pas rattaché à lui, même en connaissant son identifiant.
     */
    @Test
    void unCentreNePeutPasValiderLaFactureDUnPosteDUnAutreCentre() {
        connecter(EMAIL_CENTRE, ID_CENTRE);
        declarerStructure(ID_POSTE, "PS", "CS-AUTRE");
        when(factureRepository.findById("F-1")).thenReturn(Optional.of(factureDuPoste(StatutFacture.SOUMISE_CS)));

        assertThrows(ForbiddenException.class, () -> service.validerPoste("F-1", null));
    }

    // ===== Nouveau circuit (09/10/2026) : le poste n'envoie au district que le 0-5 ans =====

    private FactureStructure brouillonDuPoste(Regime regime) {
        return FactureStructure.builder().id("F-1").numero("FS-000001").structureSanitaireId(ID_POSTE)
                .regionId("R1").regime(regime).statut(StatutFacture.BROUILLON).mois(8).annee(2026)
                .lignes(new ArrayList<>()).historique(new ArrayList<>()).build();
    }

    /** Un poste transmet le régime « Enfants de moins de 5 ans » au district (SOUMISE_CS). */
    @Test
    void lePosteEnvoieLe0a5AnsAuDistrict() {
        connecter(EMAIL_POSTE, ID_POSTE);
        declarerStructure(ID_POSTE, "PS", ID_CENTRE);
        when(factureRepository.findById("F-1")).thenReturn(Optional.of(brouillonDuPoste(Regime.ZERO_CINQ_ANS)));

        assertEquals(StatutFacture.SOUMISE_CS, service.envoyer("F-1").getStatut());
    }

    /** Pour les autres régimes, un poste transmet directement au service régional (ENVOYEE). */
    @Test
    void lePosteEnvoieLesAutresRegimesAuServiceRegional() {
        connecter(EMAIL_POSTE, ID_POSTE);
        declarerStructure(ID_POSTE, "PS", ID_CENTRE);
        when(factureRepository.findById("F-1")).thenReturn(Optional.of(brouillonDuPoste(Regime.SESAME)));

        assertEquals(StatutFacture.ENVOYEE, service.envoyer("F-1").getStatut());
    }
}
