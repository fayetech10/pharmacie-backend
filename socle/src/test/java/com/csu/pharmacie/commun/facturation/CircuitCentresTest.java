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
import com.csu.pharmacie.entity.RapportRapprochement;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.TypeStructure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Étape 3 de la facturation CS / EPS : l'étape d'une facture de centre se déduit de son
 * contenu, c'est la structure qui la transmet, et son 0-5 ans passe par le district — comme
 * celui des postes, mais jamais celui d'un EPS.
 */
class CircuitCentresTest {

    private final DepotFactureStructure depot = mock(DepotFactureStructure.class);
    private final ClientIdentite clientIdentite = mock(ClientIdentite.class);
    private final ServiceFacturation service = new ServiceFacturation(depot,
            new ReglesFacturation(new ParametresFacturation()), clientIdentite, mock(ClientReferentiel.class),
            mock(ClientPharmacie.class), mock(Numerotation.class), mock(GenerateurPdf.class),
            mock(DepotLettreGarantie.class), mock(DepotFeuilleSoins.class), mock(DepotPieceDossier.class),
            mock(ServiceCarteCsu.class));

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════ étapes ══════════════════════════

    @Test
    void lEtapeSuitLeContenuDeLaFacture() {
        FactureStructure facture = facture(TypeStructure.CS, Regime.SESAME, StatutFacture.BROUILLON);
        assertEquals(FactureStructure.ETAPE_EN_COURS, facture.getEtape(), "patients du BCSU, aucun import");

        facture.setDernierRapprochement(new RapportRapprochement());
        assertEquals(FactureStructure.ETAPE_IMPORTEE, facture.getEtape(), "importée, un patient reste à compléter");

        facture.getDossiers().get(0).setStatut(DossierPatient.COMPLET);
        facture.getLignes().add(ligne());
        assertEquals(FactureStructure.ETAPE_COMPLETE, facture.getEtape());

        facture.setStatut(StatutFacture.ENVOYEE);
        assertEquals(FactureStructure.ETAPE_TRANSMISE, facture.getEtape());

        facture.setStatut(StatutFacture.REJETEE_SR);
        assertEquals(FactureStructure.ETAPE_COMPLETE, facture.getEtape(), "rejetée : de nouveau entre ses mains");
    }

    @Test
    void lesAutresFacturesNOntPasDEtape() {
        assertNull(facture(TypeStructure.PS, Regime.SESAME, StatutFacture.BROUILLON).getEtape(), "poste de santé");
        assertNull(facture(TypeStructure.CS, Regime.HEMODIALYSE, StatutFacture.BROUILLON).getEtape(),
                "régime sans clé de rapprochement");
        assertNotNull(facture(TypeStructure.EPS, Regime.CESARIENNE, StatutFacture.BROUILLON).getEtape(), "hôpital");
    }

    // ══════════════════════════ transmission ══════════════════════════

    @Test
    void le05AnsDUnCentrePasseParLeDistrict() {
        FactureStructure facture = factureComplete(TypeStructure.CS, Regime.ZERO_CINQ_ANS);
        facture.setDistrictId(null);
        when(clientIdentite.structure("S-1")).thenReturn(StructureDto.builder().id("S-1")
                .typeStructure(TypeStructure.CS).districtId("D-1").build());

        FactureStructure envoyee = envoyer(facture);

        assertEquals(StatutFacture.SOUMISE_CS, envoyee.getStatut());
        assertEquals("D-1", envoyee.getDistrictId(), "le district est figé à l'envoi");
    }

    @Test
    void unHopitalEtLesAutresRegimesVontAuServiceRegional() {
        assertEquals(StatutFacture.ENVOYEE, envoyer(factureComplete(TypeStructure.EPS, Regime.ZERO_CINQ_ANS)).getStatut());
        assertEquals(StatutFacture.ENVOYEE, envoyer(factureComplete(TypeStructure.CS, Regime.SESAME)).getStatut());
    }

    @Test
    void uneFactureAvecDesPatientsACompleterNePartPas() {
        FactureStructure facture = facture(TypeStructure.CS, Regime.SESAME, StatutFacture.BROUILLON);
        facture.getLignes().add(ligne());
        assertThrows(RuntimeException.class, () -> envoyer(facture));
        assertEquals(StatutFacture.BROUILLON, facture.getStatut());
    }

    // ══════════════════════════ district ══════════════════════════

    @Test
    void leDistrictControleLe05AnsDesPostesEtDesCentresPasDesHopitaux() {
        FactureStructure poste = factureTransmise("F-PS", "PS-1", TypeStructure.PS);
        FactureStructure centre = factureTransmise("F-CS", "CS-1", TypeStructure.CS);
        FactureStructure hopital = factureTransmise("F-EPS", "EPS-1", TypeStructure.EPS);
        when(clientIdentite.structuresDuDistrict("D-1")).thenReturn(List.of(
                StructureDto.builder().id("PS-1").typeStructure(TypeStructure.PS).build(),
                StructureDto.builder().id("CS-1").typeStructure(TypeStructure.CS).build(),
                StructureDto.builder().id("EPS-1").typeStructure(TypeStructure.EPS).build()));
        when(depot.findByDistrictId("D-1")).thenReturn(List.of(poste, centre, hopital));
        when(depot.findByStructureSanitaireIdIn(anyCollection())).thenReturn(List.of());

        List<String> controlees = service.facturesDesPostesDuDistrict("D-1", 10, 2026, null, null).stream()
                .map(FactureStructure::getId).toList();

        assertEquals(List.of("F-PS", "F-CS"), controlees);
    }

    // ══════════════════════════ jeux d'essai ══════════════════════════

    private FactureStructure envoyer(FactureStructure facture) {
        when(depot.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(depot.save(any(FactureStructure.class))).thenAnswer(appel -> appel.getArgument(0));
        UtilisateurAuthentifie agent = UtilisateurAuthentifie.builder()
                .id("agent-1").prenom("Agent").nom("Centre").role(Role.CENTRE_SANTE).structureId("S-1").build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(agent, null, List.of()));
        return service.envoyer(facture.getId());
    }

    private static FactureStructure facture(TypeStructure type, Regime regime, StatutFacture statut) {
        DossierPatient dossier = DossierPatient.builder().id("D1").nom("Ndiaye").prenom("Ousmane")
                .lettreGarantieNumero("G-1").statut(DossierPatient.A_COMPLETER).build();
        return FactureStructure.builder()
                .id("F-" + type + "-" + regime)
                .numero("FS-202610-00001")
                .structureSanitaireId("S-1")
                .typeStructure(type)
                .districtId("D-1")
                .regime(regime)
                .mois(10)
                .annee(2026)
                .statut(statut)
                .dossiers(new ArrayList<>(List.of(dossier)))
                .lignes(new ArrayList<>())
                .historique(new ArrayList<>())
                .build();
    }

    private static FactureStructure factureComplete(TypeStructure type, Regime regime) {
        FactureStructure facture = facture(type, regime, StatutFacture.BROUILLON);
        facture.getDossiers().get(0).setStatut(DossierPatient.COMPLET);
        facture.getLignes().add(ligne());
        return facture;
    }

    private static FactureStructure factureTransmise(String id, String structureId, TypeStructure type) {
        FactureStructure facture = factureComplete(type, Regime.ZERO_CINQ_ANS);
        facture.setId(id);
        facture.setStructureSanitaireId(structureId);
        facture.setStatut(type == TypeStructure.EPS ? StatutFacture.ENVOYEE : StatutFacture.SOUMISE_CS);
        return facture;
    }

    private static LigneFactureStructure ligne() {
        return LigneFactureStructure.builder().dossierId("D1").designation("Consultation").quantite(1).prixUnitaire(2000).build();
    }
}
