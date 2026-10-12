package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.TypeStructure;
import com.csu.pharmacie.repository.DistrictRepository;
import com.csu.pharmacie.repository.FactureStructureRepository;
import com.csu.pharmacie.repository.LettreGarantieRepository;
import com.csu.pharmacie.repository.PatientRepository;
import com.csu.pharmacie.repository.PieceDossierRepository;
import com.csu.pharmacie.repository.StructureSanitaireRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.service.FactureStructureService.ClotureResultat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Étape 3 de la facturation CS / EPS : la clôture mensuelle automatique n'envoie plus les
 * factures que les centres et les EPS complètent par l'import de fin de mois — leur structure
 * les transmet elle-même. Le reste du circuit est inchangé.
 */
class ClotureMensuelleCentresTest {

    private final FactureStructureRepository factureRepository = mock(FactureStructureRepository.class);
    private final FactureMensuelleBcsuService factureMensuelle = new FactureMensuelleBcsuService(factureRepository,
            mock(PieceDossierRepository.class), mock(DistrictRepository.class), mock(CompteurFactureService.class));
    private final FactureStructureService service = new FactureStructureService(factureRepository,
            mock(LettreGarantieRepository.class), mock(StructureSanitaireRepository.class), mock(UserRepository.class),
            mock(FeuilleSoinsService.class), mock(PatientRepository.class), factureMensuelle);

    @BeforeEach
    void preparer() {
        when(factureRepository.save(any(FactureStructure.class))).thenAnswer(appel -> appel.getArgument(0));
    }

    @Test
    void lesFacturesDesCentresAttendentLeurStructure() {
        FactureStructure centre = complete("F-CS", TypeStructure.CS, Regime.SESAME);
        FactureStructure hopital = complete("F-EPS", TypeStructure.EPS, Regime.CONTRIBUTIF);
        FactureStructure poste = complete("F-PS", TypeStructure.PS, Regime.ZERO_CINQ_ANS);
        FactureStructure dialyse = complete("F-DIA", TypeStructure.CS, Regime.HEMODIALYSE);
        when(factureRepository.findAllFiltre(anyInt(), anyInt(), any())).thenReturn(List.of(centre, hopital, poste, dialyse));

        ClotureResultat resultat = service.cloturerPeriodeAutomatique(10, 2026);

        assertEquals(2, resultat.parLaStructure());
        assertEquals(StatutFacture.BROUILLON, centre.getStatut(), "le centre transmet lui-même");
        assertEquals(StatutFacture.BROUILLON, hopital.getStatut());
        assertEquals(StatutFacture.SOUMISE_CS, poste.getStatut(), "le 0-5 ans du poste part toujours au district");
        assertEquals(StatutFacture.ENVOYEE, dialyse.getStatut(), "un régime hors module garde la clôture automatique");
        assertEquals(2, resultat.envoyees());
    }

    @Test
    void avantLaMiseEnServiceLaClotureEnvoieEncoreLesCentres() {
        ReflectionTestUtils.setField(factureMensuelle, "aPartirDe", "2026-11");
        FactureStructure centre = complete("F-CS", TypeStructure.CS, Regime.ZERO_CINQ_ANS);
        when(factureRepository.findAllFiltre(anyInt(), anyInt(), any())).thenReturn(List.of(centre));

        ClotureResultat resultat = service.cloturerPeriodeAutomatique(10, 2026);

        assertEquals(0, resultat.parLaStructure());
        assertEquals(StatutFacture.SOUMISE_CS, centre.getStatut(), "et le 0-5 ans d'un centre passe par le district");
    }

    private static FactureStructure complete(String id, TypeStructure type, Regime regime) {
        return FactureStructure.builder()
                .id(id)
                .numero("FS-" + id)
                .structureSanitaireId("S-" + id)
                .typeStructure(type)
                .regime(regime)
                .mois(10)
                .annee(2026)
                .statut(StatutFacture.BROUILLON)
                .dossiers(new ArrayList<>(List.of(DossierPatient.builder().id("D1").statut(DossierPatient.COMPLET).build())))
                .lignes(new ArrayList<>(List.of(LigneFactureStructure.builder().dossierId("D1")
                        .designation("Consultation").quantite(1).prixUnitaire(2000).build())))
                .historique(new ArrayList<>())
                .build();
    }
}
