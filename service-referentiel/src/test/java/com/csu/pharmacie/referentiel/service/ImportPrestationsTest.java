package com.csu.pharmacie.referentiel.service;

import com.csu.pharmacie.entity.PrestationHorsCsu;
import com.csu.pharmacie.referentiel.depot.DepotPrestationHorsCsu;
import com.csu.pharmacie.referentiel.dto.ResultatImportReferentiel;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Import « Service | Prestation | Prix (FCFA) » (modèle fourni le 07/10/2026) : la même
 * prestation dans plusieurs services, prix vides, services écrits de plusieurs façons.
 */
class ImportPrestationsTest {

    private final DepotPrestationHorsCsu depot = mock(DepotPrestationHorsCsu.class);
    private final ServiceHorsCsu service = new ServiceHorsCsu(depot);
    private final List<PrestationHorsCsu> enregistres = new ArrayList<>();

    ImportPrestationsTest() {
        when(depot.findByStructureIdOrderByCategorieAscDesignationAsc(anyString())).thenReturn(new ArrayList<>());
        when(depot.applicablesA(anyString())).thenReturn(List.of());
        when(depot.save(any(PrestationHorsCsu.class))).thenAnswer(appel -> {
            PrestationHorsCsu tarif = appel.getArgument(0);
            if (!enregistres.contains(tarif)) enregistres.add(tarif);
            return tarif;
        });
    }

    private MockMultipartFile classeur(Object[][] lignes) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet feuille = wb.createSheet("Feuil1");
            for (int i = 0; i < lignes.length; i++) {
                Row row = feuille.createRow(i);
                for (int j = 0; j < lignes[i].length; j++) {
                    Object v = lignes[i][j];
                    if (v instanceof Number n) row.createCell(j).setCellValue(n.doubleValue());
                    else if (v != null) row.createCell(j).setCellValue(v.toString());
                }
            }
            wb.write(out);
            return new MockMultipartFile("fichier", "Prestation et service.xlsx", null, out.toByteArray());
        }
    }

    private PrestationHorsCsu tarif(String service, String designation) {
        return enregistres.stream()
                .filter(t -> service.equals(t.getCategorie()) && designation.equals(t.getDesignation()))
                .findFirst().orElseThrow(() -> new AssertionError("absent : " + service + " / " + designation));
    }

    @Test
    void lesColonnesDuModeleSontLuesEtUnePrestationVitDansChaqueService() throws Exception {
        ResultatImportReferentiel r = service.importer(classeur(new Object[][]{
                {"Service", "Prestation", "Prix (FCFA)"},
                {"Dépositaire", "DEXCHLORPHENIRAMINE Sirop", 800},
                {"CPC", "DEXCHLORPHENIRAMINE Sirop", null},
                {"Tri enfant", "DEXCHLORPHENIRAMINE Sirop", null},
                {"ICP", "Consultation : CPC", 500},
        }), "poste-1", true, null);

        assertEquals(4, r.getLignesLues());
        assertEquals(4, r.getCrees(), "une prestation par service, sans écrasement");
        assertEquals(800, tarif("Dépositaire", "DEXCHLORPHENIRAMINE Sirop").getPrix(), 0.001,
                "le prix « Prix (FCFA) » est lu, et pas écrasé par les lignes suivantes sans prix");
        assertEquals(500, tarif("ICP", "Consultation : CPC").getPrix(), 0.001);
        assertEquals(0, tarif("CPC", "DEXCHLORPHENIRAMINE Sirop").getPrix(), 0.001);
        assertEquals(4, enregistres.stream().map(PrestationHorsCsu::getCode).distinct().count(), "codes uniques");
        assertTrue(enregistres.stream().allMatch(t -> "poste-1".equals(t.getStructureId())));
        assertTrue(r.getAvertissements().stream().anyMatch(a -> a.startsWith("2 prestation(s) importée(s) sans prix")),
                "les prix manquants sont signalés : " + r.getAvertissements());
        assertTrue(r.getErreurs().isEmpty(), r.getErreurs().toString());
    }

    @Test
    void lesEcrituresDunMemeServiceSontRegroupees() throws Exception {
        service.importer(classeur(new Object[][]{
                {"Service", "Prestation", "Prix (FCFA)"},
                {"Dépositaire", "ALBENDAZOLE CP", 300},
                {"DEPOSITAIRE", "AMOXICILLINE 500", 500},
                {"CPC", "Doliprane", null},
                {"Cpc", "Paracétamol sirop", 600},
                {"CPC,", "Pommade", 1000},
                {"PEDIATRIE", "Kit SRO + Zinc", null},
                {"PEDIATRIE", "Cotrimoxazole sirop", null},
                {"Pédiatrie", "Amoxicilline sirop", null},
                {"PADIATRIE", "Ibuprofène sirop", null},
        }), "poste-1", true, null);

        assertEquals(List.of("CPC", "Dépositaire", "Pédiatrie"),
                enregistres.stream().map(PrestationHorsCsu::getCategorie).distinct().sorted().toList(),
                "casse, accents, ponctuation et coquille d'une lettre ramenés à une écriture");
    }

    @Test
    void unPrixVideNeRemplacePasLePrixExistant() throws Exception {
        PrestationHorsCsu existant = PrestationHorsCsu.builder().id("t1").code("FLAGYL_SIROP")
                .designation("Flagyl sirop").categorie("Dépositaire").prix(700).structureId("poste-1").actif(true).build();
        when(depot.findByStructureIdOrderByCategorieAscDesignationAsc("poste-1")).thenReturn(new ArrayList<>(List.of(existant)));

        ResultatImportReferentiel r = service.importer(classeur(new Object[][]{
                {"Service", "Prestation", "Prix (FCFA)"},
                {"DEPOSITAIRE", "FLAGYL SIROP", null},
                {"ICP", "Flagyl sirop", null},
        }), "poste-1", true, null);

        assertEquals(1, r.getMisAJour());
        assertEquals(1, r.getCrees());
        assertEquals(700, existant.getPrix(), 0.001, "prix vide = prix inchangé");
        PrestationHorsCsu icp = tarif("ICP", "Flagyl sirop");
        assertNotEquals("FLAGYL_SIROP", icp.getCode(), "même nom dans un autre service : un autre code");
    }

    @Test
    void lesServicesRaresSontSignales() throws Exception {
        List<Object[]> lignes = new ArrayList<>();
        lignes.add(new Object[]{"Service", "Prestation", "Prix (FCFA)"});
        for (int i = 0; i < 8; i++) lignes.add(new Object[]{"ICP", "Acte " + i, 100});
        lignes.add(new Object[]{"Diclo. Cp", "Seringue 10cc", 100});
        ResultatImportReferentiel r = service.importer(classeur(lignes.toArray(new Object[0][])), "poste-1", true, null);

        assertTrue(r.getAvertissements().stream().anyMatch(a -> a.contains("« Diclo. Cp » (1 ligne)")),
                r.getAvertissements().toString());
        assertTrue(r.getAvertissements().stream().noneMatch(a -> a.contains("« ICP »")));
    }

    @Test
    void lAncienModeleCodeDesignationCategoriePrixResteAccepte() throws Exception {
        ResultatImportReferentiel r = service.importer(classeur(new Object[][]{
                {"Code", "Designation", "Categorie", "Prix"},
                {"CONS_GEN", "Consultation générale", "Consultation", 1000},
        }), "poste-1", true, null);
        assertEquals(1, r.getCrees());
        PrestationHorsCsu t = tarif("Consultation", "Consultation générale");
        assertEquals("CONS_GEN", t.getCode());
        assertEquals(1000, t.getPrix(), 0.001);
    }
}
