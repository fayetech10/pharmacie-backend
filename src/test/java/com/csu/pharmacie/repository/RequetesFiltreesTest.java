package com.csu.pharmacie.repository;

import com.csu.pharmacie.dto.FactureStatutDto;
import com.csu.pharmacie.entity.Facture;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.StatutFacture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exécute réellement (sur H2, même moteur que le dev local) les requêtes ajoutées
 * pour filtrer en base au lieu de charger puis filtrer en mémoire : sentinelle
 * {@code 0 = pas de filtre}, {@code :regime IS NULL}, agrégat mensuel, flux.
 * Une JPQL invalide ferait aussi échouer le démarrage de l'application : ce test
 * sert de garde-fou à la compilation des requêtes comme à leur sémantique.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:requetes-filtrees;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RequetesFiltreesTest {

    @Autowired
    private FactureRepository factureRepository;

    @Autowired
    private FactureStructureRepository factureStructureRepository;

    private Facture facture(String pharmacieId, String regionId, int mois, int annee, double montant) {
        return factureRepository.save(Facture.builder()
                .pharmacieId(pharmacieId)
                .pharmacieNom("Pharmacie " + pharmacieId)
                .regionId(regionId)
                .mois(mois)
                .annee(annee)
                .montantTotal(montant)
                .statut(StatutFacture.BROUILLON)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private FactureStructure factureStructure(String structureId, String regionId,
                                              StatutFacture statut, Regime regime, int mois, int annee) {
        return factureStructureRepository.save(FactureStructure.builder()
                .numero("FS-" + structureId + "-" + statut + "-" + regime + "-" + mois + annee)
                .structureSanitaireId(structureId)
                .regionId(regionId)
                .statut(statut)
                .regime(regime)
                .mois(mois)
                .annee(annee)
                .createdAt(LocalDateTime.now())
                .build());
    }

    /**
     * Projection « badges » (identifiant + statut) : même périmètre par rôle que les
     * listes complètes, sans charger les colonnes JSONB. Côté structures, le service
     * régional ne doit pas voir les factures non encore transmises.
     */
    @Test
    void projectionStatutsPourBadges() {
        facture("PH1", "R1", 1, 2026, 1000);
        facture("PH1", "R1", 2, 2026, 2000);
        facture("PH2", "R2", 3, 2026, 3000);

        List<FactureStatutDto> ph1 = factureRepository.findStatutsByPharmacie("PH1");
        assertEquals(2, ph1.size());
        assertTrue(ph1.stream().allMatch(d -> d.getId() != null && !d.getId().isBlank()));
        assertTrue(ph1.stream().allMatch(d -> d.getStatut() == StatutFacture.BROUILLON));
        assertEquals(1, factureRepository.findStatutsByRegion("R2").size());
        assertEquals(3, factureRepository.findAllStatuts().size());

        factureStructure("S1", "R1", StatutFacture.BROUILLON, Regime.SESAME, 1, 2026);
        factureStructure("S1", "R1", StatutFacture.ENVOYEE, Regime.SESAME, 2, 2026);
        factureStructure("S2", "R1", StatutFacture.VALIDEE_SR, Regime.CONTRIBUTIF, 3, 2026);

        // Le BROUILLON de S1 est masqué au service régional, pas aux autres périmètres.
        assertEquals(2, factureStructureRepository.findStatutsVisiblesParRegion("R1").size());
        assertEquals(2, factureStructureRepository.findStatutsByStructure("S1").size());
        assertEquals(3, factureStructureRepository.findAllStatuts().size());
    }

    @Test
    void filtresFacturesAppliquesEnBase() {
        facture("PH1", "R1", 1, 2026, 1000);
        facture("PH1", "R1", 2, 2026, 2000);
        facture("PH2", "R1", 2, 2026, 3000);
        facture("PH2", "R2", 2, 2025, 4000);

        // Sentinelle 0 : aucun filtre → tout le périmètre.
        assertEquals(2, factureRepository.findByPharmacieIdFiltre("PH1", 0, 0).size());
        assertEquals(3, factureRepository.findByRegionIdFiltre("R1", 0, 0).size());
        assertEquals(4, factureRepository.findAllFiltre(0, 0).size());

        // Filtres mois / année.
        assertEquals(1, factureRepository.findByPharmacieIdFiltre("PH1", 2, 0).size());
        assertEquals(2, factureRepository.findByRegionIdFiltre("R1", 2, 2026).size());
        assertEquals(1, factureRepository.findAllFiltre(0, 2025).size());
    }

    @Test
    void fluxEtAgregatMensuelSansChargerLesLignes() {
        facture("PH1", "R1", 1, 2026, 1000);
        facture("PH1", "R1", 1, 2026, 500);
        facture("PH1", "R1", 3, 2026, 2000);

        try (Stream<Facture> flux = factureRepository.findAllStream()) {
            assertEquals(3, flux.count());
        }
        try (Stream<Facture> flux = factureRepository.findAllByRegionId("R1")) {
            assertEquals(3, flux.count());
        }

        List<Object[]> agregat = factureRepository.aggregatMensuel(2026, "PH1", null);
        assertEquals(2, agregat.size()); // deux mois distincts
        Object[] janvier = agregat.stream()
                .filter(l -> ((Number) l[0]).intValue() == 1)
                .findFirst().orElseThrow();
        assertEquals(2L, ((Number) janvier[1]).longValue());
        assertEquals(1500.0, ((Number) janvier[2]).doubleValue(), 0.001);

        // Périmètre national (les deux paramètres nuls).
        assertEquals(2, factureRepository.aggregatMensuel(2026, null, null).size());
    }

    @Test
    void vueRegionaleDesFacturesStructureExclutLesStatutsInternes() {
        factureStructure("CS1", "R1", StatutFacture.BROUILLON, Regime.CONTRIBUTIF, 1, 2026);
        factureStructure("CS1", "R1", StatutFacture.SOUMISE_CS, Regime.CONTRIBUTIF, 1, 2026);
        factureStructure("CS1", "R1", StatutFacture.REJETEE_CS, Regime.CONTRIBUTIF, 1, 2026);
        factureStructure("CS1", "R1", StatutFacture.ENVOYEE, Regime.CONTRIBUTIF, 1, 2026);
        factureStructure("CS1", "R1", StatutFacture.VALIDEE_SR, Regime.BSF, 2, 2026);

        // Le SR ne voit que les factures réellement transmises au niveau régional.
        assertEquals(2, factureStructureRepository.findVisiblesParRegionFiltre("R1", 0, 0, null).size());
        // Filtre par régime (paramètre enum nul = tous).
        assertEquals(1, factureStructureRepository.findVisiblesParRegionFiltre("R1", 0, 0, Regime.BSF).size());
        // Filtres combinés côté structure et global.
        assertEquals(5, factureStructureRepository.findByStructureFiltre("CS1", 0, 0, null).size());
        assertEquals(4, factureStructureRepository.findAllFiltre(1, 2026, null).size());
    }
}
