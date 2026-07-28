package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.*;
import com.csu.pharmacie.repository.PharmacieRepository;
import com.csu.pharmacie.repository.RegionRepository;
import com.itextpdf.text.*;
import com.itextpdf.text.Document;
import com.itextpdf.text.Font;
import com.itextpdf.text.pdf.*;
import com.itextpdf.text.pdf.draw.LineSeparator;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
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
    private final com.csu.pharmacie.repository.FactureRepository factureRepository;

    /** Encre bleue (#003399) des valeurs « manuscrites », comme le rendu web (.paper-dots). */
    private static final BaseColor ENCRE_BLEUE = new BaseColor(0, 51, 153);
    /** Police manuscrite Caveat embarquée, chargée une seule fois ; null si le .ttf n'est pas fourni. */
    private static byte[] caveatTtf;
    private static boolean caveatChecked;

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
                "P.U.", "Qté", "Montant", "Part bénéf.", "Part SEN-CSU", "Observation"
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

                // Observation : signale les médicaments ajoutés par le pharmacien (non répertoriés).
                // La SEN-CSU s'appuie sur cette colonne pour valider leur intégration ou les exclure.
                Cell obsCell = row.createCell(10);
                obsCell.setCellValue(ligne.isAjouteParPharmacien()
                        ? "Médicament ajouté par le pharmacien — à valider par la SEN-CSU"
                        : "");
                obsCell.setCellStyle(cellLeftStyle);
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

            // Bandeau vert continu jusqu'à la colonne Observation.
            totalRow.createCell(10).setCellStyle(headerStyle);

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
            // Colonne Observation : largeur fixe (le texte d'alerte ne doit pas l'étirer démesurément).
            sheet.setColumnWidth(10, 52 * 256);

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

    public byte[] exportGlobalRegionExcel(List<Facture> facturesPharma, List<FactureStructure> facturesStruct) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            
            // --- Onglet 1 : Pharmacies ---
            Sheet sheetPharma = workbook.createSheet("Pharmacies");
            Row headerPharma = sheetPharma.createRow(0);
            String[] headersPharma = {"Pharmacie", "Mois/Année", "Date Facture", "N° Bon", "Patient", "Médicament", "P.U.", "Qté", "Montant Total", "Part SEN-CSU", "Observation"};
            for (int i = 0; i < headersPharma.length; i++) {
                headerPharma.createCell(i).setCellValue(headersPharma[i]);
            }

            int rowIdxP = 1;
            for (Facture f : facturesPharma) {
                String moisAnnee = getMonthName(f.getMois()) + " " + f.getAnnee();
                String dateStr = f.getCreatedAt() != null ? f.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) : "";
                if (f.getLignes() != null) {
                    for (LigneFacture ligne : f.getLignes()) {
                        Row row = sheetPharma.createRow(rowIdxP++);
                        row.createCell(0).setCellValue(f.getPharmacieNom() != null ? f.getPharmacieNom() : "");
                        row.createCell(1).setCellValue(moisAnnee);
                        row.createCell(2).setCellValue(dateStr);
                        row.createCell(3).setCellValue(ligne.getPatientMatricule() != null ? ligne.getPatientMatricule() : "");
                        row.createCell(4).setCellValue(ligne.getPatientNomPrenom() != null ? ligne.getPatientNomPrenom() : "");
                        row.createCell(5).setCellValue(ligne.getMedicament() != null ? ligne.getMedicament() : "");
                        row.createCell(6).setCellValue(ligne.getPrixUnitaire());
                        row.createCell(7).setCellValue(ligne.getQuantite());
                        double total = ligne.getQuantite() * ligne.getPrixUnitaire();
                        row.createCell(8).setCellValue(total);
                        row.createCell(9).setCellValue(total * 0.5); // 50% SEN-CSU en général pour pharmacie
                        row.createCell(10).setCellValue(ligne.isAjouteParPharmacien()
                                ? "Médicament ajouté par le pharmacien — à valider par la SEN-CSU"
                                : "");
                    }
                }
            }

            // --- Onglet 2 : Structures ---
            Sheet sheetStruct = workbook.createSheet("Structures Sanitaires");
            Row headerStruct = sheetStruct.createRow(0);
            String[] headersStruct = {"Structure", "Régime", "Mois/Année", "Date Facture", "N° Lettre", "Patient", "Prestation", "Montant Total", "Part SEN-CSU"};
            for (int i = 0; i < headersStruct.length; i++) {
                headerStruct.createCell(i).setCellValue(headersStruct[i]);
            }

            int rowIdxS = 1;
            for (FactureStructure f : facturesStruct) {
                String moisAnnee = getMonthName(f.getMois()) + " " + f.getAnnee();
                String dateStr = f.getCreatedAt() != null ? f.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) : "";
                String regimeStr = f.getRegime() != null ? f.getRegime().name() : "";
                
                if (f.getLignes() != null) {
                    for (LigneFactureStructure ligne : f.getLignes()) {
                        Row row = sheetStruct.createRow(rowIdxS++);
                        row.createCell(0).setCellValue(f.getStructureNom() != null ? f.getStructureNom() : "");
                        row.createCell(1).setCellValue(regimeStr);
                        row.createCell(2).setCellValue(moisAnnee);
                        row.createCell(3).setCellValue(dateStr);
                        row.createCell(4).setCellValue(ligne.getPatientMatricule() != null ? ligne.getPatientMatricule() : "");
                        
                        String nom = ligne.getPatientNom() != null ? ligne.getPatientNom() : "";
                        String prenom = ligne.getPatientPrenom() != null ? ligne.getPatientPrenom() : "";
                        row.createCell(5).setCellValue((prenom + " " + nom).trim());
                        
                        row.createCell(6).setCellValue(ligne.getDesignation() != null ? ligne.getDesignation() : "");
                        row.createCell(7).setCellValue(ligne.getMontant());
                        row.createCell(8).setCellValue(ligne.getMontantSencsu());
                    }
                }
            }

            for(int i=0; i<headersPharma.length; i++) sheetPharma.autoSizeColumn(i);
            for(int i=0; i<headersStruct.length; i++) sheetStruct.autoSizeColumn(i);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors de l'export global Excel", e);
        }
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
                "P.U.", "Qté", "Montant", "Part bénéf.", "Part SEN-CSU", "Observation"
            };
            float[] columnWidths = {4f, 7f, 10f, 17f, 20f, 7f, 4f, 8f, 8f, 8f, 18f};
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
                // Observation : signale à la SEN-CSU un médicament ajouté hors référentiel.
                addTableCell(table, ligne.isAjouteParPharmacien()
                        ? "Médicament ajouté par le pharmacien — à valider par la SEN-CSU" : "",
                        Element.ALIGN_LEFT, normalFont, borderLight);
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

            // Cellule vide sous la colonne Observation, pour fermer la ligne de totaux.
            PdfPCell totalObservation = new PdfPCell(new Paragraph("", totalFont));
            totalObservation.setBackgroundColor(csuGreen);
            totalObservation.setPadding(6f);
            totalObservation.setBorderColor(borderLight);
            totalObservation.setBorderWidth(0.5f);
            table.addCell(totalObservation);

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

    private static final String NUM_ENVOI_SR = "SR Thiès (77 762 63 29)";

    /**
     * QR code contenant le numéro du document : la structure sanitaire ou la pharmacie le
     * scanne pour retrouver la lettre/le bon sans saisir le numéro à la main.
     */
    private Image qrCodeNumero(String numero, float taille) {
        try {
            BarcodeQRCode qr = new BarcodeQRCode(numero, 1, 1, null);
            Image img = qr.getImage();
            img.scaleAbsolute(taille, taille);
            img.setAlignment(Element.ALIGN_CENTER);
            return img;
        } catch (Exception e) {
            return null; // le PDF reste utilisable sans QR
        }
    }

    /**
     * Police « manuscrite » bleue (Caveat) pour les valeurs saisies, reproduisant le rendu
     * web (.paper-dots). Repli automatique en Times italique bleu si la police n'est pas
     * embarquée : déposer Caveat-Bold.ttf (ou Caveat.ttf) dans backend/src/main/resources/fonts/.
     */
    private Font manuscrite(float taille) {
        if (!caveatChecked) {
            caveatChecked = true;
            for (String nom : new String[]{"fonts/Caveat-Bold.ttf", "fonts/Caveat.ttf",
                    "fonts/Caveat-Regular.ttf", "Caveat-Bold.ttf", "Caveat.ttf"}) {
                try {
                    caveatTtf = new ClassPathResource(nom).getInputStream().readAllBytes();
                    break;
                } catch (Exception ignored) { /* nom suivant */ }
            }
        }
        if (caveatTtf != null) {
            try {
                BaseFont bf = BaseFont.createFont("Caveat.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, caveatTtf, null);
                return new Font(bf, taille, Font.NORMAL, ENCRE_BLEUE);
            } catch (Exception e) { /* repli ci-dessous */ }
        }
        return new Font(Font.FontFamily.TIMES_ROMAN, taille * 0.68f, Font.ITALIC, ENCRE_BLEUE);
    }

    /** Ligne de formulaire « label imprimé + valeur manuscrite bleue », comme .paper-row du site. */
    private Paragraph champManuscrit(String label, String valeur, Font labelFont, float tailleValeur) {
        Paragraph p = new Paragraph();
        p.setLeading(tailleValeur * 1.25f);
        p.setSpacingAfter(9f);
        p.add(new Chunk(label + "  ", labelFont));
        p.add(new Chunk(valeur == null ? "" : valeur, manuscrite(tailleValeur)));
        return p;
    }

    public byte[] exportLettreGarantiePdf(LettreGarantie lettre) {
        return exportLettreGarantiePdf(lettre, null, null);
    }

    /**
     * Export Excel des patients enregistrés (lettres de garantie), après filtrage
     * par régime / mois / année dans l'onglet « Patients enregistrés » du BCSU.
     */
    public byte[] exportLettresGarantieExcel(List<LettreGarantie> lettres) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Patients enregistrés");

            CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            String[] colonnes = {"N° Lettre", "Date", "Prénom", "Nom", "Sexe", "Date de naissance",
                    "Téléphone", "Régime", "Statut", "Montant total", "Part SEN-CSU", "Structure"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < colonnes.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(colonnes[i]);
                cell.setCellStyle(headerStyle);
            }

            DateTimeFormatter df = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            int r = 1;
            for (LettreGarantie l : lettres) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(l.getNumero());
                row.createCell(1).setCellValue(l.getCreatedAt() != null ? l.getCreatedAt().format(df) : "");
                row.createCell(2).setCellValue(l.getPatientPrenom() != null ? l.getPatientPrenom() : "");
                row.createCell(3).setCellValue(l.getPatientNom() != null ? l.getPatientNom() : "");
                row.createCell(4).setCellValue(l.getPatientSexe() != null ? l.getPatientSexe() : "");
                row.createCell(5).setCellValue(l.getPatientDateNaissance() != null ? l.getPatientDateNaissance().format(df) : "");
                row.createCell(6).setCellValue(l.getPatientTelephone() != null ? l.getPatientTelephone() : "");
                row.createCell(7).setCellValue(l.getRegime() != null ? l.getRegime().name() : "");
                row.createCell(8).setCellValue(l.getStatut() != null ? l.getStatut().name() : "");
                row.createCell(9).setCellValue(l.getMontantTotal());
                row.createCell(10).setCellValue(l.getMontantTotalSencsu());
                row.createCell(11).setCellValue(l.getStructureNom() != null ? l.getStructureNom() : "");
            }
            for (int i = 0; i < colonnes.length; i++) {
                sheet.setColumnWidth(i, 20 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors de l'export Excel des patients enregistrés", e);
        }
    }

    /** Image iText à partir d'une data-URL (paramétrage cachet/signature), null si illisible. */
    private Image imageFromDataUrl(String dataUrl, float maxW, float maxH) {
        if (dataUrl == null || dataUrl.isBlank()) return null;
        try {
            String base64 = dataUrl.contains(",") ? dataUrl.substring(dataUrl.indexOf(',') + 1) : dataUrl;
            Image img = Image.getInstance(java.util.Base64.getDecoder().decode(base64));
            img.scaleToFit(maxW, maxH);
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    public byte[] exportLettreGarantiePdf(LettreGarantie lettre, String cachetImage, String signatureImage) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 42, 28, 42, 36);
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new DashedCutLinePageEvent());
            document.open();

            BaseColor textDark = new BaseColor(30, 30, 30);
            BaseColor lineGray = new BaseColor(180, 180, 180);

            Font nationalHeaderFont = new Font(Font.FontFamily.HELVETICA, 14, Font.NORMAL, textDark);
            Font nationalSubHeaderFont = new Font(Font.FontFamily.TIMES_ROMAN, 12, Font.NORMAL, textDark);
            Font titleFont = new Font(Font.FontFamily.HELVETICA, 15, Font.NORMAL, textDark);
            Font fieldFont = new Font(Font.FontFamily.TIMES_ROMAN, 14, Font.NORMAL, textDark);
            Font fieldBoldFont = new Font(Font.FontFamily.TIMES_ROMAN, 16, Font.BOLD, textDark);
            Font noteFont = new Font(Font.FontFamily.TIMES_ROMAN, 13, Font.NORMAL, textDark);

            // 1. En-tête (Logo à gauche, Textes nationaux à droite)
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{4.8f, 5.2f});
            headerTable.getDefaultCell().setBorder(PdfPCell.NO_BORDER);

            // Logo & Label Agence
            PdfPCell leftHeaderCell = new PdfPCell();
            leftHeaderCell.setBorder(PdfPCell.NO_BORDER);
            try {
                byte[] logoBytes = new ClassPathResource("logo-csu.png").getInputStream().readAllBytes();
                Image logoImg = Image.getInstance(logoBytes);
                logoImg.scaleToFit(52, 52);
                leftHeaderCell.addElement(logoImg);
            } catch (Exception e) {
                Paragraph fallbackText = new Paragraph("SEN-CSU", new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, new BaseColor(47, 110, 84)));
                leftHeaderCell.addElement(fallbackText);
            }
            Paragraph agencyText = new Paragraph("AGENCE SENEGALAISE DE LA\nCOUVERTURE SANITAIRE\nUNIVERSELLE", new Font(Font.FontFamily.HELVETICA, 7.5f, Font.BOLD, new BaseColor(47, 110, 84)));
            agencyText.setSpacingBefore(3f);
            leftHeaderCell.addElement(agencyText);
            headerTable.addCell(leftHeaderCell);

            // République du Sénégal
            PdfPCell rightHeaderCell = new PdfPCell();
            rightHeaderCell.setBorder(PdfPCell.NO_BORDER);
            rightHeaderCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            Paragraph pRep = new Paragraph("REPUBLIQUE DU SENEGAL", nationalHeaderFont);
            pRep.setAlignment(Element.ALIGN_CENTER);
            rightHeaderCell.addElement(pRep);
            Paragraph pMotto = new Paragraph("UN PEUPLE - UN BUT - UNE FOI", nationalSubHeaderFont);
            pMotto.setAlignment(Element.ALIGN_CENTER);
            pMotto.setSpacingBefore(12f);
            rightHeaderCell.addElement(pMotto);
            Image qrLettre = qrCodeNumero(lettre.getNumero(), 74f);
            if (qrLettre != null) {
                qrLettre.setSpacingBefore(10f);
                rightHeaderCell.addElement(qrLettre);
                Paragraph pQrLabel = new Paragraph("Scanner pour retrouver le dossier",
                        new Font(Font.FontFamily.HELVETICA, 7f, Font.ITALIC, textDark));
                pQrLabel.setAlignment(Element.ALIGN_CENTER);
                rightHeaderCell.addElement(pQrLabel);
            }
            headerTable.addCell(rightHeaderCell);

            document.add(headerTable);
            document.add(new Paragraph(" \n"));

            // 2. Encadré « LETTRE DE GARANTIE » centré (comme le site), puis N° en manuscrit
            BaseColor bleuTitre = new BaseColor(43, 92, 143); // #2b5c8f (.paper-title-box)
            PdfPTable titleTable = new PdfPTable(1);
            titleTable.setWidthPercentage(46);
            titleTable.setHorizontalAlignment(Element.ALIGN_CENTER);
            PdfPCell boxCell = new PdfPCell();
            boxCell.setBorder(PdfPCell.BOX);
            boxCell.setBorderWidth(1.4f);
            boxCell.setBorderColor(bleuTitre);
            boxCell.setPaddingTop(7f);
            boxCell.setPaddingBottom(7f);
            boxCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            Paragraph pTitle = new Paragraph("LETTRE DE GARANTIE", new Font(Font.FontFamily.HELVETICA, 16, Font.NORMAL, bleuTitre));
            pTitle.setAlignment(Element.ALIGN_CENTER);
            boxCell.addElement(pTitle);
            titleTable.addCell(boxCell);
            document.add(titleTable);

            Paragraph pNum = new Paragraph();
            pNum.setSpacingBefore(18f);
            pNum.add(new Chunk("N° ", new Font(Font.FontFamily.HELVETICA, 14, Font.BOLD, textDark)));
            pNum.add(new Chunk(lettre.getNumero(), manuscrite(19f)));
            document.add(pNum);

            // 3. Souche + champs du formulaire (valeurs « manuscrites » bleues, comme le site)
            Font labelFont = new Font(Font.FontFamily.TIMES_ROMAN, 12.5f, Font.NORMAL, textDark);

            Paragraph pSouche = new Paragraph("Souche", new Font(Font.FontFamily.TIMES_ROMAN, 15, Font.NORMAL, textDark));
            pSouche.setSpacingBefore(16f);
            pSouche.setSpacingAfter(14f);
            document.add(pSouche);

            String structure = lettre.getStructureNom() != null ? lettre.getStructureNom() : "";
            String codeAssure = (lettre.getPatientNumeroAssure() != null && !lettre.getPatientNumeroAssure().isBlank())
                    ? lettre.getPatientNumeroAssure()
                    : (lettre.getCniNumeroOcr() != null ? lettre.getCniNumeroOcr() : "");
            String sexeLbl = "M".equalsIgnoreCase(lettre.getPatientSexe()) ? "Masculin"
                    : ("F".equalsIgnoreCase(lettre.getPatientSexe()) ? "Féminin"
                    : (lettre.getPatientSexe() != null ? lettre.getPatientSexe() : ""));
            String naissance = lettre.getPatientDateNaissance() != null
                    ? lettre.getPatientDateNaissance().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "";
            String motifs = "";
            if (lettre.getPrestations() != null && !lettre.getPrestations().isEmpty()) {
                motifs = lettre.getPrestations().stream()
                        .map(pr -> (pr.getMotifCesarienne() != null && !pr.getMotifCesarienne().isBlank())
                                ? pr.getDesignation() + " (" + pr.getMotifCesarienne() + ")" : pr.getDesignation())
                        .collect(java.util.stream.Collectors.joining(", "));
            }
            String taux = (lettre.getRegime() == Regime.CONTRIBUTIF) ? "80%" : "100%";
            String dateCreation = lettre.getCreatedAt() != null
                    ? lettre.getCreatedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    : LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

            document.add(champManuscrit("STRUCTURE :", structure, labelFont, 18f));
            document.add(champManuscrit("Prénom et nom de l'assuré :", lettre.getPatientPrenom() + " " + lettre.getPatientNom(), labelFont, 18f));
            document.add(champManuscrit("Type d'assuré :", regimeLabel(lettre.getRegime()), labelFont, 18f));
            document.add(champManuscrit("Code assuré/immatriculation :", codeAssure, labelFont, 18f));
            document.add(champManuscrit("Date de naissance :", naissance, labelFont, 18f));
            document.add(champManuscrit("Sexe :", sexeLbl, labelFont, 18f));
            document.add(champManuscrit("Motif :", motifs, labelFont, 18f));

            Paragraph pTaux = champManuscrit("Taux de prise en charge :", taux, labelFont, 18f);
            pTaux.setSpacingBefore(20f);
            document.add(pTaux);

            Paragraph pDate = champManuscrit("Date :", dateCreation, labelFont, 18f);
            pDate.setSpacingBefore(20f);
            pDate.setSpacingAfter(20f);
            document.add(pDate);

            // 4. Signatures & Cachet (images du paramétrage BCSU si renseignées)
            Paragraph pSignatureTitle = new Paragraph("Signature et cachet", new Font(Font.FontFamily.TIMES_ROMAN, 13, Font.BOLD, textDark));
            Image imgSignature = imageFromDataUrl(signatureImage, 150f, 60f);
            Image imgCachet = imageFromDataUrl(cachetImage, 150f, 60f);
            if (imgSignature != null || imgCachet != null) {
                pSignatureTitle.setSpacingAfter(8f);
                document.add(pSignatureTitle);
                PdfPTable signTable = new PdfPTable(2);
                signTable.setWidthPercentage(60);
                signTable.setHorizontalAlignment(Element.ALIGN_LEFT);
                signTable.getDefaultCell().setBorder(PdfPCell.NO_BORDER);
                PdfPCell sigCell = new PdfPCell();
                sigCell.setBorder(PdfPCell.NO_BORDER);
                if (imgSignature != null) sigCell.addElement(imgSignature);
                signTable.addCell(sigCell);
                PdfPCell cachetCell = new PdfPCell();
                cachetCell.setBorder(PdfPCell.NO_BORDER);
                if (imgCachet != null) cachetCell.addElement(imgCachet);
                signTable.addCell(cachetCell);
                signTable.setSpacingAfter(10f);
                document.add(signTable);
            } else {
                pSignatureTitle.setSpacingAfter(70f);
                document.add(pSignatureTitle);
            }

            // 5. Validité en bas
            Paragraph pVal = new Paragraph("Valable pour une période d'un mois à partir de la date de délivrance", noteFont);
            pVal.setSpacingBefore(15f);
            document.add(pVal);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("Erreur lors de l'export PDF de la lettre de garantie", e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static class DashedCutLinePageEvent extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            PdfContentByte canvas = writer.getDirectContent();
            canvas.saveState();
            canvas.setLineWidth(0.8f);
            canvas.setLineDash(4f, 4f);
            canvas.moveTo(page.getRight(12), page.getBottom(12));
            canvas.lineTo(page.getRight(12), page.getTop(12));
            canvas.stroke();
            canvas.restoreState();
        }
    }

    public byte[] exportBonCommandePdf(BonCommande bon) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 45, 45, 45, 45);
            PdfWriter.getInstance(document, out);
            document.open();

            BaseColor textDark = new BaseColor(30, 30, 30);
            BaseColor lineGray = new BaseColor(180, 180, 180);

            Font agencyFont = new Font(Font.FontFamily.HELVETICA, 8, Font.BOLD, new BaseColor(47, 110, 84));
            Font titleFont = new Font(Font.FontFamily.HELVETICA, 13, Font.BOLD, textDark);
            Font metaLabelFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, textDark);
            Font metaValFont = new Font(Font.FontFamily.HELVETICA, 10, Font.NORMAL, textDark);
            Font thFont = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, textDark);
            Font tdFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL, textDark);

            // 1. En-tête (Logo + Titre + QR code du numéro)
            PdfPTable headerTable = new PdfPTable(3);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{4.6f, 3.6f, 1.8f});
            headerTable.getDefaultCell().setBorder(PdfPCell.NO_BORDER);

            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(PdfPCell.NO_BORDER);
            try {
                byte[] logoBytes = new ClassPathResource("logo-csu.png").getInputStream().readAllBytes();
                Image logoImg = Image.getInstance(logoBytes);
                logoImg.scaleToFit(40, 40);
                leftCell.addElement(logoImg);
            } catch (Exception e) {}
            Paragraph agencyText = new Paragraph("AGENCE SENEGALAISE\nDE LA COUVERTURE\nSANITAIRE UNIVERSELLE", agencyFont);
            agencyText.setSpacingBefore(3f);
            leftCell.addElement(agencyText);
            headerTable.addCell(leftCell);

            // Titre encadré "BON DE COMMANDE"
            PdfPCell titleCell = new PdfPCell();
            titleCell.setBorder(PdfPCell.BOX);
            titleCell.setBorderWidth(1.2f);
            titleCell.setBorderColor(textDark);
            titleCell.setPadding(8f);
            titleCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            Paragraph pTitle = new Paragraph("BON DE COMMANDE", titleFont);
            pTitle.setAlignment(Element.ALIGN_CENTER);
            titleCell.addElement(pTitle);
            headerTable.addCell(titleCell);

            PdfPCell qrCell = new PdfPCell();
            qrCell.setBorder(PdfPCell.NO_BORDER);
            qrCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            qrCell.setVerticalAlignment(Element.ALIGN_TOP);
            Image qrBon = qrCodeNumero(bon.getNumero(), 66f);
            if (qrBon != null) {
                qrCell.addElement(qrBon);
                Paragraph pQrLabel = new Paragraph("Scanner pour retrouver le bon",
                        new Font(Font.FontFamily.HELVETICA, 6.5f, Font.ITALIC, textDark));
                pQrLabel.setAlignment(Element.ALIGN_CENTER);
                qrCell.addElement(pQrLabel);
            }
            headerTable.addCell(qrCell);

            document.add(headerTable);
            document.add(new Paragraph(" \n"));

            // 2. N° + sous-titre + métadonnées (valeurs manuscrites bleues, comme le site)
            String dateCreation = bon.getCreatedAt() != null
                    ? bon.getCreatedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    : LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

            Paragraph pNum = new Paragraph();
            pNum.add(new Chunk("N° ", new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, textDark)));
            pNum.add(new Chunk(bon.getNumero(), manuscrite(17f)));
            pNum.setSpacingAfter(2f);
            document.add(pNum);

            Paragraph pSub = new Paragraph("Médicament du circuit d'officine (prise en charge 50%)",
                    new Font(Font.FontFamily.HELVETICA, 9, Font.ITALIC, new BaseColor(107, 114, 128)));
            pSub.setSpacingAfter(12f);
            document.add(pSub);

            Font bonLabelFont = new Font(Font.FontFamily.HELVETICA, 11, Font.NORMAL, textDark);
            document.add(champManuscrit("Date d'émission :", dateCreation, bonLabelFont, 15f));
            document.add(champManuscrit("Nom du bénéficiaire :", (bon.getPatientPrenom() + " " + bon.getPatientNom()).trim(), bonLabelFont, 15f));
            document.add(champManuscrit("Régime :", regimeLabel(bon.getRegime()), bonLabelFont, 15f));
            Paragraph pLg = champManuscrit("Lettre de garantie :", bon.getLettreGarantieNumero(), bonLabelFont, 15f);
            pLg.setSpacingAfter(14f);
            document.add(pLg);

            // 4. Tableau (Désignation, Quantité, Prix Unitaire, Total, Taux de prise en charge)
            // Avec des lignes vides pour que le pharmacien puisse écrire dedans directement.
            String[] headers = {"Désignation", "Qté", "P. Unit.", "Total", "PEC"};
            float[] widths = {42f, 12f, 16f, 16f, 14f};

            PdfPTable table = new PdfPTable(headers.length);
            table.setWidthPercentage(100);
            table.setWidths(widths);

            for (String h : headers) {
                PdfPCell cell = new PdfPCell(new Paragraph(h, thFont));
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setPadding(6f);
                cell.setBorderWidth(0.8f);
                cell.setBorderColor(textDark);
                table.addCell(cell);
            }

            // Rechercher les médicaments servis pour ce bon
            List<com.csu.pharmacie.entity.LigneFacture> medicamentsServis = new ArrayList<>();
            List<com.csu.pharmacie.entity.Facture> toutesFactures = factureRepository.findAll();
            for (com.csu.pharmacie.entity.Facture f : toutesFactures) {
                if (f.getLignes() != null) {
                    for (com.csu.pharmacie.entity.LigneFacture lf : f.getLignes()) {
                        if (lf.getBonCommandeNumero() != null && lf.getBonCommandeNumero().equals(bon.getNumero()) && lf.getStatutLigne() != com.csu.pharmacie.entity.StatutLigne.REJETEE) {
                            medicamentsServis.add(lf);
                        }
                    }
                }
            }

            if (!medicamentsServis.isEmpty()) {
                double totalMontant = 0;
                for (com.csu.pharmacie.entity.LigneFacture lf : medicamentsServis) {
                    addTableCell(table, lf.getMedicament() != null ? lf.getMedicament() : "", Element.ALIGN_LEFT, tdFont, textDark);
                    addTableCell(table, String.valueOf(lf.getQuantite()), Element.ALIGN_CENTER, tdFont, textDark);
                    addTableCell(table, String.valueOf((int)lf.getPrixUnitaire()) + " F", Element.ALIGN_CENTER, tdFont, textDark);
                    addTableCell(table, String.valueOf((int)lf.getMontant()) + " F", Element.ALIGN_CENTER, tdFont, textDark);
                    addTableCell(table, "50%", Element.ALIGN_CENTER, tdFont, textDark);
                    totalMontant += lf.getMontant();
                }
            } else {
                int rowCount = bon.getNombreLignes() > 0
                        ? bon.getNombreLignes()
                        : (bon.getPrestations() != null && !bon.getPrestations().isEmpty() ? bon.getPrestations().size() : 3);

                for (int i = 0; i < rowCount; i++) {
                    addTableCell(table, "", Element.ALIGN_LEFT, tdFont, textDark);
                    addTableCell(table, "", Element.ALIGN_CENTER, tdFont, textDark);
                    addTableCell(table, "", Element.ALIGN_CENTER, tdFont, textDark);
                    addTableCell(table, "", Element.ALIGN_CENTER, tdFont, textDark);
                    addTableCell(table, "50%", Element.ALIGN_CENTER, tdFont, textDark);
                }
            }

            // Lignes de totaux
            PdfPCell labelTotal = new PdfPCell(new Paragraph("TOTAL GENERAL", thFont));
            labelTotal.setColspan(3);
            labelTotal.setHorizontalAlignment(Element.ALIGN_RIGHT);
            labelTotal.setPadding(5f);
            labelTotal.setBorderColor(textDark);
            table.addCell(labelTotal);

            // Cellule vide pour total général
            table.addCell(new PdfPCell() {{ setBorderColor(textDark); }});
            table.addCell(new PdfPCell() {{ setBorderColor(textDark); }});

            PdfPCell labelPatient = new PdfPCell(new Paragraph("Montant à payer par le patient", thFont));
            labelPatient.setColspan(3);
            labelPatient.setHorizontalAlignment(Element.ALIGN_RIGHT);
            labelPatient.setPadding(5f);
            labelPatient.setBorderColor(textDark);
            table.addCell(labelPatient);

            table.addCell(new PdfPCell() {{ setBorderColor(textDark); }});
            table.addCell(new PdfPCell() {{ setBorderColor(textDark); }});

            PdfPCell labelTiers = new PdfPCell(new Paragraph("Montant à facturer au tiers payant", thFont));
            labelTiers.setColspan(3);
            labelTiers.setHorizontalAlignment(Element.ALIGN_RIGHT);
            labelTiers.setPadding(5f);
            labelTiers.setBorderColor(textDark);
            table.addCell(labelTiers);

            table.addCell(new PdfPCell() {{ setBorderColor(textDark); }});
            table.addCell(new PdfPCell() {{ setBorderColor(textDark); }});

            document.add(table);
            document.add(new Paragraph(" \n"));

            // 5. Signature / Cachet
            Paragraph pCachet = new Paragraph("Cachet Assureur", new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, textDark));
            pCachet.setSpacingBefore(10f);
            document.add(pCachet);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("Erreur lors de l'export PDF du bon de commande", e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Feuille de soins au format du formulaire papier ASCSU :
     * en-tête (logo + titre encadré), ligne Date/Structure/N°, tableau des régimes
     * (contributif / non contributif) avec la catégorie cochée, encadrés code assuré
     * et n° lettre de garantie, identité + accompagnant, tableau « PRISE EN CHARGE »
     * (Date, Désignation, Montant, Part Assuré, Part Assureur) et les trois signatures.
     */
    public byte[] exportFeuilleSoinsPdf(FeuilleSoins feuille) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 32, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            BaseColor textDark = new BaseColor(30, 30, 30);
            Font agencyFont = new Font(Font.FontFamily.HELVETICA, 8, Font.BOLD, textDark);
            Font titleFont = new Font(Font.FontFamily.HELVETICA, 14, Font.BOLD, textDark);
            Font labelFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, textDark);
            Font valFont = new Font(Font.FontFamily.HELVETICA, 10, Font.NORMAL, textDark);
            Font thFont = new Font(Font.FontFamily.HELVETICA, 8.5f, Font.BOLD, textDark);
            Font tdFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL, textDark);
            Font sectionFont = new Font(Font.FontFamily.HELVETICA, 11, Font.BOLD, textDark);

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

            // 1. En-tête : logo + nom d'agence à gauche, titre encadré à droite.
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{4.5f, 5.5f});
            headerTable.getDefaultCell().setBorder(PdfPCell.NO_BORDER);

            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(PdfPCell.NO_BORDER);
            try {
                byte[] logoBytes = new ClassPathResource("logo-csu.png").getInputStream().readAllBytes();
                Image logoImg = Image.getInstance(logoBytes);
                logoImg.scaleToFit(42, 42);
                leftCell.addElement(logoImg);
            } catch (Exception e) { /* logo indisponible : le texte suffit */ }
            Paragraph agencyText = new Paragraph("AGENCE SENEGALAISE\nDE LA COUVERTURE\nSANITAIRE UNIVERSELLE", agencyFont);
            agencyText.setSpacingBefore(2f);
            leftCell.addElement(agencyText);
            headerTable.addCell(leftCell);

            PdfPCell titleCell = new PdfPCell();
            titleCell.setBorder(PdfPCell.NO_BORDER);
            titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            PdfPTable titleBox = new PdfPTable(1);
            titleBox.setWidthPercentage(85);
            PdfPCell titleBoxCell = new PdfPCell(new Paragraph("FEUILLE DE SOINS", titleFont));
            titleBoxCell.setBorderWidth(1.2f);
            titleBoxCell.setBorderColor(textDark);
            titleBoxCell.setPadding(7f);
            titleBoxCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            titleBox.addCell(titleBoxCell);
            titleCell.addElement(titleBox);
            // QR code du numéro de lettre de garantie : la structure le scanne pour
            // retrouver le dossier et préremplir la facture (même circuit que la LG).
            String lgNumero = feuille.getLettreGarantieNumero();
            if (lgNumero != null && !lgNumero.isBlank()) {
                Image qrFeuille = qrCodeNumero(lgNumero, 60f);
                if (qrFeuille != null) {
                    qrFeuille.setSpacingBefore(8f);
                    titleCell.addElement(qrFeuille);
                    Paragraph pQrLabel = new Paragraph("Scanner pour retrouver le dossier",
                            new Font(Font.FontFamily.HELVETICA, 7f, Font.ITALIC, textDark));
                    pQrLabel.setAlignment(Element.ALIGN_CENTER);
                    titleCell.addElement(pQrLabel);
                }
            }
            headerTable.addCell(titleCell);
            document.add(headerTable);
            document.add(new Paragraph(" "));

            // 2. Ligne Date / Structure / N°
            String dateEmission = feuille.getCreatedAt() != null
                    ? feuille.getCreatedAt().format(fmt)
                    : LocalDate.now().format(fmt);
            PdfPTable infoLine = new PdfPTable(3);
            infoLine.setWidthPercentage(100);
            infoLine.setWidths(new float[]{3f, 4.5f, 2.5f});
            infoLine.getDefaultCell().setBorder(PdfPCell.NO_BORDER);
            PdfPCell dCell = new PdfPCell(); dCell.setBorder(PdfPCell.NO_BORDER);
            addMetaLine(dCell, "Date : ", dateEmission, labelFont, valFont);
            infoLine.addCell(dCell);
            PdfPCell sCell = new PdfPCell(); sCell.setBorder(PdfPCell.NO_BORDER);
            addMetaLine(sCell, "Structure : ", feuille.getStructureNom() != null ? feuille.getStructureNom() : "............................", labelFont, valFont);
            infoLine.addCell(sCell);
            PdfPCell nCell = new PdfPCell(); nCell.setBorder(PdfPCell.NO_BORDER);
            addMetaLine(nCell, "N° : ", feuille.getNumero(), labelFont, valFont);
            infoLine.addCell(nCell);
            document.add(infoLine);
            document.add(new Paragraph(" "));

            // 3. Tableau des régimes : contributif (3 colonnes) / non contributif (5 colonnes).
            String[] colonnesRegime = {"Classique", "CMU élèves", "CMU Daara",
                    "Femmes enceintes", "Enfant de 0-5 ans", "BSF", "CEC", "PLAN SESAME"};
            int colonneCochee = colonneRegimeFeuille(feuille.getRegime());

            PdfPTable regimeTable = new PdfPTable(8);
            regimeTable.setWidthPercentage(100);
            regimeTable.setWidths(new float[]{12f, 12f, 10f, 13f, 16f, 9f, 9f, 14f});

            PdfPCell contribHeader = new PdfPCell(new Paragraph("REGIME CONTRIBUTIF", thFont));
            contribHeader.setColspan(3);
            contribHeader.setHorizontalAlignment(Element.ALIGN_CENTER);
            contribHeader.setPadding(4f);
            contribHeader.setBorderColor(textDark);
            regimeTable.addCell(contribHeader);
            PdfPCell nonContribHeader = new PdfPCell(new Paragraph("REGIME NON CONTRIBUTIF", thFont));
            nonContribHeader.setColspan(5);
            nonContribHeader.setHorizontalAlignment(Element.ALIGN_CENTER);
            nonContribHeader.setPadding(4f);
            nonContribHeader.setBorderColor(textDark);
            regimeTable.addCell(nonContribHeader);

            for (String c : colonnesRegime) {
                PdfPCell cell = new PdfPCell(new Paragraph(c, thFont));
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setPadding(4f);
                cell.setBorderColor(textDark);
                regimeTable.addCell(cell);
            }
            for (int i = 0; i < colonnesRegime.length; i++) {
                PdfPCell cell = new PdfPCell(new Paragraph(i == colonneCochee ? "X" : " ",
                        new Font(Font.FontFamily.HELVETICA, 11, Font.BOLD, textDark)));
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setMinimumHeight(18f);
                cell.setBorderColor(textDark);
                regimeTable.addCell(cell);
            }
            document.add(regimeTable);
            document.add(new Paragraph(" "));

            // 4. Encadrés : code assuré / n° immatriculation + n° lettre de garantie.
            PdfPTable boxLine = new PdfPTable(2);
            boxLine.setWidthPercentage(100);
            boxLine.setWidths(new float[]{5.6f, 4.4f});
            PdfPCell codeBox = new PdfPCell();
            codeBox.setBorderWidth(1f);
            codeBox.setBorderColor(textDark);
            codeBox.setPadding(6f);
            Paragraph pCode = new Paragraph();
            pCode.add(new Chunk("Code assuré / Num Immatriculation : ", labelFont));
            pCode.add(new Chunk(feuille.getCodeAssure() != null ? feuille.getCodeAssure() : "........................", valFont));
            codeBox.addElement(pCode);
            boxLine.addCell(codeBox);
            PdfPCell lgBox = new PdfPCell();
            lgBox.setBorderWidth(1f);
            lgBox.setBorderColor(textDark);
            lgBox.setPadding(6f);
            Paragraph pLg = new Paragraph();
            pLg.add(new Chunk("N° Lettre de Garantie : ", labelFont));
            pLg.add(new Chunk(feuille.getLettreGarantieNumero() != null ? feuille.getLettreGarantieNumero() : "....................", valFont));
            lgBox.addElement(pLg);
            boxLine.addCell(lgBox);
            document.add(boxLine);
            document.add(new Paragraph(" "));

            // 5. Identité du bénéficiaire.
            Paragraph pNom = new Paragraph();
            pNom.add(new Chunk("Prénom(s) et Nom : ", labelFont));
            pNom.add(new Chunk((feuille.getPatientPrenom() != null ? feuille.getPatientPrenom() : "") + " "
                    + (feuille.getPatientNom() != null ? feuille.getPatientNom() : ""), valFont));
            pNom.setSpacingAfter(8f);
            document.add(pNom);

            PdfPTable identLine = new PdfPTable(3);
            identLine.setWidthPercentage(100);
            identLine.setWidths(new float[]{3.4f, 2.6f, 4f});
            identLine.getDefaultCell().setBorder(PdfPCell.NO_BORDER);
            PdfPCell sexeCell = new PdfPCell(); sexeCell.setBorder(PdfPCell.NO_BORDER);
            boolean masculin = "M".equalsIgnoreCase(feuille.getSexe()) || "H".equalsIgnoreCase(feuille.getSexe());
            boolean feminin = "F".equalsIgnoreCase(feuille.getSexe());
            addMetaLine(sexeCell, "Sexe :   ", "M [" + (masculin ? " X " : "   ") + "]    F [" + (feminin ? " X " : "   ") + "]", labelFont, valFont);
            identLine.addCell(sexeCell);
            PdfPCell ageCell = new PdfPCell(); ageCell.setBorder(PdfPCell.NO_BORDER);
            addMetaLine(ageCell, "AGE : ", feuille.getAge() != null ? feuille.getAge() + " ans" : "......................", labelFont, valFont);
            identLine.addCell(ageCell);
            PdfPCell diagCell = new PdfPCell(); diagCell.setBorder(PdfPCell.NO_BORDER);
            addMetaLine(diagCell, "Diagnostic : ", feuille.getDiagnostic() != null ? feuille.getDiagnostic() : "............................................", labelFont, valFont);
            identLine.addCell(diagCell);
            document.add(identLine);

            // 6. Accompagnant.
            Paragraph pAcc = new Paragraph("ACCOMPAGNANT", sectionFont);
            pAcc.setSpacingBefore(6f);
            pAcc.setSpacingAfter(4f);
            document.add(pAcc);
            PdfPTable accLine = new PdfPTable(2);
            accLine.setWidthPercentage(100);
            accLine.setWidths(new float[]{6f, 4f});
            accLine.getDefaultCell().setBorder(PdfPCell.NO_BORDER);
            PdfPCell accNomCell = new PdfPCell(); accNomCell.setBorder(PdfPCell.NO_BORDER);
            addMetaLine(accNomCell, "Prénom(s) et Nom : ",
                    feuille.getAccompagnantPrenomNom() != null ? feuille.getAccompagnantPrenomNom() : "................................................................",
                    labelFont, valFont);
            accLine.addCell(accNomCell);
            PdfPCell telCell = new PdfPCell(); telCell.setBorder(PdfPCell.NO_BORDER);
            addMetaLine(telCell, "Téléphone : ",
                    feuille.getPatientTelephone() != null ? feuille.getPatientTelephone() : "................................",
                    labelFont, valFont);
            accLine.addCell(telCell);
            document.add(accLine);

            // 7. PRISE EN CHARGE : lignes remplies + lignes vides (la structure complète à la main).
            Paragraph pPec = new Paragraph("PRISE EN CHARGE", sectionFont);
            pPec.setSpacingBefore(8f);
            pPec.setSpacingAfter(5f);
            document.add(pPec);

            String[] pecHeaders = {"Date", "Désignation des prestations", "Montant", "Part Assuré", "Part Assureur"};
            PdfPTable pecTable = new PdfPTable(pecHeaders.length);
            pecTable.setWidthPercentage(100);
            pecTable.setWidths(new float[]{11f, 49f, 13f, 13f, 14f});
            for (String h : pecHeaders) {
                PdfPCell cell = new PdfPCell(new Paragraph(h, thFont));
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setPadding(5f);
                cell.setBorderColor(textDark);
                regimeCellBackground(cell);
                pecTable.addCell(cell);
            }

            List<PrestationSoins> prestations = feuille.getPrestations() != null ? feuille.getPrestations() : new ArrayList<>();
            int lignesVides = Math.max(0, 14 - prestations.size());
            for (PrestationSoins p : prestations) {
                addTableCell(pecTable, p.getDate() != null ? p.getDate().format(fmt) : "", Element.ALIGN_CENTER, tdFont, textDark);
                addTableCell(pecTable, p.getDesignation() != null ? p.getDesignation() : "", Element.ALIGN_LEFT, tdFont, textDark);
                addTableCell(pecTable, formatNombre(p.getMontant()), Element.ALIGN_RIGHT, tdFont, textDark);
                addTableCell(pecTable, formatNombre(p.getPartAssure()), Element.ALIGN_RIGHT, tdFont, textDark);
                addTableCell(pecTable, formatNombre(p.getPartAssureur()), Element.ALIGN_RIGHT, tdFont, textDark);
            }
            for (int i = 0; i < lignesVides; i++) {
                for (int c = 0; c < pecHeaders.length; c++) {
                    PdfPCell cell = new PdfPCell(new Paragraph(" ", tdFont));
                    cell.setMinimumHeight(17f);
                    cell.setBorderColor(textDark);
                    cell.setBorderWidth(0.5f);
                    pecTable.addCell(cell);
                }
            }
            // Ligne de totaux si la prise en charge est remplie.
            if (!prestations.isEmpty()) {
                PdfPCell totalLabel = new PdfPCell(new Paragraph("TOTAL", thFont));
                totalLabel.setColspan(2);
                totalLabel.setHorizontalAlignment(Element.ALIGN_RIGHT);
                totalLabel.setPadding(5f);
                totalLabel.setBorderColor(textDark);
                pecTable.addCell(totalLabel);
                addTableCell(pecTable, formatNombre(feuille.getMontantTotal()), Element.ALIGN_RIGHT, thFont, textDark);
                addTableCell(pecTable, formatNombre(feuille.getMontantTotalAssure()), Element.ALIGN_RIGHT, thFont, textDark);
                addTableCell(pecTable, formatNombre(feuille.getMontantTotalAssureur()), Element.ALIGN_RIGHT, thFont, textDark);
            }
            document.add(pecTable);

            // 8. Signatures : assureur / prestataire / assuré(e).
            document.add(new Paragraph(" "));
            PdfPTable signTable = new PdfPTable(3);
            signTable.setWidthPercentage(100);
            signTable.getDefaultCell().setBorder(PdfPCell.NO_BORDER);
            Font signFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLDITALIC, textDark);
            String[] signatures = {"Signature de l'assureur", "Signature du prestataire", "Signature de l'assuré(e)"};
            int[] aligns = {Element.ALIGN_LEFT, Element.ALIGN_CENTER, Element.ALIGN_RIGHT};
            for (int i = 0; i < signatures.length; i++) {
                PdfPCell cell = new PdfPCell(new Paragraph(signatures[i], signFont));
                cell.setBorder(PdfPCell.NO_BORDER);
                cell.setHorizontalAlignment(aligns[i]);
                cell.setPaddingTop(26f);
                signTable.addCell(cell);
            }
            document.add(signTable);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("Erreur lors de l'export PDF de la feuille de soins", e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Fond léger des en-têtes du tableau de prise en charge. */
    private void regimeCellBackground(PdfPCell cell) {
        cell.setBackgroundColor(new BaseColor(241, 245, 244));
    }

    /**
     * Colonne du tableau des régimes du formulaire papier à cocher pour la catégorie
     * de la feuille (-1 si la catégorie n'y figure pas, ex. dialyses).
     */
    private int colonneRegimeFeuille(Regime regime) {
        if (regime == null) return -1;
        switch (regime) {
            case CONTRIBUTIF: return 0;   // Classique
            case NDONGO_DARA: return 2;   // CMU Daara / élèves
            case CESARIENNE: return 3;    // Femmes enceintes
            case ZERO_CINQ_ANS: return 4; // Enfant de 0-5 ans
            case BSF: return 5;
            case BSF_CEC: return 5;
            case CEC: return 6;
            case SESAME: return 7;        // PLAN SESAME
            default: return -1;
        }
    }

    private void addMetaLine(PdfPCell cell, String label, String value, Font labelFont, Font valFont) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label, labelFont));
        p.add(new Chunk(value != null ? value : "", valFont));
        p.setSpacingAfter(4f);
        cell.addElement(p);
    }

    private void addLabelValue(PdfPCell cell, String label, String value, Font labelFont, Font valFont) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label, labelFont));
        p.add(new Chunk(value != null ? value : "", valFont));
        p.setSpacingAfter(4f);
        cell.addElement(p);
    }

    private void addTotalCell(PdfPTable table, String text, BaseColor bg, BaseColor borderColor, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setBackgroundColor(bg);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(6f);
        cell.setBorderColor(borderColor);
        cell.setBorderWidth(0.5f);
        table.addCell(cell);
    }

    private String regimeLabel(Regime regime) {
        if (regime == null) return "";
        switch (regime) {
            case CONTRIBUTIF: return "Classique (Contributif)";
            case SESAME: return "Sésame";
            case CESARIENNE: return "Césarienne";
            case ZERO_CINQ_ANS: return "Enfants 0-5 ans";
            case DIALYSE_PERITONEALE: return "Dialyse péritonéale";
            case HEMODIALYSE: return "Hémodialyse";
            case BSF: return "BSF (Bourse de Sécurité Familiale)";
            case CEC: return "CEC (Carte Égalité des Chances)";
            case NDONGO_DARA: return "Ndongo Dara / Élève";
            case DIALYSE: return "Dialyse (ancien)";
            case BSF_CEC: return "BSF / CEC (ancien)";
            default: return regime.name();
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
