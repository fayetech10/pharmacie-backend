package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.*;
import com.csu.pharmacie.repository.PharmacieRepository;
import com.csu.pharmacie.repository.RegionRepository;
import com.itextpdf.text.*;
import com.itextpdf.text.Font;
import com.itextpdf.text.pdf.*;
import com.itextpdf.text.pdf.draw.LineSeparator;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ExportService {

    private final PharmacieRepository pharmacieRepository;
    private final RegionRepository regionRepository;

    public byte[] exportExcel(List<Facture> factures) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Factures");

            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("ID");
            headerRow.createCell(1).setCellValue("Pharmacie");
            headerRow.createCell(2).setCellValue("Région");
            headerRow.createCell(3).setCellValue("Mois");
            headerRow.createCell(4).setCellValue("Année");
            headerRow.createCell(5).setCellValue("Montant Total");
            headerRow.createCell(6).setCellValue("Statut");

            int rowIdx = 1;
            for (Facture facture : factures) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(facture.getId());
                row.createCell(1).setCellValue(facture.getPharmacieNom());
                row.createCell(2).setCellValue(facture.getRegionId());
                row.createCell(3).setCellValue(facture.getMois());
                row.createCell(4).setCellValue(facture.getAnnee());
                row.createCell(5).setCellValue(facture.getMontantTotal());
                row.createCell(6).setCellValue(facture.getStatut().name());
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors de l'export Excel", e);
        }
    }

    public byte[] exportFactureExcel(Facture facture) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Facture");
            sheet.setDisplayGridlines(true);

            String pharmacieNom = facture.getPharmacieNom();
            String localite = "";
            String codePharmacie = "PH";
            String adresse = "Thiès";
            String telephone = "772233325";

            Pharmacie pharmacie = pharmacieRepository.findById(facture.getPharmacieId()).orElse(null);
            if (pharmacie != null) {
                pharmacieNom = pharmacie.getNom();
                codePharmacie = pharmacie.getCode();
                adresse = pharmacie.getAdresse() != null ? pharmacie.getAdresse() : "Thiès";
                telephone = pharmacie.getTelephone() != null ? pharmacie.getTelephone() : "772233325";
                Region region = regionRepository.findById(pharmacie.getRegionId()).orElse(null);
                if (region != null) {
                    localite = region.getNom();
                }
            }

            String dateFacture = facture.getCreatedAt() != null
                    ? facture.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            // Colors
            byte[] rgbGreen = new byte[]{(byte) 47, (byte) 110, (byte) 84}; // #2F6E54
            byte[] rgbLightGreen = new byte[]{(byte) 230, (byte) 244, (byte) 234}; // #E6F4EA
            byte[] rgbGray = new byte[]{(byte) 100, (byte) 100, (byte) 100};
            byte[] rgbDark = new byte[]{(byte) 51, (byte) 51, (byte) 51};
            byte[] rgbBorder = new byte[]{(byte) 220, (byte) 220, (byte) 220};

            XSSFColor csuGreen = new XSSFColor(rgbGreen, null);
            XSSFColor lightGreen = new XSSFColor(rgbLightGreen, null);
            XSSFColor textGray = new XSSFColor(rgbGray, null);
            XSSFColor textDark = new XSSFColor(rgbDark, null);
            XSSFColor borderColor = new XSSFColor(rgbBorder, null);

            // Styles
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
            labelFont.setFontHeightInPoints((short) 9.5);
            labelFont.setColor(textGray);
            CellStyle labelStyle = workbook.createCellStyle();
            labelStyle.setFont(labelFont);

            XSSFFont valFont = workbook.createFont();
            valFont.setBold(true);
            valFont.setFontHeightInPoints((short) 9.5);
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
            setCellBorders(headerStyle);

            XSSFFont bodyFont = workbook.createFont();
            bodyFont.setFontHeightInPoints((short) 8.5);
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

            // 1. En-tête
            Row row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue(pharmacieNom.toUpperCase());
            row0.getCell(0).setCellStyle(nameStyle);

            Row row1 = sheet.createRow(1);
            String subHeader = adresse + "  ·  " + (localite.isEmpty() ? adresse : localite) 
                    + "  ·  Tél : " + telephone + "  ·  Ninea : 233333  ·  RC : 485758587";
            row1.createCell(0).setCellValue(subHeader);
            row1.getCell(0).setCellStyle(infoStyle);

            // 2. Titre "Facture"
            Row row3 = sheet.createRow(3);
            row3.createCell(0).setCellValue("Facture");
            row3.getCell(0).setCellStyle(titleStyle);

            // 3. Métadonnées
            String textMois = getMonthName(facture.getMois()) + " " + facture.getAnnee();
            createExcelMetaRow(sheet, 5, "DATE :", textMois, labelStyle, valStyle);
            
            String formatNumFacture = String.format("FACT-PHARMA-%d-%02d", facture.getAnnee(), facture.getMois());
            createExcelMetaRow(sheet, 6, "N° FACTURE :", formatNumFacture, labelStyle, valStyle);
            
            createExcelMetaRow(sheet, 7, "CODE :", codePharmacie, labelStyle, valStyle);
            createExcelMetaRow(sheet, 8, "MONNAIE :", "FRANCS CFA", labelStyle, valStyle);

            // Client à droite (Col G & H)
            Row r5 = sheet.getRow(5);
            Cell cLabel = r5.createCell(7);
            cLabel.setCellValue("CLIENT");
            cLabel.setCellStyle(clientLabelStyle);

            Row r6 = sheet.getRow(6);
            Cell cVal1 = r6.createCell(7);
            cVal1.setCellValue("Agence sénégalaise de la couverture sanitaire");
            cVal1.setCellStyle(clientValStyle);

            Row r7 = sheet.getRow(7);
            Cell cVal2 = r7.createCell(7);
            cVal2.setCellValue("universelle");
            cVal2.setCellStyle(clientValStyle);

            // 4. Headers du tableau (Row 10)
            Row headersRow = sheet.createRow(10);
            String[] headers = {
                "N°", "N° Bon", "Date", "Nom & Prénom", "Médicament",
                "P.U.", "Qté", "Montant", "Part bénéf.", "Part SEN-CSU"
            };
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headersRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 5. Lignes de données
            List<LigneFacture> lignes = facture.getLignes() != null ? facture.getLignes() : new ArrayList<>();
            int rowIdx = 11;
            int counter = 1;
            double sumMontant = 0;
            double sumBenef = 0;
            double sumCsu = 0;

            for (LigneFacture ligne : lignes) {
                int idx0 = rowIdx++;
                Row row = sheet.createRow(idx0);
                int rowNum = idx0 + 1; // n° de ligne Excel (1-based), pour les formules
                double total = ligne.getQuantite() * ligne.getPrixUnitaire();
                sumMontant += total;
                sumBenef += total * 0.5;
                sumCsu += total * 0.5;

                row.createCell(0).setCellValue(counter++);
                row.getCell(0).setCellStyle(cellCenterStyle);

                row.createCell(1).setCellValue(ligne.getPatientMatricule() != null ? ligne.getPatientMatricule() : "");
                row.getCell(1).setCellStyle(cellCenterStyle);

                row.createCell(2).setCellValue(dateFacture);
                row.getCell(2).setCellStyle(cellCenterStyle);

                row.createCell(3).setCellValue(ligne.getPatientNomPrenom() != null ? ligne.getPatientNomPrenom() : "");
                row.getCell(3).setCellStyle(cellLeftStyle);

                row.createCell(4).setCellValue(ligne.getMedicament() != null ? ligne.getMedicament() : "");
                row.getCell(4).setCellStyle(cellLeftStyle);

                // P.U. (F) et Qté (G) = valeurs saisissables. Montant (H) et Parts (I, J) =
                // formules : Excel les recalcule dès qu'on modifie le prix ou la quantité.
                row.createCell(5).setCellValue(ligne.getPrixUnitaire());
                row.getCell(5).setCellStyle(cellCenterStyle);

                row.createCell(6).setCellValue(ligne.getQuantite());
                row.getCell(6).setCellStyle(cellCenterStyle);

                Cell montantCell = row.createCell(7);
                montantCell.setCellFormula("F" + rowNum + "*G" + rowNum);
                montantCell.setCellStyle(cellCenterStyle);

                Cell benefCell = row.createCell(8);
                benefCell.setCellFormula("H" + rowNum + "*0.5");
                benefCell.setCellStyle(cellCenterStyle);

                Cell csuCell = row.createCell(9);
                csuCell.setCellFormula("H" + rowNum + "*0.5");
                csuCell.setCellStyle(cellCenterStyle);
            }

            // Totaux : sommes en formules (suivent les Montants/Parts recalculés).
            boolean hasLignes = !lignes.isEmpty();
            int firstDataRow = 12;       // 1ʳᵉ ligne de données (en-tête en ligne 11)
            int lastDataRow = rowIdx;    // dernière ligne de données (1-based)

            Row totalRow = sheet.createRow(rowIdx);
            for (int i = 0; i < 7; i++) {
                Cell cell = totalRow.createCell(i);
                cell.setCellStyle(headerStyle);
            }
            totalRow.getCell(6).setCellValue("TOTAUX");

            Cell cellSumM = totalRow.createCell(7);
            if (hasLignes) cellSumM.setCellFormula("SUM(H" + firstDataRow + ":H" + lastDataRow + ")");
            else cellSumM.setCellValue(sumMontant);
            cellSumM.setCellStyle(headerStyle);

            Cell cellSumB = totalRow.createCell(8);
            if (hasLignes) cellSumB.setCellFormula("SUM(I" + firstDataRow + ":I" + lastDataRow + ")");
            else cellSumB.setCellValue(sumBenef);
            cellSumB.setCellStyle(headerStyle);

            Cell cellSumC = totalRow.createCell(9);
            if (hasLignes) cellSumC.setCellFormula("SUM(J" + firstDataRow + ":J" + lastDataRow + ")");
            else cellSumC.setCellValue(sumCsu);
            cellSumC.setCellStyle(headerStyle);

            // 6. Encadré à droite (H & I & J)
            int boxStartRow = rowIdx + 2;
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

            Row bRow1 = sheet.createRow(boxStartRow);
            Cell bCell1 = bRow1.createCell(7);
            bCell1.setCellValue("MONTANT À RÉGLER PAR LA SEN-CSU");
            bCell1.setCellStyle(boxStyle);

            XSSFFont boxValFont = workbook.createFont();
            boxValFont.setBold(true);
            boxValFont.setFontHeightInPoints((short) 14);
            boxValFont.setColor(csuGreen);
            XSSFCellStyle boxValStyle = workbook.createCellStyle();
            boxValStyle.cloneStyleFrom(boxStyle);
            boxValStyle.setFont(boxValFont);
            boxValStyle.setAlignment(HorizontalAlignment.CENTER);

            Row bRow2 = sheet.createRow(boxStartRow + 1);
            Cell bCell2 = bRow2.createCell(7);
            // Montant à régler = somme de la part SEN-CSU (suit les modifications).
            if (hasLignes) bCell2.setCellFormula("SUM(J" + firstDataRow + ":J" + lastDataRow + ")&\" FCFA\"");
            else bCell2.setCellValue(sumCsu + " FCFA");
            bCell2.setCellStyle(boxValStyle);

            // 7. Phrase d'arrêt
            String amountInWords = convertToFrenchWords(Math.round(sumCsu));
            if (!amountInWords.isEmpty()) {
                amountInWords = Character.toUpperCase(amountInWords.charAt(0)) + amountInWords.substring(1);
            }
            String footerText = "Arrêtée la présente facture, pour la part SEN-CSU, à la somme de " + amountInWords + " francs CFA.";
            
            Row fRow = sheet.createRow(boxStartRow + 3);
            Cell fCell = fRow.createCell(0);
            fCell.setCellValue(footerText);
            fCell.setCellStyle(infoStyle);

            // 8. Signatures
            Row sigRow1 = sheet.createRow(boxStartRow + 5);
            Cell sigCell1 = sigRow1.createCell(7);
            sigCell1.setCellValue("--------------------------------------------------");
            sigCell1.setCellStyle(infoStyle);

            Row sigRow2 = sheet.createRow(boxStartRow + 6);
            Cell sigCell2 = sigRow2.createCell(7);
            sigCell2.setCellValue("Le Pharmacien (cachet et signature)");
            
            XSSFFont sigFont = workbook.createFont();
            sigFont.setFontHeightInPoints((short) 9);
            sigFont.setItalic(true);
            sigFont.setColor(textDark);
            CellStyle sigStyle = workbook.createCellStyle();
            sigStyle.setFont(sigFont);
            sigCell2.setCellStyle(sigStyle);

            // Calcule et met en cache les résultats des formules (Montant, Parts, Totaux)
            // afin que le fichier affiche les bons nombres dès l'ouverture.
            org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator.evaluateAllFormulaCells(workbook);

            // Ajuster la taille des colonnes
            for (int i = 0; i < 10; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors de l'export Excel de la facture", e);
        }
    }

    private void setCellBorders(CellStyle style) {
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private void createExcelMetaRow(Sheet sheet, int rowIdx, String label, String value, CellStyle labelStyle, CellStyle valStyle) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) {
            row = sheet.createRow(rowIdx);
        }
        Cell cellLabel = row.createCell(0);
        cellLabel.setCellValue(label);
        cellLabel.setCellStyle(labelStyle);

        Cell cellVal = row.createCell(1);
        cellVal.setCellValue(value);
        cellVal.setCellStyle(valStyle);
    }

    private String getMonthName(int mois) {
        String[] standardMois = {
            "", "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
            "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre"
        };
        return (mois >= 1 && mois <= 12) ? standardMois[mois] : "";
    }

    public byte[] exportPdf(List<Facture> factures) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(new Paragraph("Rapport des Factures"));
            document.add(new Paragraph(" "));

            for (Facture facture : factures) {
                document.add(new Paragraph("Facture ID: " + facture.getId() + " - Pharmacie: " + facture.getPharmacieNom() + " - Montant: " + facture.getMontantTotal() + " - Statut: " + facture.getStatut()));
            }

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("Erreur lors de l'export PDF", e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] exportFacturePdf(Facture facture) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String pharmacieNom = facture.getPharmacieNom();
            String localite = "";
            String codePharmacie = "PH";
            String adresse = "Thiès";
            String telephone = "772233325";

            Pharmacie pharmacie = pharmacieRepository.findById(facture.getPharmacieId()).orElse(null);
            if (pharmacie != null) {
                pharmacieNom = pharmacie.getNom();
                codePharmacie = pharmacie.getCode();
                adresse = pharmacie.getAdresse() != null ? pharmacie.getAdresse() : "Thiès";
                telephone = pharmacie.getTelephone() != null ? pharmacie.getTelephone() : "772233325";
                Region region = regionRepository.findById(pharmacie.getRegionId()).orElse(null);
                if (region != null) {
                    localite = region.getNom();
                }
            }

            String dateFacture = facture.getCreatedAt() != null
                    ? facture.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            // Colors
            BaseColor csuGreen = new BaseColor(47, 110, 84); // #2F6E54
            BaseColor lightGreen = new BaseColor(230, 244, 234); // #E6F4EA
            BaseColor textDark = new BaseColor(51, 51, 51); // #333333
            BaseColor textGray = new BaseColor(100, 100, 100); // #666666
            BaseColor borderLight = new BaseColor(220, 220, 220); // light gray

            // Fonts
            Font nameFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD, csuGreen);
            Font infoFont = new Font(Font.FontFamily.HELVETICA, 8.5f, Font.NORMAL, textGray);
            Font titleFont = new Font(Font.FontFamily.HELVETICA, 24, Font.BOLD, new BaseColor(26, 26, 26));
            Font labelFont = new Font(Font.FontFamily.HELVETICA, 9.5f, Font.BOLD, textGray);
            Font valFont = new Font(Font.FontFamily.HELVETICA, 9.5f, Font.BOLD, csuGreen);
            Font clientLabelFont = new Font(Font.FontFamily.HELVETICA, 8f, Font.NORMAL, textGray);
            Font clientValFont = new Font(Font.FontFamily.HELVETICA, 10f, Font.BOLD, csuGreen);
            Font headFont = new Font(Font.FontFamily.HELVETICA, 8.5f, Font.BOLD, BaseColor.WHITE);
            Font normalFont = new Font(Font.FontFamily.HELVETICA, 8f, Font.NORMAL, textDark);
            Font totalFont = new Font(Font.FontFamily.HELVETICA, 8.5f, Font.BOLD, BaseColor.WHITE);
            Font reglerLabelFont = new Font(Font.FontFamily.HELVETICA, 8.5f, Font.BOLD, csuGreen);
            Font reglerValFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD, csuGreen);
            Font footerFont = new Font(Font.FontFamily.HELVETICA, 8.5f, Font.ITALIC, textGray);
            Font signatureFont = new Font(Font.FontFamily.HELVETICA, 9f, Font.ITALIC, textDark);

            // 1. En-tête avec logo
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{1.5f, 8.5f});
            headerTable.getDefaultCell().setBorder(PdfPCell.NO_BORDER);

            PdfPCell logoCell = new PdfPCell();
            logoCell.setBorder(PdfPCell.NO_BORDER);
            logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            try {
                byte[] logoBytes = new ClassPathResource("logo-pharmacie.png").getInputStream().readAllBytes();
                Image logoImg = Image.getInstance(logoBytes);
                logoImg.scaleToFit(50, 50);
                logoCell.addElement(logoImg);
            } catch (Exception e) {
                // Fallback cross symbol
                Paragraph fallbackCross = new Paragraph("✚", new Font(Font.FontFamily.HELVETICA, 36, Font.BOLD, csuGreen));
                logoCell.addElement(fallbackCross);
            }
            headerTable.addCell(logoCell);

            PdfPCell infoCell = new PdfPCell();
            infoCell.setBorder(PdfPCell.NO_BORDER);
            infoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

            Paragraph pName = new Paragraph(pharmacieNom.toUpperCase(), nameFont);
            infoCell.addElement(pName);

            String subHeader = adresse + "  ·  " + (localite.isEmpty() ? adresse : localite) 
                    + "  ·  Tél : " + telephone + "  ·  Ninea : 233333  ·  RC : 485758587";
            Paragraph pSub = new Paragraph(subHeader, infoFont);
            pSub.setSpacingBefore(4f);
            infoCell.addElement(pSub);
            headerTable.addCell(infoCell);
            document.add(headerTable);

            // Ligne verte horizontale
            document.add(new Paragraph(" "));
            LineSeparator ls = new LineSeparator();
            ls.setLineColor(csuGreen);
            ls.setLineWidth(1.5f);
            document.add(ls);

            // 2. Titre "Facture"
            Paragraph titlePara = new Paragraph("Facture", titleFont);
            titlePara.setSpacingBefore(15f);
            titlePara.setSpacingAfter(15f);
            document.add(titlePara);

            // 3. Métadonnées (Date, N° Facture, Code, Monnaie à gauche, Client à droite)
            PdfPTable metaTable = new PdfPTable(2);
            metaTable.setWidthPercentage(100);
            metaTable.setWidths(new float[]{1f, 1f});

            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(PdfPCell.NO_BORDER);

            String textMois = getMonthName(facture.getMois()) + " " + facture.getAnnee();
            Paragraph pDate = new Paragraph();
            pDate.add(new Chunk("DATE : ", labelFont));
            pDate.add(new Chunk(textMois, valFont));
            pDate.setSpacingAfter(4f);
            leftCell.addElement(pDate);

            String formatNumFacture = String.format("FACT-PHARMA-%d-%02d", facture.getAnnee(), facture.getMois());
            Paragraph pNum = new Paragraph();
            pNum.add(new Chunk("N° FACTURE : ", labelFont));
            pNum.add(new Chunk(formatNumFacture, valFont));
            pNum.setSpacingAfter(4f);
            leftCell.addElement(pNum);

            Paragraph pCode = new Paragraph();
            pCode.add(new Chunk("CODE : ", labelFont));
            pCode.add(new Chunk(codePharmacie, valFont));
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

            metaTable.setSpacingAfter(20f);
            document.add(metaTable);

            // 4. Tableau des lignes de facturation
            String[] headers = {
                "N°", "N° Bon", "Date", "Nom & Prénom", "Médicament",
                "P.U.", "Qté", "Montant", "Part bénéf.", "Part SEN-CSU"
            };
            float[] columnWidths = {4f, 8f, 12f, 20f, 24f, 8f, 4f, 8f, 8f, 8f};
            PdfPTable table = new PdfPTable(headers.length);
            table.setWidthPercentage(100);
            table.setWidths(columnWidths);

            for (String h : headers) {
                PdfPCell cell = new PdfPCell(new Paragraph(h, headFont));
                cell.setBackgroundColor(csuGreen);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                cell.setPadding(6f);
                cell.setBorderColor(borderLight);
                cell.setBorderWidth(0.5f);
                table.addCell(cell);
            }

            List<LigneFacture> lignes = facture.getLignes() != null ? facture.getLignes() : new ArrayList<>();
            int index = 1;
            double sumMontant = 0;
            double sumBenef = 0;
            double sumCsu = 0;

            for (LigneFacture ligne : lignes) {
                double total = ligne.getQuantite() * ligne.getPrixUnitaire();
                sumMontant += total;
                sumBenef += total * 0.5;
                sumCsu += total * 0.5;

                addTableCell(table, String.valueOf(index++), Element.ALIGN_CENTER, normalFont, borderLight);
                addTableCell(table, ligne.getPatientMatricule() != null ? ligne.getPatientMatricule() : "", Element.ALIGN_CENTER, normalFont, borderLight);
                addTableCell(table, dateFacture, Element.ALIGN_CENTER, normalFont, borderLight);
                addTableCell(table, ligne.getPatientNomPrenom() != null ? ligne.getPatientNomPrenom() : "", Element.ALIGN_LEFT, normalFont, borderLight);
                addTableCell(table, ligne.getMedicament() != null ? ligne.getMedicament() : "", Element.ALIGN_LEFT, normalFont, borderLight);
                addTableCell(table, formatNombre(ligne.getPrixUnitaire()), Element.ALIGN_CENTER, normalFont, borderLight);
                addTableCell(table, String.valueOf(ligne.getQuantite()), Element.ALIGN_CENTER, normalFont, borderLight);
                addTableCell(table, formatNombre(total), Element.ALIGN_CENTER, normalFont, borderLight);
                addTableCell(table, formatNombre(total * 0.5), Element.ALIGN_CENTER, normalFont, borderLight);
                addTableCell(table, formatNombre(total * 0.5), Element.ALIGN_CENTER, normalFont, borderLight);
            }

            // Ligne de Totaux
            PdfPCell totalLabel = new PdfPCell(new Paragraph("TOTAUX", totalFont));
            totalLabel.setColspan(7);
            totalLabel.setBackgroundColor(csuGreen);
            totalLabel.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalLabel.setVerticalAlignment(Element.ALIGN_MIDDLE);
            totalLabel.setPadding(6f);
            totalLabel.setBorderColor(borderLight);
            totalLabel.setBorderWidth(0.5f);
            table.addCell(totalLabel);

            PdfPCell totalMontant = new PdfPCell(new Paragraph(formatNombre(sumMontant), totalFont));
            totalMontant.setBackgroundColor(csuGreen);
            totalMontant.setHorizontalAlignment(Element.ALIGN_CENTER);
            totalMontant.setVerticalAlignment(Element.ALIGN_MIDDLE);
            totalMontant.setPadding(6f);
            totalMontant.setBorderColor(borderLight);
            totalMontant.setBorderWidth(0.5f);
            table.addCell(totalMontant);

            PdfPCell totalBenef = new PdfPCell(new Paragraph(formatNombre(sumBenef), totalFont));
            totalBenef.setBackgroundColor(csuGreen);
            totalBenef.setHorizontalAlignment(Element.ALIGN_CENTER);
            totalBenef.setVerticalAlignment(Element.ALIGN_MIDDLE);
            totalBenef.setPadding(6f);
            totalBenef.setBorderColor(borderLight);
            totalBenef.setBorderWidth(0.5f);
            table.addCell(totalBenef);

            PdfPCell totalCsu = new PdfPCell(new Paragraph(formatNombre(sumCsu), totalFont));
            totalCsu.setBackgroundColor(csuGreen);
            totalCsu.setHorizontalAlignment(Element.ALIGN_CENTER);
            totalCsu.setVerticalAlignment(Element.ALIGN_MIDDLE);
            totalCsu.setPadding(6f);
            totalCsu.setBorderColor(borderLight);
            totalCsu.setBorderWidth(0.5f);
            table.addCell(totalCsu);

            document.add(table);

            // 5. Encadré Montant à régler
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

            Paragraph pReglerVal = new Paragraph(formatNombre(sumCsu) + " FCFA", reglerValFont);
            pReglerVal.setAlignment(Element.ALIGN_CENTER);
            pReglerVal.setSpacingBefore(6f);
            boxCell.addElement(pReglerVal);

            summaryTable.addCell(boxCell);
            summaryTable.setSpacingBefore(20f);
            summaryTable.setSpacingAfter(15f);
            document.add(summaryTable);

            // 6. Phrase d'arrêt
            String amountInWords = convertToFrenchWords(Math.round(sumCsu));
            if (!amountInWords.isEmpty()) {
                amountInWords = Character.toUpperCase(amountInWords.charAt(0)) + amountInWords.substring(1);
            }
            String footerText = "Arrêtée la présente facture, pour la part SEN-CSU, à la somme de " + amountInWords + " francs CFA.";
            Paragraph footerPara = new Paragraph(footerText, footerFont);
            footerPara.setSpacingBefore(15f);
            document.add(footerPara);

            // 7. Zone Signature
            Paragraph signaturePara = new Paragraph();
            signaturePara.setAlignment(Element.ALIGN_RIGHT);
            signaturePara.setSpacingBefore(40f);
            signaturePara.add(new Chunk("------------------------------------------------------------------\n", new Font(Font.FontFamily.HELVETICA, 9f, Font.NORMAL, new BaseColor(180, 180, 180))));
            signaturePara.add(new Chunk("Le Pharmacien (cachet et signature)", signatureFont));
            document.add(signaturePara);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("Erreur lors de l'export PDF de la facture", e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void addTableCell(PdfPTable table, String text, int alignment, Font font, BaseColor borderColor) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(5f);
        cell.setBorderColor(borderColor);
        cell.setBorderWidth(0.5f);
        table.addCell(cell);
    }

    private String formatNombre(double v) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        DecimalFormat df = new DecimalFormat("#,##0", symbols);
        return df.format(v);
    }

    public static String convertToFrenchWords(long number) {
        if (number == 0) return "zéro";
        
        String[] units = {"", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf"};
        String[] tens = {"", "dix", "vingt", "trente", "quarante", "cinquante", "soixante", "soixante-dix", "quatre-vingt", "quatre-vingt-dix"};
        String[] teens = {"dix", "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf"};
        
        return convertUnderBillions(number, units, tens, teens).trim();
    }

    private static String convertUnderBillions(long number, String[] units, String[] tens, String[] teens) {
        if (number >= 1_000_000_000) {
            long billions = number / 1_000_000_000;
            long remainder = number % 1_000_000_000;
            String bStr = (billions == 1 ? "un milliard" : convertUnderBillions(billions, units, tens, teens) + " milliards");
            return bStr + (remainder > 0 ? " " + convertUnderBillions(remainder, units, tens, teens) : "");
        }
        if (number >= 1_000_000) {
            long millions = number / 1_000_000;
            long remainder = number % 1_000_000;
            String mStr = (millions == 1 ? "un million" : convertUnderBillions(millions, units, tens, teens) + " millions");
            return mStr + (remainder > 0 ? " " + convertUnderBillions(remainder, units, tens, teens) : "");
        }
        if (number >= 1_000) {
            long thousands = number / 1_000;
            long remainder = number % 1_000;
            String tStr = (thousands == 1 ? "mille" : convertUnderBillions(thousands, units, tens, teens) + " mille");
            return tStr + (remainder > 0 ? " " + convertUnderBillions(remainder, units, tens, teens) : "");
        }
        if (number >= 100) {
            long hundreds = number / 100;
            long remainder = number % 100;
            String hStr;
            if (hundreds == 1) {
                hStr = "cent";
            } else {
                hStr = units[(int) hundreds] + " cent";
                if (remainder == 0) hStr += "s";
            }
            return hStr + (remainder > 0 ? " " + convertUnderBillions(remainder, units, tens, teens) : "");
        }
        if (number >= 20) {
            long ten = number / 10;
            long unit = number % 10;
            if (ten == 7) {
                return "soixante" + (unit == 1 ? "-et-onze" : "-" + teens[(int) unit]);
            } else if (ten == 9) {
                return "quatre-vingt-" + teens[(int) unit];
            } else if (ten == 8) {
                if (unit == 0) {
                    return "quatre-vingts";
                } else {
                    return "quatre-vingt-" + units[(int) unit];
                }
            } else {
                String t = tens[(int) ten];
                if (unit == 1) {
                    return t + "-et-un";
                } else if (unit > 1) {
                    return t + "-" + units[(int) unit];
                }
                return t;
            }
        }
        if (number >= 10) {
            return teens[(int) (number - 10)];
        }
        return units[(int) number];
    }
}
