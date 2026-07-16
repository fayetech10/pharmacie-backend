package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Valide la génération réelle de l'état récapitulatif nominatif pour chaque régime
 * (aucune exception POI, colonne Sexe présente, adaptation H/F ↔ M/F).
 */
class FactureStructureExportServiceTest {

    private final FactureStructureExportService service = new FactureStructureExportService();

    private FactureStructure factureAvecUneLigne(Regime regime, String sexe) {
        LigneFactureStructure ligne = LigneFactureStructure.builder()
                .designation("Consultation générale")
                .quantite(2)
                .prixUnitaire(5000)
                .montant(10000)
                .montantBeneficiaire(2000)
                .montantSencsu(8000)
                .patientPrenom("Awa")
                .patientNom("Diop")
                .patientSexe(sexe)
                .patientTelephone("770000000")
                .patientAdresse("Thiès")
                .patientMatricule("MAT-123")
                .patientNumeroCni("CNI-999")
                .patientDateNaissance(LocalDate.of(1990, 5, 12))
                .datePriseEnCharge(LocalDate.of(2026, 10, 3))
                .numeroRegistre("REG-7")
                .ircIra("IRC")
                .indicationCbt("Souffrance fœtale")
                .numeroRegistreBloc("BLOC-4")
                .dateHeureIntervention("03/10/2026 14:30")
                .dureeHospitalisationJours(3)
                .build();

        List<LigneFactureStructure> lignes = new ArrayList<>();
        lignes.add(ligne);

        return FactureStructure.builder()
                .numero("FS-260701-ABCDEF")
                .structureNom("Centre de Santé de Thiès")
                .regime(regime)
                .mois(10)
                .annee(2026)
                .lignes(lignes)
                .montantTotal(10000)
                .montantTotalBeneficiaire(2000)
                .montantTotalSencsu(8000)
                .build();
    }

    private List<String> texteDesCellules(byte[] bytes) throws Exception {
        List<String> valeurs = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.STRING) {
                        valeurs.add(cell.getStringCellValue());
                    }
                }
            }
        }
        return valeurs;
    }

    @Test
    void exportGenereUnClasseurValidePourChaqueRegime() throws Exception {
        for (Regime regime : Regime.values()) {
            byte[] bytes = service.exportFactureStructureExcel(factureAvecUneLigne(regime, "F"));
            assertNotNull(bytes, "export null pour " + regime);
            assertTrue(bytes.length > 0, "export vide pour " + regime);

            List<String> cellules = texteDesCellules(bytes);
            // Un titre « état/liste nominatif(ve) » figure dans l'en-tête
            assertTrue(cellules.stream().anyMatch(v -> v.contains("NOMINATIF") || v.contains("NOMINATIVE")),
                    "titre nominatif manquant pour " + regime);
            // La colonne Sexe (H/F ou M/F) est toujours présente
            assertTrue(cellules.stream().anyMatch(v -> v.startsWith("Sexe")),
                    "colonne Sexe manquante pour " + regime);
            // Le prénom du bénéficiaire apparaît bien dans les données
            assertTrue(cellules.contains("Awa"), "donnée patient manquante pour " + regime);
        }
    }

    @Test
    void sexeAfficheHFsaufEnfantsQuiUtilisentMF() throws Exception {
        // Sexe stocké de façon canonique en "M" : rendu "H" partout, sauf enfants 0-5 ans → "M".
        List<String> classique = texteDesCellules(
                service.exportFactureStructureExcel(factureAvecUneLigne(Regime.CONTRIBUTIF, "M")));
        assertTrue(classique.contains("H"), "le régime classique doit afficher H pour un homme");

        List<String> enfants = texteDesCellules(
                service.exportFactureStructureExcel(factureAvecUneLigne(Regime.ZERO_CINQ_ANS, "M")));
        assertTrue(enfants.contains("M"), "le régime enfants doit afficher M pour un masculin");
    }
}
