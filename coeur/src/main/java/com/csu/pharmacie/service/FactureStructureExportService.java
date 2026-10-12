package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Region;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.repository.RegionRepository;
import com.csu.pharmacie.repository.StructureSanitaireRepository;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Image;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import com.itextpdf.text.pdf.draw.LineSeparator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.csu.pharmacie.utils.ColonnesFactureStructure;
import com.csu.pharmacie.utils.PdfFactureEnfants;
import com.csu.pharmacie.utils.ColonnesFactureStructure.Colonne;
import com.csu.pharmacie.utils.NombreEnLettres;

/**
 * Export d'une facture de structure sanitaire au format « état récapitulatif nominatif »
 * du classeur « GESTION DES FACTURES CS_EPS » : les colonnes générées dépendent du régime
 * (un modèle par onglet). Utilisé aussi bien par l'espace Structure que par le Service Régional.
 *
 * Design aligné sur la facture de pharmacie (thème vert SEN-CSU).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FactureStructureExportService {

    private final StructureSanitaireRepository structureSanitaireRepository;
    private final RegionRepository regionRepository;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] MOIS = {"", "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
            "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre"};

    // ===================== Export Excel =====================

    public byte[] exportFactureStructureExcel(FactureStructure facture) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Regime regime = facture.getRegime();
            List<Colonne> colonnes = colonnesPourRegime(regime);
            int nbCol = colonnes.size();
            int dernierCol = Math.max(1, nbCol - 1);

            Sheet sheet = workbook.createSheet(titreCourt(regime));

            // Résoudre les infos de la structure
            InfosStructure infos = resoudreInfosStructure(facture);
            String structureNom = infos.nom();
            String localite = infos.localite();
            String codeStructure = infos.code();
            String adresse = infos.adresse();
            String telephone = infos.telephone();

            String dateFacture = facture.getCreatedAt() != null
                    ? facture.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            // ── Couleurs ──
            byte[] rgbGreen = new byte[]{(byte) 47, (byte) 110, (byte) 84};       // #2F6E54
            byte[] rgbLightGreen = new byte[]{(byte) 230, (byte) 244, (byte) 234}; // #E6F4EA
            byte[] rgbGray = new byte[]{(byte) 100, (byte) 100, (byte) 100};
            byte[] rgbDark = new byte[]{(byte) 51, (byte) 51, (byte) 51};

            XSSFColor csuGreen = new XSSFColor(rgbGreen, null);
            XSSFColor lightGreen = new XSSFColor(rgbLightGreen, null);
            XSSFColor textGray = new XSSFColor(rgbGray, null);
            XSSFColor textDark = new XSSFColor(rgbDark, null);

            // ── Styles ──
            XSSFFont nameFont = workbook.createFont();
            nameFont.setBold(true);
            nameFont.setFontHeightInPoints((short) 16);
            nameFont.setColor(csuGreen);
            CellStyle nameStyle = workbook.createCellStyle();
            nameStyle.setFont(nameFont);

            XSSFFont infoFont = workbook.createFont();
            infoFont.setFontHeightInPoints((short) 9);
            infoFont.setColor(textGray);
            CellStyle infoStyle = workbook.createCellStyle();
            infoStyle.setFont(infoFont);

            XSSFFont titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 20);
            titleFont.setColor(textDark);
            CellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setFont(titleFont);

            XSSFFont labelFont = workbook.createFont();
            labelFont.setBold(true);
            labelFont.setFontHeightInPoints((short) 9);
            labelFont.setColor(textGray);
            CellStyle labelStyle = workbook.createCellStyle();
            labelStyle.setFont(labelFont);

            XSSFFont valFont = workbook.createFont();
            valFont.setBold(true);
            valFont.setFontHeightInPoints((short) 9);
            valFont.setColor(csuGreen);
            CellStyle valStyle = workbook.createCellStyle();
            valStyle.setFont(valFont);

            XSSFFont clientLabelFont = workbook.createFont();
            clientLabelFont.setFontHeightInPoints((short) 8);
            clientLabelFont.setColor(textGray);
            CellStyle clientLabelStyle = workbook.createCellStyle();
            clientLabelStyle.setFont(clientLabelFont);
            clientLabelStyle.setAlignment(HorizontalAlignment.RIGHT);

            XSSFFont clientValFont = workbook.createFont();
            clientValFont.setBold(true);
            clientValFont.setFontHeightInPoints((short) 10);
            clientValFont.setColor(csuGreen);
            CellStyle clientValStyle = workbook.createCellStyle();
            clientValStyle.setFont(clientValFont);
            clientValStyle.setAlignment(HorizontalAlignment.RIGHT);

            // Sous-titre du régime
            XSSFFont subtitleFont = workbook.createFont();
            subtitleFont.setBold(true);
            subtitleFont.setFontHeightInPoints((short) 12);
            subtitleFont.setColor(textDark);
            XSSFCellStyle subtitleStyle = workbook.createCellStyle();
            subtitleStyle.setFont(subtitleFont);
            subtitleStyle.setAlignment(HorizontalAlignment.CENTER);
            subtitleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            // En-tête de tableau (vert + blanc)
            XSSFFont headFont = workbook.createFont();
            headFont.setBold(true);
            headFont.setFontHeightInPoints((short) 9);
            headFont.setColor(new XSSFColor(new byte[]{(byte) 255, (byte) 255, (byte) 255}, null));
            XSSFCellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(csuGreen);
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setFont(headFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setWrapText(true);
            setCellBorders(headerStyle);

            // Cellules de données
            XSSFFont bodyFont = workbook.createFont();
            bodyFont.setFontHeightInPoints((short) 8);
            bodyFont.setColor(textDark);

            CellStyle cellCenterStyle = workbook.createCellStyle();
            cellCenterStyle.setFont(bodyFont);
            cellCenterStyle.setAlignment(HorizontalAlignment.CENTER);
            cellCenterStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setCellBorders(cellCenterStyle);

            // Texte : une visite de plusieurs prestations les liste à la ligne dans la cellule
            // (retour à la ligne, haut de cellule pour que les listes restent alignées).
            CellStyle cellLeftStyle = workbook.createCellStyle();
            cellLeftStyle.setFont(bodyFont);
            cellLeftStyle.setAlignment(HorizontalAlignment.LEFT);
            cellLeftStyle.setVerticalAlignment(VerticalAlignment.TOP);
            cellLeftStyle.setWrapText(true);
            setCellBorders(cellLeftStyle);

            CellStyle cellRightStyle = workbook.createCellStyle();
            cellRightStyle.setFont(bodyFont);
            cellRightStyle.setAlignment(HorizontalAlignment.RIGHT);
            cellRightStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setCellBorders(cellRightStyle);

            // Cellule numérique
            CellStyle cellNumStyle = workbook.createCellStyle();
            cellNumStyle.setFont(bodyFont);
            cellNumStyle.setAlignment(HorizontalAlignment.RIGHT);
            cellNumStyle.setVerticalAlignment(VerticalAlignment.TOP);
            cellNumStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            setCellBorders(cellNumStyle);

            // ── 1. En-tête de la structure ──
            int r = 0;
            Row row0 = sheet.createRow(r);
            Cell nameCell = row0.createCell(0);
            nameCell.setCellValue(structureNom.toUpperCase());
            nameCell.setCellStyle(nameStyle);
            if (dernierCol > 0) sheet.addMergedRegion(new CellRangeAddress(r, r, 0, dernierCol));
            r++;

            String subHeader = (adresse.isEmpty() ? "" : adresse + "  ·  ")
                    + (localite.isEmpty() ? "" : localite + "  ·  ")
                    + (telephone.isEmpty() ? "" : "Tél : " + telephone);
            if (!subHeader.isEmpty()) {
                Row row1 = sheet.createRow(r);
                Cell infoCell = row1.createCell(0);
                infoCell.setCellValue(subHeader);
                infoCell.setCellStyle(infoStyle);
                if (dernierCol > 0) sheet.addMergedRegion(new CellRangeAddress(r, r, 0, dernierCol));
            }
            r++;
            r++; // ligne d'aération

            // ── 2. Titre « Facture » ──
            Row rowTitle = sheet.createRow(r);
            Cell cellTitle = rowTitle.createCell(0);
            cellTitle.setCellValue("Facture");
            cellTitle.setCellStyle(titleStyle);
            r++;
            r++; // ligne d'aération

            // ── 3. Métadonnées ──
            String textMois = mois(facture.getMois()) + " " + facture.getAnnee();
            createExcelMetaRow(sheet, r, "DATE :", textMois, labelStyle, valStyle);

            // CLIENT à droite
            int clientCol = Math.max(7, dernierCol);
            Row rowDate = sheet.getRow(r);
            Cell cLabel = rowDate.createCell(clientCol);
            cLabel.setCellValue("CLIENT");
            cLabel.setCellStyle(clientLabelStyle);
            r++;

            String formatNumFacture = String.format("FACT-STRUCT-%d-%02d", facture.getAnnee(), facture.getMois());
            createExcelMetaRow(sheet, r, "N° FACTURE :", formatNumFacture, labelStyle, valStyle);
            Row rowNum = sheet.getRow(r);
            Cell cVal1 = rowNum.createCell(clientCol);
            cVal1.setCellValue("Agence sénégalaise de la couverture sanitaire");
            cVal1.setCellStyle(clientValStyle);
            r++;

            createExcelMetaRow(sheet, r, "CODE :", codeStructure, labelStyle, valStyle);
            Row rowCode = sheet.getRow(r);
            Cell cVal2 = rowCode.createCell(clientCol);
            cVal2.setCellValue("universelle");
            cVal2.setCellStyle(clientValStyle);
            r++;

            createExcelMetaRow(sheet, r, "MONNAIE :", "FRANCS CFA", labelStyle, valStyle);
            r++;
            r++; // ligne d'aération

            // ── 4. Titre du régime ──
            cellMerge(sheet, r, dernierCol, titreLong(regime), subtitleStyle);
            r++;

            // Référence facture
            Row rowRef = sheet.createRow(r);
            Cell refCell = rowRef.createCell(0);
            refCell.setCellValue("N° Référence facture : " + (facture.getNumero() != null ? facture.getNumero() : ""));
            refCell.setCellStyle(infoStyle);
            if (dernierCol > 0) sheet.addMergedRegion(new CellRangeAddress(r, r, 0, dernierCol));
            r++;
            r++; // ligne d'aération

            // ── 5. En-têtes de colonnes ──
            Row entete = sheet.createRow(r++);
            entete.setHeightInPoints(32);
            for (int i = 0; i < nbCol; i++) {
                Cell cell = entete.createCell(i);
                cell.setCellValue(colonnes.get(i).entete());
                cell.setCellStyle(headerStyle);
            }

            // ── 6. Lignes de données ──
            // Une ligne par prestation : chaque prestation a ses propres cellules (prestation,
            // quantité, P.U, montant) ; l'identité du patient (et les colonnes communes) est
            // fusionnée verticalement sur toutes les prestations de la visite, sans la dupliquer.
            double[] totaux = new double[nbCol];
            List<LigneFactureStructure> lignes = facture.getLignes() != null ? facture.getLignes() : new ArrayList<>();
            for (List<LigneFactureStructure> visite : ColonnesFactureStructure.groupesPourRegime(regime, lignes)) {
                ColonnesFactureStructure.CelluleFusion[][] grille =
                        ColonnesFactureStructure.grilleVisite(visite, colonnes);
                int premiereLigne = r;
                for (int rr = 0; rr < grille.length; rr++) {
                    Row row = sheet.createRow(r++);
                    for (int i = 0; i < nbCol; i++) {
                        ColonnesFactureStructure.CelluleFusion cf = grille[rr][i];
                        Cell cell = row.createCell(i);
                        if (cf == null) {
                            cell.setCellStyle(cellLeftStyle); // cellule couverte par la fusion verticale.
                            continue;
                        }
                        Object val = cf.valeur();
                        if (val instanceof Number nombre) {
                            cell.setCellValue(nombre.doubleValue());
                            cell.setCellStyle(cellNumStyle);
                        } else {
                            cell.setCellValue(val != null ? val.toString() : "");
                            cell.setCellStyle(cellLeftStyle);
                        }
                        if (cf.rowspan() > 1) {
                            sheet.addMergedRegion(new CellRangeAddress(
                                    premiereLigne + rr, premiereLigne + rr + cf.rowspan() - 1, i, i));
                        }
                    }
                }
                for (int i = 0; i < nbCol; i++) {
                    if (colonnes.get(i).total()) {
                        totaux[i] += ColonnesFactureStructure.sommeVisite(colonnes.get(i), visite);
                    }
                }
            }

            // ── 7. Ligne des totaux ──
            boolean aTotal = colonnes.stream().anyMatch(Colonne::total);
            if (aTotal) {
                Row rowTot = sheet.createRow(r++);
                for (int i = 0; i < nbCol; i++) {
                    Cell cell = rowTot.createCell(i);
                    if (i == 0) {
                        cell.setCellValue("TOTAUX");
                    } else if (colonnes.get(i).total()) {
                        cell.setCellValue(totaux[i]);
                    }
                    cell.setCellStyle(headerStyle);
                }
            }

            // ── 8. Encadré récapitulatif ──
            r++; // ligne d'aération
            int boxStartRow = r;

            XSSFCellStyle boxStyle = workbook.createCellStyle();
            boxStyle.setFillForegroundColor(lightGreen);
            boxStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            boxStyle.setBorderTop(BorderStyle.MEDIUM);
            boxStyle.setBorderBottom(BorderStyle.MEDIUM);
            boxStyle.setBorderLeft(BorderStyle.MEDIUM);
            boxStyle.setBorderRight(BorderStyle.MEDIUM);
            boxStyle.setTopBorderColor(csuGreen);
            boxStyle.setBottomBorderColor(csuGreen);
            boxStyle.setLeftBorderColor(csuGreen);
            boxStyle.setRightBorderColor(csuGreen);

            // Position de l'encadré : dernières colonnes
            int boxCol = Math.max(nbCol - 3, 0);

            Row bRow1 = sheet.createRow(boxStartRow);
            Cell bCell1 = bRow1.createCell(boxCol);
            bCell1.setCellValue("MONTANT À RÉGLER PAR LA SEN-CSU");
            bCell1.setCellStyle(boxStyle);
            if (boxCol < dernierCol) {
                sheet.addMergedRegion(new CellRangeAddress(boxStartRow, boxStartRow, boxCol, dernierCol));
            }

            XSSFFont boxValFont = workbook.createFont();
            boxValFont.setBold(true);
            boxValFont.setFontHeightInPoints((short) 14);
            boxValFont.setColor(csuGreen);
            XSSFCellStyle boxValStyle = workbook.createCellStyle();
            boxValStyle.cloneStyleFrom(boxStyle);
            boxValStyle.setFont(boxValFont);
            boxValStyle.setAlignment(HorizontalAlignment.CENTER);

            Row bRow2 = sheet.createRow(boxStartRow + 1);
            Cell bCell2 = bRow2.createCell(boxCol);
            bCell2.setCellValue(formatNombre(facture.getMontantTotalSencsu()) + " FCFA");
            bCell2.setCellStyle(boxValStyle);
            if (boxCol < dernierCol) {
                sheet.addMergedRegion(new CellRangeAddress(boxStartRow + 1, boxStartRow + 1, boxCol, dernierCol));
            }
            r = boxStartRow + 2;

            // ── 9. Phrase d'arrêt ──
            r++; // ligne d'aération
            String amountInWords = NombreEnLettres.convertir(Math.round(facture.getMontantTotalSencsu()));
            if (!amountInWords.isEmpty()) {
                amountInWords = Character.toUpperCase(amountInWords.charAt(0)) + amountInWords.substring(1);
            }
            String footerText = "Arrêtée la présente facture, pour la part SEN-CSU, à la somme de " + amountInWords + " francs CFA.";
            Row fRow = sheet.createRow(r);
            Cell fCell = fRow.createCell(0);
            fCell.setCellValue(footerText);
            fCell.setCellStyle(infoStyle);
            if (dernierCol > 0) sheet.addMergedRegion(new CellRangeAddress(r, r, 0, dernierCol));

            // ── 10. Signature ──
            r += 2;
            Row sigRow1 = sheet.createRow(r);
            Cell sigCell1 = sigRow1.createCell(clientCol);
            sigCell1.setCellValue("--------------------------------------------------");
            sigCell1.setCellStyle(infoStyle);

            r++;
            Row sigRow2 = sheet.createRow(r);
            Cell sigCell2 = sigRow2.createCell(clientCol);
            sigCell2.setCellValue("Le Responsable (cachet et signature)");
            XSSFFont sigFont = workbook.createFont();
            sigFont.setFontHeightInPoints((short) 9);
            sigFont.setItalic(true);
            sigFont.setColor(textDark);
            CellStyle sigStyle = workbook.createCellStyle();
            sigStyle.setFont(sigFont);
            sigCell2.setCellStyle(sigStyle);

            // Ajuster les colonnes
            for (int i = 0; i < nbCol; i++) {
                sheet.autoSizeColumn(i);
                int largeur = sheet.getColumnWidth(i);
                if (largeur > 12000) sheet.setColumnWidth(i, 12000);
                if (largeur < 2800) sheet.setColumnWidth(i, 2800);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Erreur lors de l'export Excel de la facture structure", e);
            throw new RuntimeException("Erreur lors de l'export Excel", e);
        }
    }

    private void setCellBorders(CellStyle style) {
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private void createExcelMetaRow(Sheet sheet, int rowIdx, String label, String value,
                                    CellStyle labelStyle, CellStyle valStyle) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell cellLabel = row.createCell(0);
        cellLabel.setCellValue(label);
        cellLabel.setCellStyle(labelStyle);
        Cell cellVal = row.createCell(1);
        cellVal.setCellValue(value);
        cellVal.setCellStyle(valStyle);
    }

    // ===================== Colonnes par régime (classeur CS_EPS) =====================
    // Source unique : commun/.../utils/ColonnesFactureStructure, partagée avec les feuilles
    // « facture » de la consolidation district.

    private List<Colonne> colonnesPourRegime(Regime regime) {
        return ColonnesFactureStructure.pourRegime(regime);
    }

    private String dateNaissance(LigneFactureStructure l) {
        return ColonnesFactureStructure.dateNaissance(l);
    }

    private String datePriseEnCharge(LigneFactureStructure l) {
        return ColonnesFactureStructure.datePriseEnCharge(l);
    }

    private String matricule(LigneFactureStructure l) {
        return ColonnesFactureStructure.matricule(l);
    }

    private String sexe(LigneFactureStructure l, boolean enfants) {
        return ColonnesFactureStructure.sexe(l, enfants);
    }

    private String nonVide(String s) {
        return s != null ? s : "";
    }

    // ===================== Libellés =====================

    private String titreLong(Regime regime) {
        return ColonnesFactureStructure.titreLong(regime);
    }

    private String titreCourt(Regime regime) {
        return ColonnesFactureStructure.titreCourt(regime);
    }

    private String mois(int mois) {
        return (mois >= 1 && mois <= 12) ? MOIS[mois] : String.valueOf(mois);
    }

    private String formatMontant(double montant) {
        return formatNombre(montant);
    }

    private String formatNombre(double v) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        DecimalFormat df = new DecimalFormat("#,##0", symbols);
        return df.format(v);
    }

    // ===================== Utilitaires POI =====================

    private void cellMerge(Sheet sheet, int rowIdx, int lastCol, String value, CellStyle style) {
        Row row = sheet.createRow(rowIdx);
        Cell cell = row.createCell(0);
        cell.setCellValue(value);
        cell.setCellStyle(style);
        if (lastCol > 0) {
            sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, lastCol));
        }
    }

    /** Coordonnées de la structure imprimées en en-tête, résolues une seule fois pour Excel, PDF et Word. */
    private record InfosStructure(String nom, String code, String adresse, String telephone, String localite) {}

    private InfosStructure resoudreInfosStructure(FactureStructure facture) {
        String nom = facture.getStructureNom() != null ? facture.getStructureNom() : "Structure de santé";
        String localite = "";
        String code = "STR";
        String adresse = "";
        String telephone = "";
        if (facture.getStructureSanitaireId() != null) {
            StructureSanitaire structure = structureSanitaireRepository
                    .findById(facture.getStructureSanitaireId()).orElse(null);
            if (structure != null) {
                nom = structure.getNom() != null ? structure.getNom() : nom;
                code = structure.getCode() != null ? structure.getCode() : code;
                adresse = structure.getAdresse() != null ? structure.getAdresse() : "";
                telephone = structure.getTelephone() != null ? structure.getTelephone() : "";
                if (structure.getRegionId() != null) {
                    Region region = regionRepository.findById(structure.getRegionId()).orElse(null);
                    if (region != null) {
                        localite = region.getNom();
                    }
                }
            }
        }
        return new InfosStructure(nom, code, adresse, telephone, localite);
    }

    /** Sous-ligne « adresse · localité · Tél : xxx » de l'en-tête, commune aux trois formats. */
    private String sousEnteteStructure(InfosStructure infos) {
        return (infos.adresse().isEmpty() ? "" : infos.adresse() + "  ·  ")
                + (infos.localite().isEmpty() ? "" : infos.localite() + "  ·  ")
                + (infos.telephone().isEmpty() ? "" : "Tél : " + infos.telephone());
    }

    // ===================== Export PDF =====================

    public byte[] exportFactureStructurePdf(FactureStructure facture) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Résoudre les infos de la structure
            InfosStructure infos = resoudreInfosStructure(facture);
            String structureNom = infos.nom();
            String localite = infos.localite();
            String codeStructure = infos.code();
            String adresse = infos.adresse();
            String telephone = infos.telephone();

            Regime regime = facture.getRegime();
            List<Colonne> colonnes = colonnesPourRegime(regime);
            int nbCol = colonnes.size();

            Document document = new Document(PageSize.A4.rotate(), 20, 20, 20, 20);
            PdfWriter.getInstance(document, out);
            document.open();

            // ── Couleurs ──
            BaseColor csuGreen = new BaseColor(47, 110, 84);       // #2F6E54
            BaseColor lightGreen = new BaseColor(230, 244, 234);   // #E6F4EA
            BaseColor textDark = new BaseColor(51, 51, 51);        // #333333
            BaseColor textGray = new BaseColor(100, 100, 100);     // #666666
            BaseColor borderLight = new BaseColor(220, 220, 220);  // light gray

            // ── Polices ──
            Font nameFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD, csuGreen);
            Font infoFont = new Font(Font.FontFamily.HELVETICA, 8.5f, Font.NORMAL, textGray);
            Font titleFont = new Font(Font.FontFamily.HELVETICA, 24, Font.BOLD, new BaseColor(26, 26, 26));
            Font labelFont = new Font(Font.FontFamily.HELVETICA, 9.5f, Font.BOLD, textGray);
            Font valFont = new Font(Font.FontFamily.HELVETICA, 9.5f, Font.BOLD, csuGreen);
            Font clientLabelFont = new Font(Font.FontFamily.HELVETICA, 8f, Font.NORMAL, textGray);
            Font clientValFont = new Font(Font.FontFamily.HELVETICA, 10f, Font.BOLD, csuGreen);
            Font subtitleFont = new Font(Font.FontFamily.HELVETICA, 12f, Font.BOLD, textDark);
            Font headFont = new Font(Font.FontFamily.HELVETICA, 8f, Font.BOLD, BaseColor.WHITE);
            Font normalFont = new Font(Font.FontFamily.HELVETICA, 7.5f, Font.NORMAL, textDark);
            Font totalFont = new Font(Font.FontFamily.HELVETICA, 8f, Font.BOLD, BaseColor.WHITE);
            Font reglerLabelFont = new Font(Font.FontFamily.HELVETICA, 8.5f, Font.BOLD, csuGreen);
            Font reglerValFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD, csuGreen);
            Font footerFont = new Font(Font.FontFamily.HELVETICA, 8.5f, Font.ITALIC, textGray);
            Font signatureFont = new Font(Font.FontFamily.HELVETICA, 9f, Font.ITALIC, textDark);

            // ── 1. En-tête avec logo ──
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{1.5f, 8.5f});
            headerTable.getDefaultCell().setBorder(PdfPCell.NO_BORDER);

            PdfPCell logoCell = new PdfPCell();
            logoCell.setBorder(PdfPCell.NO_BORDER);
            logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            try {
                byte[] logoBytes = new ClassPathResource("logo-csu.png").getInputStream().readAllBytes();
                Image logoImg = Image.getInstance(logoBytes);
                logoImg.scaleToFit(50, 50);
                logoCell.addElement(logoImg);
            } catch (Exception e) {
                Paragraph fallbackCross = new Paragraph("✚", new Font(Font.FontFamily.HELVETICA, 36, Font.BOLD, csuGreen));
                logoCell.addElement(fallbackCross);
            }
            headerTable.addCell(logoCell);

            PdfPCell infoCell = new PdfPCell();
            infoCell.setBorder(PdfPCell.NO_BORDER);
            infoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            Paragraph pName = new Paragraph(structureNom.toUpperCase(), nameFont);
            infoCell.addElement(pName);

            String subHeader = (adresse.isEmpty() ? "" : adresse + "  ·  ")
                    + (localite.isEmpty() ? "" : localite + "  ·  ")
                    + (telephone.isEmpty() ? "" : "Tél : " + telephone);
            if (!subHeader.isEmpty()) {
                Paragraph pSub = new Paragraph(subHeader, infoFont);
                pSub.setSpacingBefore(4f);
                infoCell.addElement(pSub);
            }
            headerTable.addCell(infoCell);
            document.add(headerTable);

            // ── Ligne verte horizontale ──
            document.add(new Paragraph(" "));
            LineSeparator ls = new LineSeparator();
            ls.setLineColor(csuGreen);
            ls.setLineWidth(1.5f);
            document.add(ls);

            // ── 2. Titre « Facture » ──
            Paragraph titlePara = new Paragraph("Facture", titleFont);
            titlePara.setSpacingBefore(15f);
            titlePara.setSpacingAfter(15f);
            document.add(titlePara);

            // ── 3. Métadonnées ──
            PdfPTable metaTable = new PdfPTable(2);
            metaTable.setWidthPercentage(100);
            metaTable.setWidths(new float[]{1f, 1f});

            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(PdfPCell.NO_BORDER);

            String textMois = mois(facture.getMois()) + " " + facture.getAnnee();
            Paragraph pDate = new Paragraph();
            pDate.add(new Chunk("DATE : ", labelFont));
            pDate.add(new Chunk(textMois, valFont));
            pDate.setSpacingAfter(4f);
            leftCell.addElement(pDate);

            String formatNumFacture = String.format("FACT-STRUCT-%d-%02d", facture.getAnnee(), facture.getMois());
            Paragraph pNum = new Paragraph();
            pNum.add(new Chunk("N° FACTURE : ", labelFont));
            pNum.add(new Chunk(formatNumFacture, valFont));
            pNum.setSpacingAfter(4f);
            leftCell.addElement(pNum);

            Paragraph pCode = new Paragraph();
            pCode.add(new Chunk("CODE : ", labelFont));
            pCode.add(new Chunk(codeStructure, valFont));
            pCode.setSpacingAfter(4f);
            leftCell.addElement(pCode);

            Paragraph pMonnaie = new Paragraph();
            pMonnaie.add(new Chunk("MONNAIE : ", labelFont));
            pMonnaie.add(new Chunk("FRANCS CFA", valFont));
            leftCell.addElement(pMonnaie);
            metaTable.addCell(leftCell);

            PdfPCell rightCell = new PdfPCell();
            rightCell.setBorder(PdfPCell.NO_BORDER);
            rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            Paragraph pClientLabel = new Paragraph("CLIENT", clientLabelFont);
            pClientLabel.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(pClientLabel);
            Paragraph pClientVal = new Paragraph("Agence sénégalaise de la couverture sanitaire\nuniverselle", clientValFont);
            pClientVal.setAlignment(Element.ALIGN_RIGHT);
            pClientVal.setSpacingBefore(4f);
            rightCell.addElement(pClientVal);
            metaTable.addCell(rightCell);

            metaTable.setSpacingAfter(10f);
            document.add(metaTable);

            // ── 4. Titre du régime ──
            Paragraph regimeTitre = new Paragraph(titreLong(regime), subtitleFont);
            regimeTitre.setAlignment(Element.ALIGN_CENTER);
            regimeTitre.setSpacingAfter(5f);
            document.add(regimeTitre);

            // Référence facture
            Paragraph refFacture = new Paragraph("N° Référence facture : " + (facture.getNumero() != null ? facture.getNumero() : ""), infoFont);
            refFacture.setAlignment(Element.ALIGN_CENTER);
            refFacture.setSpacingAfter(10f);
            document.add(refFacture);

            // ── 5. Tableau des données ──
            PdfPTable table = new PdfPTable(nbCol);
            table.setWidthPercentage(100);

            // En-têtes (vert + blanc)
            for (Colonne col : colonnes) {
                PdfPCell cell = new PdfPCell(new Phrase(col.entete(), headFont));
                cell.setBackgroundColor(csuGreen);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                cell.setPadding(5f);
                cell.setBorderColor(borderLight);
                cell.setBorderWidth(0.5f);
                table.addCell(cell);
            }

            // Lignes de données
            double[] totaux = new double[nbCol];
            List<LigneFactureStructure> lignes = facture.getLignes() != null ? facture.getLignes() : new ArrayList<>();
            DecimalFormat df = new DecimalFormat("#,##0");

            // Lignes de données : une ligne par prestation (cellules séparées), l'identité du
            // patient fusionnée verticalement sur toute la visite (rowspan), sans la dupliquer.
            for (List<LigneFactureStructure> visite : ColonnesFactureStructure.groupesPourRegime(regime, lignes)) {
                ColonnesFactureStructure.CelluleFusion[][] grille =
                        ColonnesFactureStructure.grilleVisite(visite, colonnes);
                for (int rr = 0; rr < grille.length; rr++) {
                    for (int i = 0; i < nbCol; i++) {
                        ColonnesFactureStructure.CelluleFusion cf = grille[rr][i];
                        if (cf == null) {
                            continue; // couverte par le rowspan de la cellule du dessus : iText la saute.
                        }
                        Object val = cf.valeur();
                        String textVal;
                        boolean isNumber = false;
                        if (val instanceof Number nombre) {
                            textVal = df.format(nombre.doubleValue());
                            isNumber = true;
                        } else {
                            textVal = val != null ? val.toString() : "";
                        }

                        PdfPCell cell = new PdfPCell(new Phrase(textVal, normalFont));
                        if (cf.rowspan() > 1) cell.setRowspan(cf.rowspan());
                        cell.setHorizontalAlignment(isNumber ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                        cell.setPadding(4f);
                        cell.setBorderColor(borderLight);
                        cell.setBorderWidth(0.5f);
                        table.addCell(cell);
                    }
                }
                for (int i = 0; i < nbCol; i++) {
                    if (colonnes.get(i).total()) {
                        totaux[i] += ColonnesFactureStructure.sommeVisite(colonnes.get(i), visite);
                    }
                }
            }

            // Ligne des totaux
            boolean aTotal = colonnes.stream().anyMatch(Colonne::total);
            if (aTotal) {
                PdfPCell cellLibelle = new PdfPCell(new Phrase("TOTAUX", totalFont));
                cellLibelle.setBackgroundColor(csuGreen);
                cellLibelle.setHorizontalAlignment(Element.ALIGN_CENTER);
                cellLibelle.setVerticalAlignment(Element.ALIGN_MIDDLE);
                cellLibelle.setPadding(5f);
                cellLibelle.setBorderColor(borderLight);
                cellLibelle.setBorderWidth(0.5f);
                table.addCell(cellLibelle);

                for (int i = 1; i < nbCol; i++) {
                    String textVal = "";
                    if (colonnes.get(i).total()) {
                        textVal = df.format(totaux[i]);
                    }
                    PdfPCell cell = new PdfPCell(new Phrase(textVal, totalFont));
                    cell.setBackgroundColor(csuGreen);
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                    cell.setPadding(5f);
                    cell.setBorderColor(borderLight);
                    cell.setBorderWidth(0.5f);
                    table.addCell(cell);
                }
            }

            document.add(table);

            // ── 6. Encadré Montant à régler ──
            PdfPTable summaryTable = new PdfPTable(1);
            summaryTable.setWidthPercentage(40);
            summaryTable.setHorizontalAlignment(Element.ALIGN_RIGHT);

            PdfPCell boxCell = new PdfPCell();
            boxCell.setBackgroundColor(lightGreen);
            boxCell.setBorderColor(csuGreen);
            boxCell.setBorderWidth(1.5f);
            boxCell.setPadding(12f);

            Paragraph pReglerLabel = new Paragraph("MONTANT À RÉGLER PAR LA SEN-CSU", reglerLabelFont);
            pReglerLabel.setAlignment(Element.ALIGN_CENTER);
            boxCell.addElement(pReglerLabel);

            Paragraph pReglerVal = new Paragraph(formatNombre(facture.getMontantTotalSencsu()) + " FCFA", reglerValFont);
            pReglerVal.setAlignment(Element.ALIGN_CENTER);
            pReglerVal.setSpacingBefore(6f);
            boxCell.addElement(pReglerVal);

            summaryTable.addCell(boxCell);
            summaryTable.setSpacingBefore(20f);
            summaryTable.setSpacingAfter(15f);
            document.add(summaryTable);

            // ── 7. Phrase d'arrêt ──
            String amountInWords = NombreEnLettres.convertir(Math.round(facture.getMontantTotalSencsu()));
            if (!amountInWords.isEmpty()) {
                amountInWords = Character.toUpperCase(amountInWords.charAt(0)) + amountInWords.substring(1);
            }
            String footerText = "Arrêtée la présente facture, pour la part SEN-CSU, à la somme de " + amountInWords + " francs CFA.";
            Paragraph footerPara = new Paragraph(footerText, footerFont);
            footerPara.setSpacingBefore(15f);
            document.add(footerPara);

            // ── 8. Zone Signature ──
            Paragraph signaturePara = new Paragraph();
            signaturePara.setAlignment(Element.ALIGN_RIGHT);
            signaturePara.setSpacingBefore(40f);
            signaturePara.add(new Chunk("------------------------------------------------------------------\n",
                    new Font(Font.FontFamily.HELVETICA, 9f, Font.NORMAL, new BaseColor(180, 180, 180))));
            signaturePara.add(new Chunk("Le Responsable (cachet et signature)", signatureFont));
            document.add(signaturePara);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Erreur lors de l'export PDF de la facture structure", e);
            throw new RuntimeException("Erreur lors de l'export PDF", e);
        }
    }

    // ===================== Export Word (inchangé) =====================

    public byte[] exportFactureStructureWord(FactureStructure facture) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Regime regime = facture.getRegime();
            List<Colonne> colonnes = colonnesPourRegime(regime);
            int nbCol = colonnes.size();
            InfosStructure infos = resoudreInfosStructure(facture);

            // ── 1. En-tête de la structure ──
            XWPFParagraph pNom = doc.createParagraph();
            XWPFRun rNom = pNom.createRun();
            rNom.setText(infos.nom().toUpperCase());
            rNom.setBold(true);
            rNom.setFontSize(14);
            String sousEntete = sousEnteteStructure(infos);
            if (!sousEntete.isEmpty()) {
                XWPFParagraph pSous = doc.createParagraph();
                XWPFRun rSous = pSous.createRun();
                rSous.setText(sousEntete);
                rSous.setFontSize(9);
            }

            // ── 2. Titre « Facture » ──
            XWPFParagraph pTitre = doc.createParagraph();
            pTitre.setSpacingBefore(200);
            XWPFRun rTitre = pTitre.createRun();
            rTitre.setText("Facture");
            rTitre.setBold(true);
            rTitre.setFontSize(18);

            // ── 3. Métadonnées ──
            ajouterMetaWord(doc, "DATE : ", mois(facture.getMois()) + " " + facture.getAnnee());
            ajouterMetaWord(doc, "N° FACTURE : ",
                    String.format("FACT-STRUCT-%d-%02d", facture.getAnnee(), facture.getMois()));
            ajouterMetaWord(doc, "CODE : ", infos.code());
            ajouterMetaWord(doc, "MONNAIE : ", "FRANCS CFA");
            ajouterMetaWord(doc, "CLIENT : ", "Agence sénégalaise de la couverture sanitaire universelle");

            // ── 4. Titre du régime + référence ──
            XWPFParagraph pReg = doc.createParagraph();
            pReg.setAlignment(ParagraphAlignment.CENTER);
            pReg.setSpacingBefore(200);
            XWPFRun rReg = pReg.createRun();
            rReg.setText(titreLong(regime));
            rReg.setBold(true);
            rReg.setFontSize(12);
            XWPFParagraph pRef = doc.createParagraph();
            pRef.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun rRef = pRef.createRun();
            rRef.setText("N° Référence facture : " + (facture.getNumero() != null ? facture.getNumero() : ""));
            rRef.setFontSize(9);

            // ── 5. Tableau des données (mêmes colonnes que l'Excel et le PDF) ──
            XWPFTable table = doc.createTable(1, nbCol);
            XWPFTableRow header = table.getRow(0);
            for (int i = 0; i < nbCol; i++) {
                remplirCelluleWord(header, i, colonnes.get(i).entete(), true, ParagraphAlignment.CENTER);
            }

            double[] totaux = new double[nbCol];
            List<LigneFactureStructure> lignes = facture.getLignes() != null ? facture.getLignes() : new ArrayList<>();
            for (List<LigneFactureStructure> visite : ColonnesFactureStructure.groupesPourRegime(regime, lignes)) {
                // Une ligne par prestation (cellules séparées) ; l'identité du patient est fusionnée
                // verticalement sur tout le bloc de la visite, sans la dupliquer.
                ColonnesFactureStructure.CelluleFusion[][] grille =
                        ColonnesFactureStructure.grilleVisite(visite, colonnes);
                int n = grille.length;
                XWPFTableRow[] rows = new XWPFTableRow[n];
                for (int rr = 0; rr < n; rr++) rows[rr] = table.createRow();
                for (int rr = 0; rr < n; rr++) {
                    for (int i = 0; i < nbCol; i++) {
                        ColonnesFactureStructure.CelluleFusion cf = grille[rr][i];
                        if (cf == null) {
                            fusionVerticaleWord(rows[rr].getCell(i), false); // couverte par la fusion.
                            continue;
                        }
                        Object val = cf.valeur();
                        if (val instanceof Number nombre) {
                            remplirCelluleWord(rows[rr], i, formatNombre(nombre.doubleValue()), false, ParagraphAlignment.RIGHT);
                        } else {
                            remplirCelluleWord(rows[rr], i, val != null ? val.toString() : "", false, ParagraphAlignment.LEFT);
                        }
                        if (cf.rowspan() > 1) fusionVerticaleWord(rows[rr].getCell(i), true); // haut de la fusion.
                    }
                }
                for (int i = 0; i < nbCol; i++) {
                    if (colonnes.get(i).total()) {
                        totaux[i] += ColonnesFactureStructure.sommeVisite(colonnes.get(i), visite);
                    }
                }
            }

            // ── 6. Ligne des totaux ──
            if (colonnes.stream().anyMatch(Colonne::total)) {
                XWPFTableRow rowTot = table.createRow();
                for (int i = 0; i < nbCol; i++) {
                    String t = i == 0 ? "TOTAUX" : (colonnes.get(i).total() ? formatNombre(totaux[i]) : "");
                    remplirCelluleWord(rowTot, i, t, true, i == 0 ? ParagraphAlignment.CENTER : ParagraphAlignment.RIGHT);
                }
            }

            // ── 7. Montant à régler ──
            XWPFParagraph pRegler = doc.createParagraph();
            pRegler.setSpacingBefore(240);
            pRegler.setAlignment(ParagraphAlignment.RIGHT);
            XWPFRun rReglerLbl = pRegler.createRun();
            rReglerLbl.setText("MONTANT À RÉGLER PAR LA SEN-CSU : ");
            rReglerLbl.setBold(true);
            XWPFRun rReglerVal = pRegler.createRun();
            rReglerVal.setText(formatNombre(facture.getMontantTotalSencsu()) + " FCFA");
            rReglerVal.setBold(true);

            // ── 8. Phrase d'arrêt (en lettres) ──
            String enLettres = NombreEnLettres.convertir(Math.round(facture.getMontantTotalSencsu()));
            if (!enLettres.isEmpty()) {
                enLettres = Character.toUpperCase(enLettres.charAt(0)) + enLettres.substring(1);
            }
            XWPFParagraph pArret = doc.createParagraph();
            pArret.setSpacingBefore(150);
            XWPFRun rArret = pArret.createRun();
            rArret.setText("Arrêtée la présente facture, pour la part SEN-CSU, à la somme de "
                    + enLettres + " francs CFA.");
            rArret.setItalic(true);
            rArret.setFontSize(9);

            // ── 9. Signature ──
            XWPFParagraph pSig = doc.createParagraph();
            pSig.setAlignment(ParagraphAlignment.RIGHT);
            pSig.setSpacingBefore(400);
            XWPFRun rSig = pSig.createRun();
            rSig.setText("Le Responsable (cachet et signature)");
            rSig.setItalic(true);
            rSig.setFontSize(9);

            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Erreur lors de l'export Word de la facture structure", e);
            throw new RuntimeException("Erreur lors de l'export Word", e);
        }
    }

    /** Ligne de métadonnée « Libellé valeur » du Word (libellé en gras). */
    private void ajouterMetaWord(XWPFDocument doc, String label, String valeur) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun rl = p.createRun();
        rl.setText(label);
        rl.setBold(true);
        rl.setFontSize(9);
        XWPFRun rv = p.createRun();
        rv.setText(valeur);
        rv.setFontSize(9);
    }

    /** Remplit une cellule du tableau Word (remplace le paragraphe par défaut). */
    private void remplirCelluleWord(XWPFTableRow row, int idx, String texte, boolean gras, ParagraphAlignment alignement) {
        XWPFTableCell cell = row.getCell(idx);
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        p.setAlignment(alignement);
        for (int i = p.getRuns().size() - 1; i >= 0; i--) {
            p.removeRun(i);
        }
        XWPFRun run = p.createRun();
        // Le Word n'interprète pas « \n » : chaque ligne d'une liste devient un saut de ligne.
        String[] morceaux = (texte == null ? "" : texte).split("\n", -1);
        for (int i = 0; i < morceaux.length; i++) {
            if (i > 0) run.addBreak();
            run.setText(morceaux[i], i);
        }
        run.setBold(gras);
        run.setFontSize(9);
    }

    /**
     * Fusion verticale d'une cellule Word : {@code debut=true} ouvre la fusion (cellule du haut
     * qui porte la valeur), {@code debut=false} la continue (cellule vide couverte par celle du dessus).
     */
    private void fusionVerticaleWord(XWPFTableCell cell, boolean debut) {
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr tcPr =
                cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVMerge vMerge =
                tcPr.isSetVMerge() ? tcPr.getVMerge() : tcPr.addNewVMerge();
        vMerge.setVal(debut
                ? org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.RESTART
                : org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.CONTINUE);
    }

    // ============== PDF 0-5 ans : page de garde + liste nominative (un seul fichier) ==============

    /**
     * Document officiel du poste pour le forfait « Enfants de moins de 5 ans » : une page de
     * garde (facture synthétique) suivie de la liste nominative détaillée, réunies dans un
     * seul PDF. Un enfant venu avec trois prestations est UN patient et trois prestations
     * (correction du 07/10/2026 : il comptait pour trois patients) : « Nombre patients » =
     * patients distincts, comme la consolidation district ; « Nombre Prestations/Actes » =
     * lignes. La liste nominative a une ligne par visite, ses prestations réunies. Le montant
     * total reprend celui déjà calculé par la facture.
     */
    public byte[] exportFactureStructureEnfantsPdf(FactureStructure facture) {
        try {
            InfosStructure infos = resoudreInfosStructure(facture);
            // Document partagé avec la « Situation 0-5 ans » du district (service centre) :
            // commun/.../utils/PdfFactureEnfants.
            return PdfFactureEnfants.une(facture, PdfFactureEnfants.Structure.de(infos.nom(), infos.adresse(), infos.localite()));
        } catch (Exception e) {
            log.error("Erreur lors de l'export PDF 0-5 ans de la facture structure", e);
            throw new RuntimeException("Erreur lors de l'export PDF 0-5 ans", e);
        }
    }

    // ===================== Exports Groupés (Mensuels) =====================

    public byte[] exportGroupeExcel(List<FactureStructure> factures, Regime regime, int mois, int annee, String structureNom) {
        // Une facture par structure × régime × mois : le cas courant est une seule facture,
        // exportée telle quelle (son numéro, sa date) plutôt qu'une copie fusionnée.
        if (factures.size() == 1) {
            return exportFactureStructureExcel(factures.get(0));
        }
        FactureStructure merged = new FactureStructure();
        merged.setRegime(regime);
        merged.setMois(mois);
        merged.setAnnee(annee);
        merged.setStructureNom(structureNom);
        merged.setNumero("Mensuelle_" + mois + "_" + annee);

        // Récupérer l'ID de la structure depuis la première facture
        if (!factures.isEmpty() && factures.get(0).getStructureSanitaireId() != null) {
            merged.setStructureSanitaireId(factures.get(0).getStructureSanitaireId());
        }

        List<LigneFactureStructure> toutesLignes = new ArrayList<>();
        double totalSencsu = 0;

        for (FactureStructure f : factures) {
            if (f.getLignes() != null) {
                toutesLignes.addAll(f.getLignes());
            }
            totalSencsu += f.getMontantTotalSencsu();
        }
        merged.setLignes(toutesLignes);
        merged.setMontantTotalSencsu(totalSencsu);

        return exportFactureStructureExcel(merged);
    }

    public byte[] exportGroupePdf(List<FactureStructure> factures, Regime regime, int mois, int annee, String structureNom) {
        if (factures.size() == 1) {
            return exportFactureStructurePdf(factures.get(0));
        }
        FactureStructure merged = new FactureStructure();
        merged.setRegime(regime);
        merged.setMois(mois);
        merged.setAnnee(annee);
        merged.setStructureNom(structureNom);
        merged.setNumero("Mensuelle_" + mois + "_" + annee);

        // Récupérer l'ID de la structure depuis la première facture
        if (!factures.isEmpty() && factures.get(0).getStructureSanitaireId() != null) {
            merged.setStructureSanitaireId(factures.get(0).getStructureSanitaireId());
        }

        List<LigneFactureStructure> toutesLignes = new ArrayList<>();
        double totalSencsu = 0;

        for (FactureStructure f : factures) {
            if (f.getLignes() != null) {
                toutesLignes.addAll(f.getLignes());
            }
            totalSencsu += f.getMontantTotalSencsu();
        }
        merged.setLignes(toutesLignes);
        merged.setMontantTotalSencsu(totalSencsu);

        return exportFactureStructurePdf(merged);
    }
}
