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

    // Repositories mockés : ils ne sont interrogés que lorsque la facture porte un
    // structureSanitaireId, ce qui n'est pas le cas des factures de ce test.
    private final FactureStructureExportService service = new FactureStructureExportService(
            org.mockito.Mockito.mock(com.csu.pharmacie.repository.StructureSanitaireRepository.class),
            org.mockito.Mockito.mock(com.csu.pharmacie.repository.RegionRepository.class));

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

    private LigneFactureStructure prestation(String dossierId, String designation, LocalDate date, double montant) {
        return LigneFactureStructure.builder()
                .dossierId(dossierId)
                .designation(designation)
                .service("Médecine générale")
                .quantite(1)
                .prixUnitaire(montant)
                .montant(montant)
                .montantSencsu(montant * 0.8)
                .montantBeneficiaire(montant * 0.2)
                .patientPrenom("Awa")
                .patientNom("Diop")
                .patientMatricule("MAT-123")
                .datePriseEnCharge(date)
                .build();
    }

    /** Lignes du tableau nominatif dont la première cellule porte ce prénom. */
    private List<Row> lignesDuPatient(Sheet sheet, String prenom) {
        List<Row> lignes = new ArrayList<>();
        for (Row row : sheet) {
            Cell premiere = row.getCell(0);
            if (premiere != null && premiere.getCellType() == CellType.STRING && prenom.equals(premiere.getStringCellValue())) {
                lignes.add(row);
            }
        }
        return lignes;
    }

    @Test
    void pdfZeroCinqAnsCompteUnPatientPourUnEnfantATroisPrestations() throws Exception {
        List<LigneFactureStructure> lignes = new ArrayList<>(List.of(
                prestation("dossier-1", "Consultation", LocalDate.of(2026, 10, 3), 1400),
                prestation("dossier-1", "Paracétamol sirop", LocalDate.of(2026, 10, 3), 0),
                prestation("dossier-1", "Test de diagnostic rapide", LocalDate.of(2026, 10, 3), 0)));
        FactureStructure facture = FactureStructure.builder()
                .numero("FS-261001-ENFANT").structureNom("PS Test").regime(Regime.ZERO_CINQ_ANS)
                .mois(10).annee(2026).lignes(lignes)
                .montantTotal(1400).montantTotalSencsu(1400)
                .build();

        com.itextpdf.text.pdf.PdfReader lecteur = new com.itextpdf.text.pdf.PdfReader(service.exportFactureStructureEnfantsPdf(facture));
        StringBuilder texte = new StringBuilder();
        for (int page = 1; page <= lecteur.getNumberOfPages(); page++) {
            texte.append(com.itextpdf.text.pdf.parser.PdfTextExtractor.getTextFromPage(lecteur, page)).append('\n');
        }
        lecteur.close();
        String contenu = texte.toString();
        assertTrue(contenu.contains("1 patient ") || contenu.contains("1 patient\n") || contenu.contains("1 patient   "),
                "un enfant à trois prestations est un seul patient : " + contenu);
        assertTrue(contenu.contains("3 prestations"), "les trois prestations restent comptées : " + contenu);
        assertFalse(contenu.contains("3 patients"), "il ne doit pas compter trois patients : " + contenu);
    }

    @Test
    void regimeClassiqueCelluleSepareeParPrestationPatientFusionneSansAdresse() throws Exception {
        // Même patiente, 3 prestations : 2 sur la première visite (dossier-1), 1 sur la deuxième (dossier-2).
        List<LigneFactureStructure> lignes = new ArrayList<>(List.of(
                prestation("dossier-1", "Consultation", LocalDate.of(2026, 10, 3), 1000),
                prestation("dossier-2", "Consultation", LocalDate.of(2026, 10, 20), 1000),
                prestation("dossier-1", "Pansement", LocalDate.of(2026, 10, 3), 500)));
        FactureStructure facture = FactureStructure.builder()
                .numero("FS-261001-ABCDEF").structureNom("PS Test").regime(Regime.CONTRIBUTIF)
                .mois(10).annee(2026).lignes(lignes)
                .montantTotal(2500).montantTotalSencsu(2000).montantTotalBeneficiaire(500)
                .build();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(service.exportFactureStructureExcel(facture)))) {
            Sheet sheet = wb.getSheetAt(0);
            // Le prénom n'apparaît qu'en haut de chaque visite (cellule fusionnée) : deux visites.
            List<Row> visites = lignesDuPatient(sheet, "Awa");
            assertEquals(2, visites.size(), "le patient n'est pas dupliqué : une entête de bloc par visite");

            List<String> entetes = new ArrayList<>();
            Row ligneEntete = sheet.getRow(visites.get(0).getRowNum() - 1);
            ligneEntete.forEach(c -> entetes.add(c.getStringCellValue()));

            assertFalse(entetes.contains("Adresse"), "l'adresse doit être enlevée du régime classique");
            assertFalse(entetes.stream().anyMatch(e -> e.toLowerCase().contains("registre")), "le numéro de registre ne doit pas figurer dans le régime classique");
            assertFalse(entetes.stream().anyMatch(e -> e.toLowerCase().contains("accompagnant")), "l'accompagnant ne doit pas figurer dans le régime classique");
            assertFalse(entetes.stream().anyMatch(e -> e.toLowerCase().contains("diagnostic")), "le diagnostic ne doit pas figurer dans le régime classique");

            int cPrenom = entetes.indexOf("Prénom(s)");
            int cPrestation = entetes.indexOf("Prestation(s)");
            int cPu = entetes.indexOf("P.U");
            int cMontant = entetes.indexOf("Montant Total");

            // Visite 1 (dossier-1) : deux prestations, chacune dans sa PROPRE ligne/cellule.
            Row v1p1 = visites.get(0);
            Row v1p2 = sheet.getRow(v1p1.getRowNum() + 1);
            assertEquals("Consultation", v1p1.getCell(cPrestation).getStringCellValue());
            assertEquals(1000, v1p1.getCell(cPu).getNumericCellValue(), 0.001);
            assertEquals(1000, v1p1.getCell(cMontant).getNumericCellValue(), 0.001);
            assertEquals("Pansement", v1p2.getCell(cPrestation).getStringCellValue());
            assertEquals(500, v1p2.getCell(cPu).getNumericCellValue(), 0.001);
            assertEquals(500, v1p2.getCell(cMontant).getNumericCellValue(), 0.001);

            // L'identité est fusionnée verticalement : le prénom n'est écrit qu'en haut du bloc.
            assertEquals("Awa", v1p1.getCell(cPrenom).getStringCellValue());
            Cell prenomCouvert = v1p2.getCell(cPrenom);
            assertTrue(prenomCouvert == null || prenomCouvert.getStringCellValue().isEmpty(),
                    "la cellule prénom de la 2e prestation est couverte par la fusion");
            assertTrue(
                    sheet.getMergedRegions().stream().anyMatch(rg -> rg.getFirstColumn() == cPrenom
                            && rg.getFirstRow() == v1p1.getRowNum() && rg.getLastRow() == v1p1.getRowNum() + 1),
                    "le prénom doit être fusionné sur les deux prestations de la visite");

            // Visite 2 (dossier-2) : une seule prestation.
            Row v2 = visites.get(1);
            assertEquals("Consultation", v2.getCell(cPrestation).getStringCellValue());
            assertEquals(1000, v2.getCell(cPu).getNumericCellValue(), 0.001);
            assertEquals(1000, v2.getCell(cMontant).getNumericCellValue(), 0.001);

            // Ligne des totaux : somme de toutes les prestations.
            Row totaux = sheet.getRow(v2.getRowNum() + 1);
            assertEquals("TOTAUX", totaux.getCell(0).getStringCellValue());
            assertEquals(2500, totaux.getCell(cMontant).getNumericCellValue(), 0.001);
        }

        // Le PDF (rowspan iText) et le Word (vMerge) doivent aussi se générer sans erreur sur le
        // cas multi-prestations avec fusion verticale.
        byte[] pdf = service.exportFactureStructurePdf(facture);
        assertNotNull(pdf);
        assertTrue(pdf.length > 0, "PDF vide");
        byte[] word = service.exportFactureStructureWord(facture);
        assertNotNull(word);
        assertTrue(word.length > 0, "Word vide");

        // PDF « Liste + Facture » (PdfFactureEnfants.listeNominative, rowspan iText) : même cas.
        byte[] listeEtFacture = service.exportFactureStructureEnfantsPdf(facture);
        assertNotNull(listeEtFacture);
        assertTrue(listeEtFacture.length > 0, "PDF Liste + Facture vide");

        // Hors 0-5 ans, les colonnes du modèle enfants (registre, adresse, accompagnant, diagnostic)
        // sont retirées de la liste « Liste + Facture ».
        com.itextpdf.text.pdf.PdfReader reader = new com.itextpdf.text.pdf.PdfReader(listeEtFacture);
        StringBuilder txt = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            txt.append(com.itextpdf.text.pdf.parser.PdfTextExtractor.getTextFromPage(reader, page)).append('\n');
        }
        reader.close();
        String contenuPdf = txt.toString();
        assertFalse(contenuPdf.contains("registre"), "pas de colonne registre hors 0-5 ans : " + contenuPdf);
        assertFalse(contenuPdf.contains("Adresse"), "pas de colonne adresse hors 0-5 ans");
        assertFalse(contenuPdf.contains("accompagnant"), "pas de colonne accompagnant hors 0-5 ans");
        assertFalse(contenuPdf.contains("Diagnostic"), "pas de colonne diagnostic hors 0-5 ans");
        assertTrue(contenuPdf.contains("Consultation") && contenuPdf.contains("Pansement"),
                "les prestations figurent bien dans la liste");
    }
}
