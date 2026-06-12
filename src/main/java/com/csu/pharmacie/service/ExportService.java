package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.*;
import com.csu.pharmacie.repository.PharmacieRepository;
import com.csu.pharmacie.repository.RegionRepository;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

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
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Facture");

            // Configurer l'affichage des lignes de grille
            sheet.setDisplayGridlines(true);

            // Charger les informations de la pharmacie et de la localité
            String pharmacieNom = facture.getPharmacieNom();
            String localite = "";
            String codePharmacie = "PH";

            Pharmacie pharmacie = pharmacieRepository.findById(facture.getPharmacieId()).orElse(null);
            if (pharmacie != null) {
                pharmacieNom = pharmacie.getNom();
                codePharmacie = pharmacie.getCode();
                Region region = regionRepository.findById(pharmacie.getRegionId()).orElse(null);
                if (region != null) {
                    localite = region.getNom();
                }
            }

            // Définition des Styles
            org.apache.poi.ss.usermodel.Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            titleFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_GREEN.getIndex());

            org.apache.poi.ss.usermodel.CellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setFont(titleFont);

            org.apache.poi.ss.usermodel.Font metaLabelFont = workbook.createFont();
            metaLabelFont.setBold(true);
            metaLabelFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_GREEN.getIndex());

            org.apache.poi.ss.usermodel.CellStyle metaLabelStyle = workbook.createCellStyle();
            metaLabelStyle.setFont(metaLabelFont);

            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());

            org.apache.poi.ss.usermodel.CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_GREEN.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            headerStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);

            org.apache.poi.ss.usermodel.CellStyle cellStyle = workbook.createCellStyle();
            cellStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            cellStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            cellStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            cellStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);

            org.apache.poi.ss.usermodel.CellStyle totalStyle = workbook.createCellStyle();
            totalStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_GREEN.getIndex());
            totalStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            totalStyle.setFont(headerFont);
            totalStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            totalStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            totalStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            totalStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);

            // 1. Titre
            Row titleRow = sheet.createRow(0);
            titleRow.createCell(0).setCellValue("FACTURE MOIS " + getMonthName(facture.getMois()).toUpperCase());
            titleRow.getCell(0).setCellStyle(titleStyle);

            // 2. Métadonnées
            createMetadataRow(sheet, 2, "Pharmacie", pharmacieNom, metaLabelStyle);
            createMetadataRow(sheet, 3, "Localite", localite, metaLabelStyle);
            createMetadataRow(sheet, 4, "Periode de facturation", getMonthName(facture.getMois()) + " " + facture.getAnnee(), metaLabelStyle);

            String numFacture = String.format("F-%s-%d-%02d", codePharmacie, facture.getAnnee(), facture.getMois());
            createMetadataRow(sheet, 5, "N° de facture", numFacture, metaLabelStyle);

            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
            String dateFacture = facture.getCreatedAt() != null ? facture.getCreatedAt().format(formatter) : java.time.LocalDate.now().format(formatter);
            createMetadataRow(sheet, 6, "Date facturation", dateFacture, metaLabelStyle);

            // 3. En-têtes du tableau (Row 9 -> index 8)
            Row headersRow = sheet.createRow(8);
            String[] headers = {
                "N° patient", "Date", "Prénom/Nom", "Matricule/Carte", "Medicament",
                "Prix unitaire (FCFA)", "Qte", "Montant total", "Part benef. 50%", "Part SEN-CSU 50%"
            };
            for (int i = 0; i < headers.length; i++) {
                org.apache.poi.ss.usermodel.Cell cell = headersRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 4. Remplissage des lignes de données (Row 10 -> index 9)
            List<LigneFacture> lignes = facture.getLignes();
            int rowIdx = 9;
            int patientCounter = 1;

            String lastPatientMatricule = null;
            String lastPatientNom = null;

            double sumPrixUnitaire = 0;
            double sumMontantTotal = 0;
            double sumPartBenef = 0;
            double sumPartCsu = 0;

            for (LigneFacture ligne : lignes) {
                Row row = sheet.createRow(rowIdx++);

                boolean isNewPatient = lastPatientMatricule == null ||
                        !lastPatientMatricule.equals(ligne.getPatientMatricule()) ||
                        !lastPatientNom.equals(ligne.getPatientNomPrenom());

                if (isNewPatient) {
                    lastPatientMatricule = ligne.getPatientMatricule();
                    lastPatientNom = ligne.getPatientNomPrenom();

                    row.createCell(0).setCellValue(String.format("%04d", patientCounter++));
                    row.createCell(1).setCellValue(dateFacture);
                    row.createCell(2).setCellValue(ligne.getPatientNomPrenom());
                    row.createCell(3).setCellValue(ligne.getPatientMatricule());
                } else {
                    row.createCell(0).setCellValue("");
                    row.createCell(1).setCellValue("");
                    row.createCell(2).setCellValue("");
                    row.createCell(3).setCellValue("");
                }

                row.createCell(4).setCellValue(ligne.getMedicament());
                row.createCell(5).setCellValue(ligne.getPrixUnitaire());
                row.createCell(6).setCellValue(ligne.getQuantite());

                double total = ligne.getQuantite() * ligne.getPrixUnitaire();
                row.createCell(7).setCellValue(total);
                row.createCell(8).setCellValue(total * 0.5);
                row.createCell(9).setCellValue(total * 0.5);

                for (int i = 0; i < 10; i++) {
                    if (row.getCell(i) == null) {
                        row.createCell(i);
                    }
                    row.getCell(i).setCellStyle(cellStyle);
                }

                sumPrixUnitaire += ligne.getPrixUnitaire();
                sumMontantTotal += total;
                sumPartBenef += total * 0.5;
                sumPartCsu += total * 0.5;
            }

            // 5. Ligne de Totaux
            Row totalRow = sheet.createRow(rowIdx);
            totalRow.createCell(0).setCellValue("TOTAL");
            totalRow.createCell(5).setCellValue(sumPrixUnitaire);
            totalRow.createCell(7).setCellValue(sumMontantTotal);
            totalRow.createCell(8).setCellValue(sumPartBenef);
            totalRow.createCell(9).setCellValue(sumPartCsu);

            for (int i = 0; i < 10; i++) {
                if (totalRow.getCell(i) == null) {
                    totalRow.createCell(i);
                }
                totalRow.getCell(i).setCellStyle(totalStyle);
            }

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

    private void createMetadataRow(Sheet sheet, int rowIdx, String label, String value, org.apache.poi.ss.usermodel.CellStyle labelStyle) {
        Row row = sheet.createRow(rowIdx);
        org.apache.poi.ss.usermodel.Cell cellLabel = row.createCell(0);
        cellLabel.setCellValue(label);
        cellLabel.setCellStyle(labelStyle);
        row.createCell(2).setCellValue(value);
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

    /** PDF détaillé d'une facture (en-tête + lignes + total), calqué sur l'export Excel par facture. */
    public byte[] exportFacturePdf(Facture facture) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String pharmacieNom = facture.getPharmacieNom();
            String localite = "";
            String codePharmacie = "PH";
            Pharmacie pharmacie = pharmacieRepository.findById(facture.getPharmacieId()).orElse(null);
            if (pharmacie != null) {
                pharmacieNom = pharmacie.getNom();
                codePharmacie = pharmacie.getCode();
                Region region = regionRepository.findById(pharmacie.getRegionId()).orElse(null);
                if (region != null) {
                    localite = region.getNom();
                }
            }

            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
            String dateFacture = facture.getCreatedAt() != null
                    ? facture.getCreatedAt().format(formatter)
                    : java.time.LocalDate.now().format(formatter);
            String numFacture = String.format("F-%s-%d-%02d", codePharmacie, facture.getAnnee(), facture.getMois());

            Document document = new Document(com.itextpdf.text.PageSize.A4.rotate(), 28, 28, 28, 28);
            PdfWriter.getInstance(document, out);
            document.open();

            com.itextpdf.text.BaseColor green = new com.itextpdf.text.BaseColor(4, 120, 87);
            com.itextpdf.text.Font titleFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 16, com.itextpdf.text.Font.BOLD, green);
            com.itextpdf.text.Font labelFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 10, com.itextpdf.text.Font.BOLD);
            com.itextpdf.text.Font normalFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 10);
            com.itextpdf.text.Font headFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 9, com.itextpdf.text.Font.BOLD, com.itextpdf.text.BaseColor.WHITE);

            document.add(new Paragraph("FACTURE MOIS " + getMonthName(facture.getMois()).toUpperCase(), titleFont));
            document.add(new Paragraph(" "));
            document.add(new Paragraph("Pharmacie : " + pharmacieNom, normalFont));
            document.add(new Paragraph("Localité : " + localite, normalFont));
            document.add(new Paragraph("Période de facturation : " + getMonthName(facture.getMois()) + " " + facture.getAnnee(), normalFont));
            document.add(new Paragraph("N° de facture : " + numFacture, normalFont));
            document.add(new Paragraph("Date facturation : " + dateFacture, normalFont));
            document.add(new Paragraph("Statut : " + facture.getStatut().name(), normalFont));
            document.add(new Paragraph(" "));

            String[] headers = {
                "N° patient", "Prénom/Nom", "Matricule", "Médicament",
                "PU (FCFA)", "Qté", "Montant", "Part bénéf. 50%", "Part SEN-CSU 50%"
            };
            com.itextpdf.text.pdf.PdfPTable table = new com.itextpdf.text.pdf.PdfPTable(headers.length);
            table.setWidthPercentage(100);
            for (String h : headers) {
                com.itextpdf.text.pdf.PdfPCell cell = new com.itextpdf.text.pdf.PdfPCell(new Paragraph(h, headFont));
                cell.setBackgroundColor(green);
                cell.setHorizontalAlignment(com.itextpdf.text.Element.ALIGN_CENTER);
                cell.setPadding(4);
                table.addCell(cell);
            }

            List<LigneFacture> lignes = facture.getLignes() != null ? facture.getLignes() : new java.util.ArrayList<>();
            int patientCounter = 1;
            String lastMat = null, lastNom = null;
            double sumMontant = 0, sumBenef = 0, sumCsu = 0;

            for (LigneFacture ligne : lignes) {
                boolean isNewPatient = lastMat == null
                        || !java.util.Objects.equals(lastMat, ligne.getPatientMatricule())
                        || !java.util.Objects.equals(lastNom, ligne.getPatientNomPrenom());
                String num = "", nom = "", mat = "";
                if (isNewPatient) {
                    lastMat = ligne.getPatientMatricule();
                    lastNom = ligne.getPatientNomPrenom();
                    num = String.format("%04d", patientCounter++);
                    nom = ligne.getPatientNomPrenom() != null ? ligne.getPatientNomPrenom() : "";
                    mat = ligne.getPatientMatricule() != null ? ligne.getPatientMatricule() : "";
                }
                double total = ligne.getQuantite() * ligne.getPrixUnitaire();
                sumMontant += total;
                sumBenef += total * 0.5;
                sumCsu += total * 0.5;

                table.addCell(new com.itextpdf.text.pdf.PdfPCell(new Paragraph(num, normalFont)));
                table.addCell(new com.itextpdf.text.pdf.PdfPCell(new Paragraph(nom, normalFont)));
                table.addCell(new com.itextpdf.text.pdf.PdfPCell(new Paragraph(mat, normalFont)));
                table.addCell(new com.itextpdf.text.pdf.PdfPCell(new Paragraph(ligne.getMedicament() != null ? ligne.getMedicament() : "", normalFont)));
                table.addCell(new com.itextpdf.text.pdf.PdfPCell(new Paragraph(formatNombre(ligne.getPrixUnitaire()), normalFont)));
                table.addCell(new com.itextpdf.text.pdf.PdfPCell(new Paragraph(String.valueOf(ligne.getQuantite()), normalFont)));
                table.addCell(new com.itextpdf.text.pdf.PdfPCell(new Paragraph(formatNombre(total), normalFont)));
                table.addCell(new com.itextpdf.text.pdf.PdfPCell(new Paragraph(formatNombre(total * 0.5), normalFont)));
                table.addCell(new com.itextpdf.text.pdf.PdfPCell(new Paragraph(formatNombre(total * 0.5), normalFont)));
            }

            com.itextpdf.text.pdf.PdfPCell totalLabel = new com.itextpdf.text.pdf.PdfPCell(new Paragraph("TOTAL", labelFont));
            totalLabel.setColspan(6);
            totalLabel.setHorizontalAlignment(com.itextpdf.text.Element.ALIGN_RIGHT);
            totalLabel.setPadding(4);
            table.addCell(totalLabel);
            table.addCell(new com.itextpdf.text.pdf.PdfPCell(new Paragraph(formatNombre(sumMontant), labelFont)));
            table.addCell(new com.itextpdf.text.pdf.PdfPCell(new Paragraph(formatNombre(sumBenef), labelFont)));
            table.addCell(new com.itextpdf.text.pdf.PdfPCell(new Paragraph(formatNombre(sumCsu), labelFont)));

            document.add(table);
            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("Erreur lors de l'export PDF de la facture", e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String formatNombre(double v) {
        return String.format("%,.0f", v);
    }
}
