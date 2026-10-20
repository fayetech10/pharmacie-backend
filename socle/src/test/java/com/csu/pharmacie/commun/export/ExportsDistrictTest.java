package com.csu.pharmacie.commun.export;

import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.TypeStructure;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exports du district : le centre de santé rattaché figure même sans facture du régime
 * filtré (demande du 07/10/2026 — la situation 0-5 ans ne montrait que les postes).
 */
class ExportsDistrictTest {

    private final StructureDto poste = StructureDto.builder().id("ps-1").nom("Lalane").typeStructure(TypeStructure.PS)
            .structureRattachementNom("Centre de Sante de Dixieme").actif(true).build();
    private final StructureDto centre = StructureDto.builder().id("cs-1").nom("Centre de Sante de Dixieme")
            .typeStructure(TypeStructure.CS).actif(true).build();
    private final StructureDto inactif = StructureDto.builder().id("ps-2").nom("Poste ferme").typeStructure(TypeStructure.PS)
            .actif(false).build();
    private final List<StructureDto> structures = List.of(poste, centre, inactif);

    private List<FactureStructure> facturesDuPosteSeulement() {
        LigneFactureStructure ligne = LigneFactureStructure.builder().dossierId("d1").designation("Consultation")
                .quantite(1).prixUnitaire(1400).montant(1400).montantSencsu(1400)
                .patientPrenom("Awa").patientNom("Diop").datePriseEnCharge(LocalDate.of(2026, 10, 3)).build();
        return List.of(FactureStructure.builder().id("f1").numero("FS-202610-00007").structureSanitaireId("ps-1")
                .structureNom("Lalane").regime(Regime.ZERO_CINQ_ANS).mois(10).annee(2026).statut(StatutFacture.BROUILLON)
                .lignes(new ArrayList<>(List.of(ligne))).montantTotal(1400).montantTotalSencsu(1400).build());
    }

    private static String texte(byte[] pdf) throws Exception {
        PdfReader lecteur = new PdfReader(pdf);
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p <= lecteur.getNumberOfPages(); p++) {
            sb.append(PdfTextExtractor.getTextFromPage(lecteur, p)).append('\n');
        }
        lecteur.close();
        return sb.toString();
    }

    private Map<String, StructureDto> annuaire() {
        return structures.stream().collect(Collectors.toMap(StructureDto::getId, Function.identity()));
    }

    @Test
    void situationGlobaleEnfantsListeAussiLeCentreSansFacture() throws Exception {
        String contenu = texte(new GenerateurPdfEnfantsDistrict().situationGlobale(facturesDuPosteSeulement(), annuaire(),
                new GenerateurPdfEnfantsDistrict.Contexte("District sanitaire Thiès", "Thiès", 10, 2026)));
        assertTrue(contenu.contains("CS Centre de Sante de Dixieme"), contenu);
        assertTrue(contenu.contains("Sous total (CS)"), contenu);
        assertTrue(contenu.contains("PS Lalane"), contenu);
        assertTrue(!contenu.contains("Poste ferme"), "une structure inactive n'y figure pas");
    }

    @Test
    void listeNominativeEnfantsRecapituleAussiLeCentre() throws Exception {
        String contenu = texte(new GenerateurPdfEnfantsDistrict().listeNominative(facturesDuPosteSeulement(), annuaire(),
                new GenerateurPdfEnfantsDistrict.Contexte("District sanitaire Thiès", "Thiès", 10, 2026)));
        assertTrue(contenu.contains("RÉCAPITULATIF PAR STRUCTURE"), contenu);
        assertTrue(contenu.contains("CS Centre de Sante de Dixieme"), contenu);
    }

    @Test
    void listeNominativeEnfantsSepareLesPrestationsDUneMemeVisite() throws Exception {
        // Un enfant, une visite, deux prestations → deux lignes (cellules séparées), générées sans erreur.
        LigneFactureStructure p1 = LigneFactureStructure.builder().dossierId("d9").designation("Consultation")
                .quantite(1).prixUnitaire(1400).montant(1400).montantSencsu(1400)
                .patientPrenom("Awa").patientNom("Diop").datePriseEnCharge(LocalDate.of(2026, 10, 3)).build();
        LigneFactureStructure p2 = LigneFactureStructure.builder().dossierId("d9").designation("Paracétamol sirop")
                .quantite(1).prixUnitaire(0).montant(0).montantSencsu(0)
                .patientPrenom("Awa").patientNom("Diop").datePriseEnCharge(LocalDate.of(2026, 10, 3)).build();
        List<FactureStructure> factures = List.of(FactureStructure.builder().id("f9").numero("FS-202610-00099")
                .structureSanitaireId("ps-1").structureNom("Lalane").regime(Regime.ZERO_CINQ_ANS).mois(10).annee(2026)
                .statut(StatutFacture.BROUILLON).lignes(new ArrayList<>(List.of(p1, p2)))
                .montantTotal(1400).montantTotalSencsu(1400).build());

        String contenu = texte(new GenerateurPdfEnfantsDistrict().listeNominative(factures, annuaire(),
                new GenerateurPdfEnfantsDistrict.Contexte("District sanitaire Thiès", "Thiès", 10, 2026)));
        assertTrue(contenu.contains("Consultation") && contenu.contains("Paracétamol sirop"),
                "les deux prestations de la visite figurent : " + contenu);
    }

    @Test
    void listesNominativesPlacentLesDerniersEnregistresEnTete() throws Exception {
        // Octobre : Awa enregistrée avant Modou. Septembre : Binta. Factures passées dans le désordre.
        LigneFactureStructure awa = LigneFactureStructure.builder().dossierId("d1").designation("Consultation")
                .quantite(1).prixUnitaire(1400).montant(1400).montantSencsu(1400)
                .patientPrenom("Awa").patientNom("Diop").datePriseEnCharge(LocalDate.of(2026, 10, 3)).build();
        LigneFactureStructure modou = LigneFactureStructure.builder().dossierId("d2").designation("Consultation")
                .quantite(1).prixUnitaire(1400).montant(1400).montantSencsu(1400)
                .patientPrenom("Modou").patientNom("Fall").datePriseEnCharge(LocalDate.of(2026, 10, 5)).build();
        LigneFactureStructure binta = LigneFactureStructure.builder().dossierId("d0").designation("Consultation")
                .quantite(1).prixUnitaire(1400).montant(1400).montantSencsu(1400)
                .patientPrenom("Binta").patientNom("Sarr").datePriseEnCharge(LocalDate.of(2026, 9, 20)).build();
        FactureStructure octobre = FactureStructure.builder().id("f10").numero("FS-202610-00010").structureSanitaireId("ps-1")
                .structureNom("Lalane").regime(Regime.ZERO_CINQ_ANS).mois(10).annee(2026).statut(StatutFacture.BROUILLON)
                .lignes(new ArrayList<>(List.of(awa, modou))).montantTotal(2800).montantTotalSencsu(2800).build();
        FactureStructure septembre = FactureStructure.builder().id("f09").numero("FS-202609-00004").structureSanitaireId("ps-1")
                .structureNom("Lalane").regime(Regime.ZERO_CINQ_ANS).mois(9).annee(2026).statut(StatutFacture.BROUILLON)
                .lignes(new ArrayList<>(List.of(binta))).montantTotal(1400).montantTotalSencsu(1400).build();

        String district = texte(new GenerateurPdfEnfantsDistrict().listeNominative(List.of(octobre, septembre), annuaire(),
                new GenerateurPdfEnfantsDistrict.Contexte("District sanitaire Thiès", "Thiès", null, 2026)));
        assertTrue(district.indexOf("Modou") >= 0 && district.indexOf("Modou") < district.indexOf("Awa")
                && district.indexOf("Awa") < district.indexOf("Binta"), "district : " + district);

        String listeFacture = texte(com.csu.pharmacie.utils.PdfFactureEnfants.plusieurs(List.of(
                new com.csu.pharmacie.utils.PdfFactureEnfants.FactureAImprimer(octobre,
                        com.csu.pharmacie.utils.PdfFactureEnfants.Structure.de("Lalane", null, "Thiès")))));
        assertTrue(listeFacture.indexOf("Modou") >= 0 && listeFacture.indexOf("Modou") < listeFacture.indexOf("Awa"),
                "Liste + facture : " + listeFacture);
    }

    @Test
    void situationDistrictEtClasseurListentAussiLeCentre() throws Exception {
        EnteteDocument entete = EnteteDocument.builder().titre("Situation").districtNom("District sanitaire Thiès")
                .periode("Octobre 2026").regimeLibelle("Enfants de moins de 5 ans").build();
        String pdf = texte(new GenerateurPdf().situationDistrict(facturesDuPosteSeulement(), structures, entete));
        assertTrue(pdf.contains("Centre de Sante de Dixieme"), pdf);

        List<String> synthese = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(
                new GenerateurExcel().consolidationDistrict(facturesDuPosteSeulement(), structures, entete)))) {
            for (Row row : wb.getSheet("Synthèse")) {
                for (Cell c : row) if (c.getCellType() == CellType.STRING) synthese.add(c.getStringCellValue());
            }
        }
        // Le centre figure au tableau (et dans la liste « à relancer »), le poste aussi.
        assertTrue(synthese.stream().filter("Centre de Sante de Dixieme"::equals).count() >= 2, synthese.toString());
        assertTrue(synthese.contains("Lalane"), synthese.toString());
    }

    @Test
    void situationEnfantsSansFeuillesDeSyntheseEtPdfDeTousLesPostesEtCentres() throws Exception {
        EnteteDocument entete = EnteteDocument.builder().titre("Situation 0-5 ans").districtNom("District sanitaire Thiès")
                .periode("Octobre 2026").regimeLibelle("Enfants de moins de 5 ans").build();
        List<FactureStructure> factures = new ArrayList<>(facturesDuPosteSeulement());
        LigneFactureStructure ligneCentre = LigneFactureStructure.builder().dossierId("d2").designation("Consultation")
                .quantite(1).prixUnitaire(1400).montant(1400).montantSencsu(1400)
                .patientPrenom("Modou").patientNom("Fall").datePriseEnCharge(LocalDate.of(2026, 10, 5)).build();
        factures.add(FactureStructure.builder().id("f2").numero("FS-202610-00009").structureSanitaireId("cs-1")
                .structureNom("Centre de Sante de Dixieme").districtNom("District sanitaire Thiès")
                .regime(Regime.ZERO_CINQ_ANS).mois(10).annee(2026).statut(StatutFacture.BROUILLON)
                .lignes(new ArrayList<>(List.of(ligneCentre))).montantTotal(1400).montantTotalSencsu(1400).build());

        // Excel : seulement les feuilles par structure.
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(
                new GenerateurExcel().consolidationDistrict(factures, structures, entete, false)))) {
            List<String> feuilles = new ArrayList<>();
            wb.forEach(f -> feuilles.add(f.getSheetName()));
            assertTrue(feuilles.stream().noneMatch(n -> n.equals("Synthèse") || n.equals("Facturation") || n.equals("Par régime")),
                    feuilles.toString());
            assertTrue(feuilles.size() == 2, "une feuille par structure facturée : " + feuilles);
        }

        // PDF : la « Liste + facture » de chaque structure, à la suite (2 pages chacune).
        byte[] pdf = com.csu.pharmacie.utils.PdfFactureEnfants.plusieurs(factures.stream()
                .map(f -> new com.csu.pharmacie.utils.PdfFactureEnfants.FactureAImprimer(f,
                        com.csu.pharmacie.utils.PdfFactureEnfants.Structure.de(f.getStructureNom(), null, "Thiès")))
                .toList());
        PdfReader lecteur = new PdfReader(pdf);
        int pages = lecteur.getNumberOfPages();
        lecteur.close();
        String contenu = texte(pdf);
        assertTrue(pages == 4, "page de garde + liste par structure : " + pages);
        assertTrue(contenu.contains("CENTRE DE SANTE DE DIXIEME") && contenu.contains("LALANE"), contenu);
        assertTrue(!contenu.contains("DISTRICT DISTRICT"), "le mot « DISTRICT » n'est plus doublé : " + contenu);
    }
}
