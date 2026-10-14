package com.csu.pharmacie.centre.service;

import com.csu.pharmacie.centre.service.ServiceActivitesRegion.ActiviteDistrict;
import com.csu.pharmacie.centre.service.ServiceActivitesRegion.ActiviteStructure;
import com.csu.pharmacie.centre.service.ServiceActivitesRegion.ActivitesRegion;
import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.dto.DistrictDto;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.facturation.DepotFactureStructure;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.TypeStructure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Activités du réseau d'une région (14/10/2026) : le service régional voit SA région — quelle
 * que soit la région demandée —, toutes ses structures (à zéro si rien saisi), brouillons
 * compris et ventilés en cours / transmis, patients distincts à chaque échelon.
 */
class ActivitesRegionTest {

    private static final String REGION = "region-thies";

    private final DepotFactureStructure depot = mock(DepotFactureStructure.class);
    private final ClientIdentite clientIdentite = mock(ClientIdentite.class);
    private final ServiceActivitesRegion service = new ServiceActivitesRegion(depot, clientIdentite);
    private final List<FactureStructure> factures = new ArrayList<>();

    @BeforeEach
    void preparer() {
        when(clientIdentite.districtsDeLaRegion(REGION)).thenReturn(List.of(
                DistrictDto.builder().id("d-thies").code("THDT08").nom("District de Thiès")
                        .regionId(REGION).regionNom("Thiès").actif(true).build(),
                DistrictDto.builder().id("d-mbour").code("THDT05").nom("District de Mbour")
                        .regionId(REGION).regionNom("Thiès").actif(true).build()));
        when(clientIdentite.structuresDeLaRegion(REGION)).thenReturn(List.of(
                structure("cs-thies", "Centre de santé de Thiès", TypeStructure.CS, "d-thies", true),
                structure("ps-keur", "Poste de Keur Mbaye", TypeStructure.PS, "d-thies", true),
                structure("ps-silence", "Poste silencieux", TypeStructure.PS, "d-thies", true),
                structure("ps-ferme", "Poste fermé", TypeStructure.PS, "d-thies", false),
                structure("eps-x", "Hôpital régional", TypeStructure.EPS, null, true)));
        when(depot.findByStructureSanitaireIdInAndAnnee(anyCollection(), eq(2026))).thenReturn(factures);
        connecter(Role.SERVICE_REGIONAL, REGION);
    }

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void leServiceRegionalVoitSaRegionAvecLesBrouillonsVentiles() {
        // Le poste : un brouillon 0-5 ans (2 visites du même enfant) et une facture Sésame transmise.
        factures.add(facture("ps-keur", Regime.ZERO_CINQ_ANS, 10, StatutFacture.BROUILLON, 2800,
                dossier("MAT-1", DossierPatient.COMPLET), dossier("mat-1 ", DossierPatient.A_COMPLETER)));
        factures.add(facture("ps-keur", Regime.SESAME, 10, StatutFacture.ENVOYEE, 5000,
                dossier("MAT-2", DossierPatient.COMPLET)));
        // Un autre mois : hors période.
        factures.add(facture("ps-keur", Regime.ZERO_CINQ_ANS, 9, StatutFacture.PAYEE, 9999,
                dossier("MAT-9", DossierPatient.COMPLET)));
        // Le centre : soumis au district.
        factures.add(facture("cs-thies", Regime.ZERO_CINQ_ANS, 10, StatutFacture.SOUMISE_CS, 1400,
                dossier("MAT-1", DossierPatient.COMPLET)));

        ActivitesRegion vue = service.construire(null, 10, 2026);

        assertEquals(REGION, vue.getRegionId());
        assertEquals("Thiès", vue.getRegionNom());
        assertEquals("Octobre 2026", vue.getPeriode());
        // Deux districts déclarés + « Sans district » (l'EPS), Mbour à zéro mais présent.
        assertEquals(2, vue.getNombreDistricts());
        assertEquals(List.of("District de Mbour", "District de Thiès", "Sans district"),
                vue.getDistricts().stream().map(ActiviteDistrict::getNom).toList());

        ActiviteDistrict thies = vue.getDistricts().get(1);
        // Poste fermé sans saisie écarté ; centre d'abord, puis les postes par nom.
        assertEquals(List.of("cs-thies", "ps-keur", "ps-silence"),
                thies.getStructures().stream().map(ActiviteStructure::getId).toList());
        assertEquals(2, thies.getNombrePostes());
        assertEquals(1, thies.getNombreCentres());
        assertEquals(2, thies.getStructuresActives());
        assertEquals(66.7, thies.getTauxActivite());

        ActiviteStructure poste = thies.getStructures().get(1);
        assertEquals(2, poste.getIndicateurs().getNombreFactures());
        assertEquals(1, poste.getIndicateurs().getFacturesEnCours());
        assertEquals(1, poste.getIndicateurs().getFacturesTransmises());
        assertEquals(2800, poste.getIndicateurs().getMontantSencsuEnCours());
        assertEquals(5000, poste.getIndicateurs().getMontantSencsuTransmis());
        assertEquals(3, poste.getIndicateurs().getVisites());
        assertEquals(2, poste.getIndicateurs().getPatients(), "MAT-1 vu deux fois = un patient");
        assertEquals(1, poste.getIndicateurs().getDossiersACompleter());
        assertEquals(1, poste.getIndicateurs().getParStatut().get("BROUILLON"));
        assertEquals(1, poste.getIndicateurs().getParStatut().get("ENVOYEE"));

        ActiviteStructure silencieux = thies.getStructures().get(2);
        assertEquals(0, silencieux.getIndicateurs().getNombreFactures());
        assertTrue(silencieux.getFactures().isEmpty());

        // District : MAT-1 vu au poste ET au centre ne compte qu'une fois.
        assertEquals(3, thies.getIndicateurs().getNombreFactures());
        assertEquals(2, thies.getIndicateurs().getPatients());
        assertEquals(9200, thies.getIndicateurs().getMontantSencsu());
        assertEquals(9200, vue.getIndicateurs().getMontantSencsu());
        assertEquals(4, vue.getNombreStructures(), "le poste fermé sans saisie est écarté");
        assertEquals(2, vue.getStructuresActives());
    }

    @Test
    void uneAutreRegionEstRefuseeAuServiceRegional() {
        ExceptionMetier refus = assertThrows(ExceptionMetier.class,
                () -> service.construire("region-dakar", 10, 2026));
        assertTrue(refus.getMessage().contains("votre région"));
        verify(clientIdentite, never()).structuresDeLaRegion("region-dakar");
    }

    @Test
    void leNiveauCentralDoitDesignerLaRegion() {
        connecter(Role.SERVICE_CENTRAL, null);
        assertThrows(ExceptionMetier.class, () -> service.construire(null, 10, 2026));
        assertEquals(REGION, service.construire(REGION, 10, 2026).getRegionId());
    }

    @Test
    void unDistrictNePeutPasConsulterLaRegion() {
        connecter(Role.DISTRICT, REGION);
        assertThrows(ExceptionMetier.class, () -> service.construire(REGION, 10, 2026));
        verify(depot, never()).findByStructureSanitaireIdInAndAnnee(anyCollection(), anyInt());
    }

    @Test
    void sansRouteDesDistrictsLesDistrictsViennentDesStructures() {
        when(clientIdentite.districtsDeLaRegion(REGION)).thenReturn(List.of());
        ActivitesRegion vue = service.construire(null, 0, 2026);
        assertEquals("Année 2026", vue.getPeriode());
        assertNull(vue.getMois());
        assertEquals(List.of("District de Thiès", "Sans district"),
                vue.getDistricts().stream().map(ActiviteDistrict::getNom).toList());
    }

    // ─────────────────────── fabrication ───────────────────────

    private static void connecter(Role role, String regionId) {
        UtilisateurAuthentifie utilisateur = UtilisateurAuthentifie.builder()
                .id("u-1").prenom("Service").nom("Régional").role(role).regionId(regionId).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(utilisateur, null, List.of()));
    }

    private static StructureDto structure(String id, String nom, TypeStructure type, String districtId, boolean actif) {
        return StructureDto.builder().id(id).code(id.toUpperCase()).nom(nom).typeStructure(type)
                .districtId(districtId).districtNom(districtId == null ? null : "District de Thiès")
                .regionId(REGION).regionNom("Thiès").actif(actif).build();
    }

    private static DossierPatient dossier(String matricule, String statut) {
        DossierPatient dossier = new DossierPatient();
        dossier.setId(UUID.randomUUID().toString());
        dossier.setMatricule(matricule);
        dossier.setStatut(statut);
        return dossier;
    }

    private static FactureStructure facture(String structureId, Regime regime, int mois, StatutFacture statut,
                                            double sencsu, DossierPatient... dossiers) {
        FactureStructure facture = new FactureStructure();
        facture.setId(UUID.randomUUID().toString());
        facture.setNumero("FS-2026" + mois + "-" + structureId);
        facture.setStructureSanitaireId(structureId);
        facture.setRegime(regime);
        facture.setMois(mois);
        facture.setAnnee(2026);
        facture.setStatut(statut);
        facture.setMontantTotal(sencsu);
        facture.setMontantTotalSencsu(sencsu);
        facture.setDossiers(new ArrayList<>(List.of(dossiers)));
        facture.setUpdatedAt(LocalDateTime.of(2026, mois, 14, 10, 0));
        return facture;
    }
}
