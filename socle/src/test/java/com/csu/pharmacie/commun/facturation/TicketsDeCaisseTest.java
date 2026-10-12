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
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.PieceDossier;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.TypeStructure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Étape 4 de la facturation CS / EPS : les tickets de caisse se saisissent en série
 * (identifiant + date, puis photo), un dossier peut en compter plusieurs, et le dossier d'un
 * patient du BCSU n'est complet qu'avec au moins un ticket.
 */
class TicketsDeCaisseTest {

    private static final LocalDate LE_5_OCTOBRE = LocalDate.of(2026, 10, 5);
    private static final String PHOTO = "data:image/jpeg;base64,AAAA";

    private final DepotFactureStructure depot = mock(DepotFactureStructure.class);
    private final DepotPieceDossier depotPieces = mock(DepotPieceDossier.class);
    /** Les pièces « en base ». */
    private final List<PieceDossier> pieces = new ArrayList<>();
    private final ServiceFacturation service = new ServiceFacturation(depot,
            new ReglesFacturation(new ParametresFacturation()), mock(ClientIdentite.class),
            mock(ClientReferentiel.class), mock(ClientPharmacie.class), mock(Numerotation.class),
            mock(GenerateurPdf.class), mock(DepotLettreGarantie.class), mock(DepotFeuilleSoins.class), depotPieces,
            mock(ServiceCarteCsu.class));
    private FactureStructure facture;

    @BeforeEach
    void preparer() {
        facture = FactureStructure.builder()
                .id("F-1").numero("FS-202610-00001").structureSanitaireId("CS-1").typeStructure(TypeStructure.CS)
                .regime(Regime.ZERO_CINQ_ANS).mois(10).annee(2026).statut(StatutFacture.BROUILLON)
                .dossiers(new ArrayList<>(List.of(enfant("E1", "Fatou", "EXT-1"), enfant("E2", "Moussa", "EXT-2"))))
                .lignes(new ArrayList<>(List.of(LigneFactureStructure.builder().dossierId("E1")
                        .designation("Consultation").quantite(1).prixUnitaire(1400).build())))
                .historique(new ArrayList<>())
                .build();
        when(depot.findById("F-1")).thenAnswer(appel -> Optional.of(facture));
        when(depot.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(eq("CS-1"), eq(Regime.ZERO_CINQ_ANS), anyInt(), anyInt()))
                .thenAnswer(appel -> appel.<Integer>getArgument(2) == 10 ? Optional.of(facture) : Optional.empty());
        when(depot.save(any(FactureStructure.class))).thenAnswer(appel -> appel.getArgument(0));

        when(depotPieces.save(any(PieceDossier.class))).thenAnswer(appel -> {
            PieceDossier piece = appel.getArgument(0);
            piece.setId(UUID.randomUUID().toString());
            pieces.add(piece);
            return piece;
        });
        when(depotPieces.countByDossierIdAndType(anyString(), anyString())).thenAnswer(appel -> pieces.stream()
                .filter(p -> p.getDossierId().equals(appel.getArgument(0)) && p.getType().equals(appel.getArgument(1)))
                .count());
        when(depotPieces.findByIdAndDossierId(anyString(), anyString())).thenAnswer(appel -> pieces.stream()
                .filter(p -> p.getId().equals(appel.getArgument(0)) && p.getDossierId().equals(appel.getArgument(1)))
                .findFirst());
        doAnswer(appel -> pieces.remove(appel.<PieceDossier>getArgument(0))).when(depotPieces).delete(any(PieceDossier.class));
        when(depotPieces.findByDossierIdAndTypeOrderByCreatedAtAsc(anyString(), anyString())).thenAnswer(appel -> pieces.stream()
                .filter(p -> p.getDossierId().equals(appel.getArgument(0)) && p.getType().equals(appel.getArgument(1)))
                .map(p -> (DepotPieceDossier.ResumePiece) new DepotPieceDossier.ResumePiece() {
                    public String getId() { return p.getId(); }
                    public LocalDateTime getCreatedAt() { return p.getCreatedAt(); }
                })
                .toList());

        UtilisateurAuthentifie agent = UtilisateurAuthentifie.builder()
                .id("agent-1").prenom("Agent").nom("Centre").role(Role.CENTRE_SANTE).structureId("CS-1").build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(agent, null, List.of()));
    }

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void laRechercheProposeLesEnfantsDuMemeAccompagnantVusCeJour() {
        ServiceFacturation.RechercheTicket recherche = service.rechercherVisites(Regime.ZERO_CINQ_ANS, "1 751 1990 01234", LE_5_OCTOBRE);

        assertEquals(List.of("E1", "E2"), recherche.visites().stream().map(ServiceFacturation.VisiteTicket::dossierId).toList());
        assertTrue(recherche.autresDates().isEmpty());
    }

    @Test
    void uneDateSansVisiteSignaleLesDatesConnues() {
        ServiceFacturation.RechercheTicket recherche = service.rechercherVisites(Regime.ZERO_CINQ_ANS, "1751199001234",
                LE_5_OCTOBRE.plusDays(2));

        assertTrue(recherche.visites().isEmpty());
        assertEquals(List.of(LE_5_OCTOBRE), recherche.autresDates());
        assertTrue(service.rechercherVisites(Regime.ZERO_CINQ_ANS, "1751199001234", LocalDate.of(2026, 11, 2))
                .visites().isEmpty(), "pas de facture ce mois-là");
    }

    @Test
    void leTicketCompleteLeDossierQuiADesPrestations() {
        ServiceFacturation.VisiteTicket visite = service.ajouterTicket("F-1", "E1", PHOTO);

        assertEquals(1, visite.tickets());
        assertEquals(DossierPatient.COMPLET, visite.statut());
        assertTrue(facture.getDossiers().get(0).possedePiece(PieceDossier.TICKET_CAISSE));
        assertEquals(FactureStructure.ETAPE_EN_COURS, facture.getEtape(), "E2 attend encore prestation et ticket");
    }

    @Test
    void sansPrestationLeTicketNeSuffitPas() {
        ServiceFacturation.VisiteTicket visite = service.ajouterTicket("F-1", "E2", PHOTO);

        assertEquals(DossierPatient.A_COMPLETER, visite.statut());
    }

    @Test
    void plusieursTicketsSAjoutentEtSeRetirentUnAUn() {
        service.ajouterTicket("F-1", "E1", PHOTO);
        ServiceFacturation.VisiteTicket deux = service.ajouterTicket("F-1", "E1", PHOTO);
        assertEquals(2, deux.tickets(), "le second s'ajoute, il ne remplace pas le premier");
        assertEquals(2, service.tickets("F-1", "E1").size());

        List<ServiceFacturation.TicketResume> tickets = service.tickets("F-1", "E1");
        service.retirerTicket("F-1", "E1", tickets.get(0).id());
        ServiceFacturation.VisiteTicket aucun = service.retirerTicket("F-1", "E1", tickets.get(1).id());

        assertEquals(0, aucun.tickets());
        assertEquals(DossierPatient.A_COMPLETER, aucun.statut(), "sans ticket, le dossier redevient à compléter");
        assertFalse(facture.getDossiers().get(0).possedePiece(PieceDossier.TICKET_CAISSE));
    }

    @Test
    void uneFactureTransmiseOuUnePhotoIllisibleSontRefusees() {
        assertThrows(ExceptionMetier.class, () -> service.ajouterTicket("F-1", "E1", "pas une image"));
        facture.setStatut(StatutFacture.ENVOYEE);
        assertThrows(ExceptionMetier.class, () -> service.ajouterTicket("F-1", "E1", PHOTO));
        assertTrue(pieces.isEmpty());
    }

    private static DossierPatient enfant(String id, String prenom, String extrait) {
        return DossierPatient.builder()
                .id(id).prenom(prenom).nom("Sow").matricule(extrait)
                .cniAccompagnant("1751199001234").cleRapprochement("1751199001234")
                .dateNaissance(LocalDate.of(2024, 3, 1)).datePriseEnCharge(LE_5_OCTOBRE)
                .lettreGarantieId("L-" + id).lettreGarantieNumero("G-" + id)
                .identiteVerrouillee(true).statut(DossierPatient.A_COMPLETER)
                .pieces(new ArrayList<>())
                .build();
    }
}
