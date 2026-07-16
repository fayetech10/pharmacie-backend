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
import java.util.function.Function;

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

    /** Colonne de l'état nominatif : intitulé + extraction de la valeur d'une ligne + totalisation éventuelle. */
    private record Colonne(String entete, Function<LigneFactureStructure, Object> valeur, boolean total) {
        Colonne(String entete, Function<LigneFactureStructure, Object> valeur) {
            this(entete, valeur, false);
        }
    }

    // ===================== Export Excel =====================

    public byte[] exportFactureStructureExcel(FactureStructure facture) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Regime regime = facture.getRegime();
            List<Colonne> colonnes = colonnesPourRegime(regime);
            int nbCol = colonnes.size();
            int dernierCol = Math.max(1, nbCol - 1);

            Sheet sheet = workbook.createSheet(titreCourt(regime));

            // Résoudre les infos de la structure
            String structureNom = facture.getStructureNom() != null ? facture.getStructureNom() : "Structure de santé";
            String localite = "";
            String codeStructure = "STR";
            String adresse = "";
            String telephone = "";

            if (facture.getStructureSanitaireId() != null) {
                StructureSanitaire structure = structureSanitaireRepository
                        .findById(facture.getStructureSanitaireId()).orElse(null);
                if (structure != null) {
                    structureNom = structure.getNom() != null ? structure.getNom() : structureNom;
                    codeStructure = structure.getCode() != null ? structure.getCode() : codeStructure;
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

            CellStyle cellLeftStyle = workbook.createCellStyle();
            cellLeftStyle.setFont(bodyFont);
            cellLeftStyle.setAlignment(HorizontalAlignment.LEFT);
            cellLeftStyle.setVerticalAlignment(VerticalAlignment.CENTER);
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
            cellNumStyle.setVerticalAlignment(VerticalAlignment.CENTER);
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
            double[] totaux = new double[nbCol];
            List<LigneFactureStructure> lignes = facture.getLignes() != null ? facture.getLignes() : new ArrayList<>();
            for (LigneFactureStructure ligne : lignes) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < nbCol; i++) {
                    Colonne col = colonnes.get(i);
                    Object val = col.valeur().apply(ligne);
                    Cell cell = row.createCell(i);
                    if (val instanceof Number nombre) {
                        double d = nombre.doubleValue();
                        cell.setCellValue(d);
                        cell.setCellStyle(cellNumStyle);
                        if (col.total()) totaux[i] += d;
                    } else {
                        cell.setCellValue(val != null ? val.toString() : "");
                        cell.setCellStyle(cellLeftStyle);
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
            String amountInWords = ExportService.convertToFrenchWords(Math.round(facture.getMontantTotalSencsu()));
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

    private List<Colonne> colonnesPourRegime(Regime regime) {
        if (regime == null) return colonnesClassique();
        return switch (regime) {
            case ZERO_CINQ_ANS -> colonnesEnfants();
            case CESARIENNE -> colonnesCesarienne();
            case DIALYSE_PERITONEALE -> colonnesDialysePeritoneale();
            case HEMODIALYSE, DIALYSE -> colonnesHemodialyse();
            case BSF, CEC, BSF_CEC -> colonnesBsfCec();
            case SESAME -> colonnesSesame();
            case CONTRIBUTIF, NDONGO_DARA -> colonnesClassique();
        };
    }

    /** Classique (contributif) et Ndongo Dara / Élève. */
    private List<Colonne> colonnesClassique() {
        List<Colonne> c = new ArrayList<>();
        c.add(new Colonne("Prénom(s)", LigneFactureStructure::getPatientPrenom));
        c.add(new Colonne("Nom", LigneFactureStructure::getPatientNom));
        c.add(new Colonne("Date de naissance", this::dateNaissance));
        c.add(new Colonne("Sexe (H/F)", l -> sexe(l, false)));
        c.add(new Colonne("Adresse", LigneFactureStructure::getPatientAdresse));
        c.add(new Colonne("N° Tél", LigneFactureStructure::getPatientTelephone));
        c.add(new Colonne("N° Matricule / Code Bénéficiaire", this::matricule));
        c.add(new Colonne("Date de prise en charge", this::datePriseEnCharge));
        c.add(new Colonne("Service", l -> ""));
        c.add(new Colonne("Prestation(s)", LigneFactureStructure::getDesignation));
        c.add(new Colonne("Quantité", LigneFactureStructure::getQuantite));
        c.add(new Colonne("P.U", LigneFactureStructure::getPrixUnitaire));
        c.add(new Colonne("Montant Total", LigneFactureStructure::getMontant, true));
        return c;
    }

    /** Enfants de moins de 5 ans. */
    private List<Colonne> colonnesEnfants() {
        List<Colonne> c = new ArrayList<>();
        c.add(new Colonne("N° dans le registre", LigneFactureStructure::getNumeroRegistre));
        c.add(new Colonne("Prénom(s)", LigneFactureStructure::getPatientPrenom));
        c.add(new Colonne("Nom", LigneFactureStructure::getPatientNom));
        c.add(new Colonne("Date de naissance", this::dateNaissance));
        c.add(new Colonne("Sexe (M/F)", l -> sexe(l, true)));
        c.add(new Colonne("Adresse", LigneFactureStructure::getPatientAdresse));
        c.add(new Colonne("N° Matricule / N° Extrait de naissance / N° accompagnant", this::matricule));
        c.add(new Colonne("N° Téléphone", LigneFactureStructure::getPatientTelephone));
        c.add(new Colonne("Date de prise en Charge", this::datePriseEnCharge));
        c.add(new Colonne("Service", l -> ""));
        c.add(new Colonne("Prestations et médicaments", LigneFactureStructure::getDesignation));
        c.add(new Colonne("Diagnostic / Motif de consultation", l -> ""));
        c.add(new Colonne("Forfait", LigneFactureStructure::getPrixUnitaire));
        c.add(new Colonne("Montant Total", LigneFactureStructure::getMontant, true));
        return c;
    }

    /** Césarienne (pas de colonne montant dans le modèle : la part SEN-CSU figure en récapitulatif). */
    private List<Colonne> colonnesCesarienne() {
        List<Colonne> c = new ArrayList<>();
        c.add(new Colonne("Prénom(s)", LigneFactureStructure::getPatientPrenom));
        c.add(new Colonne("Nom", LigneFactureStructure::getPatientNom));
        c.add(new Colonne("Date de naissance", this::dateNaissance));
        c.add(new Colonne("Sexe (H/F)", l -> sexe(l, false)));
        c.add(new Colonne("Adresse", LigneFactureStructure::getPatientAdresse));
        c.add(new Colonne("N° Téléphone", LigneFactureStructure::getPatientTelephone));
        c.add(new Colonne("N° Matricule / N° CNI Patient / N° accompagnant", this::matricule));
        c.add(new Colonne("Indication / Motif de CBT", this::indicationCesarienne));
        c.add(new Colonne("N° Registre Bloc opératoire", LigneFactureStructure::getNumeroRegistreBloc));
        c.add(new Colonne("Date et Heure Intervention", LigneFactureStructure::getDateHeureIntervention));
        c.add(new Colonne("Durée Hospitalisation (jours)", LigneFactureStructure::getDureeHospitalisationJours));
        return c;
    }

    /** Dialyse péritonéale (nombre de poches). */
    private List<Colonne> colonnesDialysePeritoneale() {
        List<Colonne> c = new ArrayList<>();
        ajouterIdentiteDialyse(c);
        c.add(new Colonne("Date de prise en charge", this::datePriseEnCharge));
        c.add(new Colonne("Nbre de Poches", LigneFactureStructure::getQuantite));
        c.add(new Colonne("Prix Unitaire", LigneFactureStructure::getPrixUnitaire));
        c.add(new Colonne("Prix Total", LigneFactureStructure::getMontant, true));
        return c;
    }

    /** Hémodialyse (nombre de séances) — sert aussi à l'ancien régime DIALYSE. */
    private List<Colonne> colonnesHemodialyse() {
        List<Colonne> c = new ArrayList<>();
        ajouterIdentiteDialyse(c);
        c.add(new Colonne("Nbre de Séances", LigneFactureStructure::getQuantite));
        c.add(new Colonne("Prix Unitaire", LigneFactureStructure::getPrixUnitaire));
        c.add(new Colonne("Prix Total", LigneFactureStructure::getMontant, true));
        return c;
    }

    /** Identité commune aux dialyses (jusqu'à la colonne IRC/IRA incluse). */
    private void ajouterIdentiteDialyse(List<Colonne> c) {
        c.add(new Colonne("Prénom(s)", LigneFactureStructure::getPatientPrenom));
        c.add(new Colonne("Nom", LigneFactureStructure::getPatientNom));
        c.add(new Colonne("Date de naissance", this::dateNaissance));
        c.add(new Colonne("Sexe (H/F)", l -> sexe(l, false)));
        c.add(new Colonne("Adresse", LigneFactureStructure::getPatientAdresse));
        c.add(new Colonne("N° Téléphone", LigneFactureStructure::getPatientTelephone));
        c.add(new Colonne("N° Matricule / N° CNI Patient / N° accompagnant", this::matricule));
        c.add(new Colonne("IRC/IRA", LigneFactureStructure::getIrcIra));
    }

    /** Bourse de Sécurité Familiale et Carte Égalité des Chances (montant facturé à la SEN-CSU). */
    private List<Colonne> colonnesBsfCec() {
        List<Colonne> c = new ArrayList<>();
        c.add(new Colonne("Prénom(s)", LigneFactureStructure::getPatientPrenom));
        c.add(new Colonne("Nom", LigneFactureStructure::getPatientNom));
        c.add(new Colonne("Date de naissance", this::dateNaissance));
        c.add(new Colonne("Sexe (H/F)", l -> sexe(l, false)));
        c.add(new Colonne("Adresse", LigneFactureStructure::getPatientAdresse));
        c.add(new Colonne("N° Tél", LigneFactureStructure::getPatientTelephone));
        c.add(new Colonne("N° Matricule / CNI", this::matricule));
        c.add(new Colonne("Date de prise en charge", this::datePriseEnCharge));
        c.add(new Colonne("Service", l -> ""));
        c.add(new Colonne("Prestation(s)", LigneFactureStructure::getDesignation));
        c.add(new Colonne("Quantité", LigneFactureStructure::getQuantite));
        c.add(new Colonne("P.U", LigneFactureStructure::getPrixUnitaire));
        c.add(new Colonne("Montant facturé à la SEN-CSU", LigneFactureStructure::getMontantSencsu, true));
        return c;
    }

    /** Plan Sésame (matricule ET n° CNI dans deux colonnes distinctes). */
    private List<Colonne> colonnesSesame() {
        List<Colonne> c = new ArrayList<>();
        c.add(new Colonne("Prénom(s)", LigneFactureStructure::getPatientPrenom));
        c.add(new Colonne("Nom", LigneFactureStructure::getPatientNom));
        c.add(new Colonne("Date de naissance", this::dateNaissance));
        c.add(new Colonne("Sexe (H/F)", l -> sexe(l, false)));
        c.add(new Colonne("Adresse", LigneFactureStructure::getPatientAdresse));
        c.add(new Colonne("N° Tél", LigneFactureStructure::getPatientTelephone));
        c.add(new Colonne("N° Matricule", l -> nonVide(l.getPatientMatricule())));
        c.add(new Colonne("N° CNI", LigneFactureStructure::getPatientNumeroCni));
        c.add(new Colonne("Date de prise en charge", this::datePriseEnCharge));
        c.add(new Colonne("Service", l -> ""));
        c.add(new Colonne("Prestation(s)", LigneFactureStructure::getDesignation));
        c.add(new Colonne("Quantité", LigneFactureStructure::getQuantite));
        c.add(new Colonne("P.U", LigneFactureStructure::getPrixUnitaire));
        c.add(new Colonne("Montant facturé à la SEN-CSU", LigneFactureStructure::getMontantSencsu, true));
        return c;
    }

    // ===================== Extracteurs de valeurs =====================

    private String dateNaissance(LigneFactureStructure l) {
        return l.getPatientDateNaissance() != null ? l.getPatientDateNaissance().format(DATE_FMT) : "";
    }

    private String datePriseEnCharge(LigneFactureStructure l) {
        return l.getDatePriseEnCharge() != null ? l.getDatePriseEnCharge().format(DATE_FMT) : "";
    }

    /** Matricule du bénéficiaire, avec repli sur le n° CNI si le matricule n'est pas renseigné. */
    private String matricule(LigneFactureStructure l) {
        if (l.getPatientMatricule() != null && !l.getPatientMatricule().isBlank()) {
            return l.getPatientMatricule();
        }
        return nonVide(l.getPatientNumeroCni());
    }

    private String indicationCesarienne(LigneFactureStructure l) {
        if (l.getIndicationCbt() != null && !l.getIndicationCbt().isBlank()) {
            return l.getIndicationCbt();
        }
        return nonVide(l.getMotifCesarienne());
    }

    /**
     * Sexe affiché selon le modèle : les enfants de moins de 5 ans utilisent M/F,
     * tous les autres régimes utilisent H/F (le sexe est stocké de façon canonique en M/F).
     */
    private String sexe(LigneFactureStructure l, boolean enfants) {
        String s = l.getPatientSexe();
        if (s == null || s.isBlank()) return "";
        boolean feminin = s.trim().toUpperCase(Locale.ROOT).startsWith("F");
        if (feminin) return "F";
        return enfants ? "M" : "H";
    }

    private String nonVide(String s) {
        return s != null ? s : "";
    }

    // ===================== Libellés =====================

    private String titreLong(Regime regime) {
        if (regime == null) return "ETAT RECAPITULATIF NOMINATIF";
        return switch (regime) {
            case CONTRIBUTIF -> "ETAT RECAPITULATIF NOMINATIF DU REGIME CLASSIQUE";
            case ZERO_CINQ_ANS -> "LISTE NOMINATIVE DES ENFANTS DE MOINS DE 5 ANS";
            case CESARIENNE -> "LISTE NOMINATIVE DE LA CESARIENNE";
            case DIALYSE_PERITONEALE -> "ETAT RECAPITULATIF NOMINATIF DE LA DIALYSE PERITONEALE";
            case HEMODIALYSE -> "ETAT RECAPITULATIF NOMINATIF DE L'HEMODIALYSE";
            case BSF -> "ETAT RECAPITULATIF NOMINATIF DE LA BOURSE DE SECURITE FAMILIALE";
            case CEC -> "ETAT RECAPITULATIF NOMINATIF DE LA CARTE EGALITE DES CHANCES";
            case SESAME -> "ETAT RECAPITULATIF NOMINATIF DU PLAN SESAME";
            case NDONGO_DARA -> "ETAT RECAPITULATIF NOMINATIF DU PLAN NDONGO DARA / ELEVE";
            case DIALYSE -> "ETAT RECAPITULATIF NOMINATIF DE LA DIALYSE";
            case BSF_CEC -> "ETAT RECAPITULATIF NOMINATIF BSF / CEC";
        };
    }

    private String titreCourt(Regime regime) {
        if (regime == null) return "Facture";
        return switch (regime) {
            case CONTRIBUTIF -> "Classique";
            case ZERO_CINQ_ANS -> "Enfants 0-5 ans";
            case CESARIENNE -> "Césariennes";
            case DIALYSE_PERITONEALE -> "Dialyse péritonéale";
            case HEMODIALYSE -> "Hémodialyse";
            case BSF -> "BSF";
            case CEC -> "CEC";
            case SESAME -> "Sésame";
            case NDONGO_DARA -> "Ndongo Dara";
            case DIALYSE -> "Dialyse";
            case BSF_CEC -> "BSF-CEC";
        };
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

    // ===================== Export PDF =====================

    public byte[] exportFactureStructurePdf(FactureStructure facture) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Résoudre les infos de la structure
            String structureNom = facture.getStructureNom() != null ? facture.getStructureNom() : "Structure de santé";
            String localite = "";
            String codeStructure = "STR";
            String adresse = "";
            String telephone = "";

            if (facture.getStructureSanitaireId() != null) {
                StructureSanitaire structure = structureSanitaireRepository
                        .findById(facture.getStructureSanitaireId()).orElse(null);
                if (structure != null) {
                    structureNom = structure.getNom() != null ? structure.getNom() : structureNom;
                    codeStructure = structure.getCode() != null ? structure.getCode() : codeStructure;
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

            for (LigneFactureStructure ligne : lignes) {
                for (int i = 0; i < nbCol; i++) {
                    Colonne col = colonnes.get(i);
                    Object val = col.valeur().apply(ligne);
                    String textVal;
                    boolean isNumber = false;

                    if (val instanceof Number nombre) {
                        double d = nombre.doubleValue();
                        textVal = df.format(d);
                        if (col.total()) totaux[i] += d;
                        isNumber = true;
                    } else {
                        textVal = val != null ? val.toString() : "";
                    }

                    PdfPCell cell = new PdfPCell(new Phrase(textVal, normalFont));
                    cell.setHorizontalAlignment(isNumber ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                    cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                    cell.setPadding(4f);
                    cell.setBorderColor(borderLight);
                    cell.setBorderWidth(0.5f);
                    table.addCell(cell);
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
            String amountInWords = ExportService.convertToFrenchWords(Math.round(facture.getMontantTotalSencsu()));
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
            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun run = title.createRun();
            run.setText("Facture Mensuelle - " + facture.getStructureNom());
            run.setBold(true);
            run.setFontSize(16);

            XWPFParagraph info = doc.createParagraph();
            XWPFRun infoRun = info.createRun();
            infoRun.setText("Régime: " + facture.getRegime() + " | Mois/Année: " + facture.getMois() + "/" + facture.getAnnee());
            infoRun.addBreak();
            infoRun.setText("Montant Total Pris en Charge: " + facture.getMontantTotalSencsu() + " FCFA");

            XWPFTable table = doc.createTable();
            XWPFTableRow headerRow = table.getRow(0);
            headerRow.getCell(0).setText("Patient");
            headerRow.addNewTableCell().setText("Matricule");
            headerRow.addNewTableCell().setText("N° Lettre");
            headerRow.addNewTableCell().setText("Montant Total");
            headerRow.addNewTableCell().setText("Montant PEC");

            for (LigneFactureStructure ligne : facture.getLignes()) {
                XWPFTableRow row = table.createRow();
                row.getCell(0).setText(ligne.getPatientNom() + " " + ligne.getPatientPrenom());
                row.getCell(1).setText(ligne.getPatientMatricule() != null ? ligne.getPatientMatricule() : "");
                row.getCell(2).setText(ligne.getLettreGarantieNumero() != null ? ligne.getLettreGarantieNumero() : "");
                row.getCell(3).setText(String.valueOf(ligne.getMontant()));
                row.getCell(4).setText(String.valueOf(ligne.getMontantSencsu()));
            }

            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Erreur lors de l'export Word de la facture structure", e);
            throw new RuntimeException("Erreur lors de l'export Word", e);
        }
    }

    // ===================== Exports Groupés (Mensuels) =====================

    public byte[] exportGroupeExcel(List<FactureStructure> factures, Regime regime, int mois, int annee, String structureNom) {
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
