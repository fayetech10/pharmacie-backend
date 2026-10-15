package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.commun.carte.ServiceCarteCsu;
import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.ClientPharmacie;
import com.csu.pharmacie.commun.client.ClientReferentiel;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.TypeStructure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Saisie hors ligne renvoyée : la même saisie (même clé de synchronisation) ne crée jamais
 * qu'un dossier — rejouée après une réponse perdue, reportée sur un autre mois, ou envoyée
 * plusieurs fois au même instant (bug du 14/10/2026 : patient enregistré trois fois au
 * retour du réseau).
 *
 * <p>La base est simulée en mémoire, et le verrou consultatif PostgreSQL par un verrou Java
 * tenu jusqu'à la fin de l'appel — ce que fait la transaction en production.</p>
 */
class SaisiesRejoueesTest {

    private static final String POSTE = "PS-1";

    /** Les factures « en base ». */
    private final Map<String, FactureStructure> base = new ConcurrentHashMap<>();
    /** Verrous consultatifs par nom, relâchés en fin de « transaction ». */
    private final Map<String, ReentrantLock> verrous = new ConcurrentHashMap<>();
    /** Point de rendez-vous entre le contrôle de la clé et le choix de la facture du mois. */
    private volatile CountDownLatch rendezVous = new CountDownLatch(0);

    private final DepotFactureStructure depot = mock(DepotFactureStructure.class);
    private final ClientIdentite clientIdentite = mock(ClientIdentite.class);
    private final Numerotation numerotation = mock(Numerotation.class);
    private final ServiceFacturation service = new ServiceFacturation(depot,
            new ReglesFacturation(new ParametresFacturation()), clientIdentite,
            mock(ClientReferentiel.class), mock(ClientPharmacie.class), numerotation,
            mock(GenerateurPdf.class), mock(DepotLettreGarantie.class), mock(DepotFeuilleSoins.class),
            mock(DepotPieceDossier.class), mock(ServiceCarteCsu.class));

    @BeforeEach
    void preparer() {
        AtomicInteger compteur = new AtomicInteger();
        when(numerotation.prochainNumero(anyString(), anyInt(), anyInt()))
                .thenAnswer(appel -> "FP-202610-" + compteur.incrementAndGet());
        when(clientIdentite.structure(POSTE)).thenReturn(StructureDto.builder()
                .id(POSTE).nom("Poste de Ndiaganiao").typeStructure(TypeStructure.PS).build());

        when(depot.verrouiller(anyString())).thenAnswer(appel -> {
            verrous.computeIfAbsent(appel.getArgument(0), cle -> new ReentrantLock()).lock();
            return 1;
        });
        when(depot.findByStructureSanitaireId(anyString())).thenAnswer(appel -> base.values().stream()
                .filter(f -> Objects.equals(f.getStructureSanitaireId(), appel.getArgument(0)))
                .toList());
        when(depot.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(anyString(), any(Regime.class), anyInt(), anyInt()))
                .thenAnswer(appel -> {
                    // Laisse aux envois simultanés le temps de passer, eux aussi, le contrôle de
                    // la clé — ce qu'ils faisaient sans verrou.
                    rendezVous.countDown();
                    rendezVous.await(300, TimeUnit.MILLISECONDS);
                    return base.values().stream()
                            .filter(f -> Objects.equals(f.getStructureSanitaireId(), appel.getArgument(0))
                                    && f.getRegime() == appel.getArgument(1)
                                    && f.getMois() == appel.<Integer>getArgument(2)
                                    && f.getAnnee() == appel.<Integer>getArgument(3))
                            .findFirst();
                });
        when(depot.save(any(FactureStructure.class))).thenAnswer(appel -> {
            FactureStructure facture = appel.getArgument(0);
            if (facture.getId() == null) {
                facture.setId(UUID.randomUUID().toString());
            }
            base.put(facture.getId(), facture);
            return facture;
        });
    }

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uneSaisieRejoueeRenvoieSaFactureSansSecondDossier() {
        FactureStructure premiere = enTransaction(saisie("cle-1", "Awa", 10));
        FactureStructure rejouee = enTransaction(saisie("cle-1", "Awa", 10));

        assertEquals(premiere.getId(), rejouee.getId());
        assertEquals(1, base.size());
        assertEquals(1, premiere.getDossiers().size(), "la saisie rejouée n'ajoute rien");
        assertEquals(1, premiere.getLignes().size());
        assertEquals(List.of("cle-1"), premiere.getDossiers().get(0).getClesSynchronisation());
    }

    @Test
    void uneSaisieRejoueeSurUnAutreMoisEstReconnue() {
        FactureStructure octobre = enTransaction(saisie("cle-1", "Awa", 10));
        // Réponse perdue, puis saisie reportée par l'écran sur le mois suivant (mois clôturé).
        FactureStructure rejouee = enTransaction(saisie("cle-1", "Awa", 11));

        assertEquals(octobre.getId(), rejouee.getId());
        assertEquals(1, base.size(), "pas de facture de novembre pour une saisie déjà reçue");
    }

    @Test
    void desSaisiesDifferentesSAjoutentNormalement() {
        enTransaction(saisie("cle-1", "Awa", 10));
        FactureStructure facture = enTransaction(saisie("cle-2", "Moussa", 10));

        assertEquals(1, base.size());
        assertEquals(2, facture.getDossiers().size());
    }

    @Test
    void laMemeSaisieEnvoyeeTroisFoisEnMemeTempsNeCreeQuUnDossier() throws Exception {
        List<FactureStructure> reponses = simultanement(
                saisie("cle-1", "Awa", 10), saisie("cle-1", "Awa", 10), saisie("cle-1", "Awa", 10));

        assertEquals(1, base.size(), "une seule facture du mois");
        FactureStructure facture = base.values().iterator().next();
        assertEquals(1, facture.getDossiers().size(), "un seul dossier pour la saisie");
        assertEquals(1, facture.getLignes().size());
        assertTrue(reponses.stream().allMatch(r -> r.getId().equals(facture.getId())),
                "les envois en double reçoivent la facture existante");
    }

    @Test
    void deuxPremieresSaisiesSimultaneesDuMoisPartagentLaMemeFacture() throws Exception {
        simultanement(saisie("cle-1", "Awa", 10), saisie("cle-2", "Moussa", 10));

        assertEquals(1, base.size(), "pas deux factures pour le même régime et le même mois");
        assertEquals(List.of("Awa", "Moussa"), base.values().iterator().next().getDossiers().stream()
                .map(DossierPatient::getPrenom).sorted().toList());
    }

    @Test
    void lesVerrousSontPrisAvantDeLireCeQuIlsProtegent() {
        enTransaction(saisie("cle-1", "Awa", 10));

        InOrder ordre = inOrder(depot);
        ordre.verify(depot).verrouiller("saisie:" + POSTE + ":cle-1");
        ordre.verify(depot).findByStructureSanitaireId(POSTE);
        ordre.verify(depot).findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(eq(POSTE), eq(Regime.ZERO_CINQ_ANS),
                eq(10), eq(2026));
        // Facture du mois absente : verrou de création, puis nouvelle lecture avant de la créer.
        ordre.verify(depot).verrouiller("facture:" + POSTE + ":ZERO_CINQ_ANS:2026-10");
        ordre.verify(depot).findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(eq(POSTE), eq(Regime.ZERO_CINQ_ANS),
                eq(10), eq(2026));
        ordre.verify(depot).save(any(FactureStructure.class));
    }

    @Test
    void uneFactureDejaCreeeNeDemandePasLeVerrouDeCreation() {
        enTransaction(saisie("cle-1", "Awa", 10));
        enTransaction(saisie("cle-2", "Moussa", 10));

        verify(depot, times(1)).verrouiller("facture:" + POSTE + ":ZERO_CINQ_ANS:2026-10");
    }

    // ─────────────── outils ───────────────

    /** Un appel au service, comme une requête HTTP : sa « transaction » relâche le verrou en finissant. */
    private FactureStructure enTransaction(RequeteFacturation requete) {
        connecter();
        try {
            return service.ajouterPatient(requete);
        } finally {
            for (ReentrantLock verrou : verrous.values()) {
                while (verrou.isHeldByCurrentThread()) {
                    verrou.unlock();
                }
            }
            SecurityContextHolder.clearContext();
        }
    }

    /** Envoie les saisies au même instant, chacune sur son fil, comme des requêtes parallèles. */
    private List<FactureStructure> simultanement(RequeteFacturation... requetes) throws Exception {
        rendezVous = new CountDownLatch(requetes.length);
        CountDownLatch depart = new CountDownLatch(1);
        ExecutorService fils = Executors.newFixedThreadPool(requetes.length);
        try {
            List<Future<FactureStructure>> envois = new ArrayList<>();
            for (RequeteFacturation requete : requetes) {
                envois.add(fils.submit(() -> {
                    depart.await();
                    return enTransaction(requete);
                }));
            }
            depart.countDown();
            List<FactureStructure> reponses = new ArrayList<>();
            for (Future<FactureStructure> envoi : envois) {
                reponses.add(envoi.get(10, TimeUnit.SECONDS));
            }
            return reponses;
        } finally {
            fils.shutdownNow();
        }
    }

    private static void connecter() {
        UtilisateurAuthentifie agent = UtilisateurAuthentifie.builder()
                .id("agent-1").prenom("Agent").nom("Poste").role(Role.POSTE_SANTE).structureId(POSTE).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(agent, null, List.of()));
    }

    /** Enfant de moins de 5 ans vu le 5 du mois, une consultation — telle que l'écran l'envoie. */
    private static RequeteFacturation saisie(String cle, String prenom, int mois) {
        RequeteFacturation requete = new RequeteFacturation();
        requete.setRegime(Regime.ZERO_CINQ_ANS);
        requete.setMois(mois);
        requete.setAnnee(2026);
        requete.setPatientNom("Diop");
        requete.setPatientPrenom(prenom);
        requete.setPatientDateNaissance(LocalDate.of(2024, 5, 1));
        requete.setLignes(new ArrayList<>(List.of(LigneFactureStructure.builder()
                .designation("Consultation").quantite(1).prixUnitaire(1400)
                .datePriseEnCharge(LocalDate.of(2026, mois, 5)).build())));
        requete.setCleSynchronisation(cle);
        return requete;
    }
}
