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
 * Prestations par défaut des postes de santé (liste fournie le 14/10/2026) : lignes nationales
 * « PS », vues des seuls postes, importées par l'administration.
 */
class PrestationsParDefautPostesTest {

    private final DepotPrestationHorsCsu depot = mock(DepotPrestationHorsCsu.class);
    private final ServiceHorsCsu service = new ServiceHorsCsu(depot);
    private final List<PrestationHorsCsu> enregistres = new ArrayList<>();

    private static PrestationHorsCsu ligne(String code, String service, String designation, double prix,
                                           String structureId, String type) {
        return PrestationHorsCsu.builder().id(code + "-" + structureId).code(code).categorie(service)
                .designation(designation).prix(prix).structureId(structureId).typeStructure(type).actif(true).build();
    }

    private final PrestationHorsCsu commun = ligne("CONS", "Consultation", "Consultation générale", 1000, null, null);
    private final PrestationHorsCsu posteTarife = ligne("CONSULTATION_CPC", "ICP", "Consultation : CPC", 500, null, "PS");
    private final PrestationHorsCsu posteATarifer = ligne("ACT_ADULTE_COMPRIME", "Dépositaire", "ACT ADULTE Comprimé", 0, null, "PS");

    PrestationsParDefautPostesTest() {
        when(depot.applicablesA(anyString())).thenReturn(List.of(commun, posteTarife, posteATarifer));
        when(depot.findByStructureIdOrderByCategorieAscDesignationAsc(anyString())).thenReturn(new ArrayList<>());
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
            return new MockMultipartFile("fichier", "listeServicesPrestation.xlsx", null, out.toByteArray());
        }
    }

    @Test
    void unPosteVoitSesPrestationsParDefautTarifees() {
        List<PrestationHorsCsu> facturables = service.applicables("poste-1", true);
        assertTrue(facturables.contains(posteTarife), "prestation par défaut tarifée : facturable par le poste");
        assertFalse(facturables.contains(posteATarifer), "sans prix : le poste doit d'abord fixer le sien");
        assertTrue(service.catalogue("poste-1", true).contains(posteATarifer), "mais proposée à tarifer");
    }

    @Test
    void unCentreNeVoitPasLesPrestationsDesPostes() {
        assertEquals(List.of(commun), service.applicables("centre-1", false));
        assertEquals(List.of(commun), service.catalogue("centre-1", false));
    }

    @Test
    void lImportAdministrateurAlimenteLaListeDesPostes() throws Exception {
        when(depot.findAll()).thenReturn(List.of(commun));

        ResultatImportReferentiel r = service.importer(classeur(new Object[][]{
                {"Service", "Prestation", "Prix (FCFA)"},
                {"ICP", "Consultation : CPC", 500},
                {"Dépositaire", "ACT ADULTE Comprimé", null},
        }), null, false, ServiceHorsCsu.POSTES);

        assertEquals(2, r.getCrees());
        assertTrue(enregistres.stream().allMatch(t -> t.getStructureId() == null && "PS".equals(t.getTypeStructure())),
                "lignes nationales réservées aux postes");
    }

    @Test
    void reimporterMetAJourLaListeDesPostesSansToucherAuNationalCommun() throws Exception {
        PrestationHorsCsu existant = ligne("CONSULTATION_CPC", "ICP", "Consultation : CPC", 500, null, "PS");
        PrestationHorsCsu homonymeCommun = ligne("CONSULTATION_CPC_2", "ICP", "Consultation : CPC", 300, null, null);
        when(depot.findAll()).thenReturn(List.of(existant, homonymeCommun));

        ResultatImportReferentiel r = service.importer(classeur(new Object[][]{
                {"Service", "Prestation", "Prix (FCFA)"},
                {"ICP", "Consultation : CPC", 600},
        }), null, false, ServiceHorsCsu.POSTES);

        assertEquals(1, r.getMisAJour());
        assertEquals(600, existant.getPrix(), 0.001);
        assertEquals(300, homonymeCommun.getPrix(), 0.001, "le national commun n'est pas réécrit");
    }

    @Test
    void lePosteQuiFixeSonPrixReprendLeCodeNationalEtLeSurcharge() throws Exception {
        service.importer(classeur(new Object[][]{
                {"Service", "Prestation", "Prix (FCFA)"},
                {"Dépositaire", "ACT ADULTE Comprimé", 1200},
        }), "poste-1", true, null);

        PrestationHorsCsu sien = enregistres.get(0);
        assertEquals("ACT_ADULTE_COMPRIME", sien.getCode(), "même code que la ligne nationale : elle la surcharge");
        assertEquals("poste-1", sien.getStructureId());
        assertNull(sien.getTypeStructure());
    }
}
