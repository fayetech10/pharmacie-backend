package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.DossierPatientDto;
import com.csu.pharmacie.entity.*;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class PdfExportService {

    private static final Font TITLE_FONT = new Font(Font.FontFamily.HELVETICA, 18, Font.BOLD, BaseColor.DARK_GRAY);
    private static final Font SECTION_FONT = new Font(Font.FontFamily.HELVETICA, 14, Font.BOLD, BaseColor.DARK_GRAY);
    private static final Font NORMAL_FONT = new Font(Font.FontFamily.HELVETICA, 10, Font.NORMAL, BaseColor.BLACK);
    private static final Font BOLD_FONT = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.BLACK);
    private static final Font TABLE_HEADER_FONT = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.WHITE);

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public byte[] generatePatientDossierPdf(DossierPatientDto dossier) throws DocumentException {
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, out);

        document.open();

        addHeader(document, dossier.getPatient());
        document.add(Chunk.NEWLINE);

        addIdentitySection(document, dossier.getPatient());
        document.add(Chunk.NEWLINE);

        addSummarySection(document, dossier);
        document.add(Chunk.NEWLINE);

        if (dossier.getLettresGarantie() != null && !dossier.getLettresGarantie().isEmpty()) {
            addLettresGarantieSection(document, dossier);
            document.add(Chunk.NEWLINE);
        }

        if (dossier.getBonsCommande() != null && !dossier.getBonsCommande().isEmpty()) {
            addBonsCommandeSection(document, dossier);
            document.add(Chunk.NEWLINE);
        }

        if (dossier.getFacturesStructure() != null && !dossier.getFacturesStructure().isEmpty()) {
            addFacturesStructureSection(document, dossier);
            document.add(Chunk.NEWLINE);
        }

        document.close();
        return out.toByteArray();
    }

    private void addHeader(Document document, Patient patient) throws DocumentException {
        Paragraph title = new Paragraph("Dossier Bénéficiaire Consolidé", TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);

        Paragraph subtitle = new Paragraph(patient.getPrenom() + " " + patient.getNom(), SECTION_FONT);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        document.add(subtitle);
    }

    private void addIdentitySection(Document document, Patient patient) throws DocumentException {
        Paragraph sectionTitle = new Paragraph("Informations Personnelles", SECTION_FONT);
        document.add(sectionTitle);
        document.add(new Paragraph(" "));

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1, 2});

        addTableRow(table, "Régime", patient.getRegime() != null ? patient.getRegime().name() : "N/A");
        addTableRow(table, "Téléphone", patient.getTelephone() != null ? patient.getTelephone() : "—");
        addTableRow(table, "N° CNI", patient.getNumeroCni() != null ? patient.getNumeroCni() : "—");
        addTableRow(table, "N° Assuré", patient.getNumeroAssure() != null ? patient.getNumeroAssure() : "—");
        addTableRow(table, "Date de Naissance", patient.getDateNaissance() != null ? patient.getDateNaissance().format(DATE_FORMATTER) : "—");

        document.add(table);
    }

    private void addSummarySection(Document document, DossierPatientDto dossier) throws DocumentException {
        Paragraph sectionTitle = new Paragraph("Résumé des Prises en Charge", SECTION_FONT);
        document.add(sectionTitle);
        document.add(new Paragraph(" "));

        double totalSencsu = 0;
        if (dossier.getLignesFactureOfficine() != null) {
            totalSencsu += dossier.getLignesFactureOfficine().stream()
                    .mapToDouble(LigneFacture::getMontant)
                    .sum();
        }
        if (dossier.getFacturesStructure() != null) {
            for (FactureStructure fs : dossier.getFacturesStructure()) {
                if (fs.getLignes() != null) {
                    totalSencsu += fs.getLignes().stream()
                            .mapToDouble(LigneFactureStructure::getMontantSencsu)
                            .sum();
                }
            }
        }

        Paragraph total = new Paragraph("Total Prise en Charge SEN-CSU : " + formatCurrency(totalSencsu), BOLD_FONT);
        document.add(total);
    }

    private void addLettresGarantieSection(Document document, DossierPatientDto dossier) throws DocumentException {
        Paragraph sectionTitle = new Paragraph("Lettres de Garantie", SECTION_FONT);
        document.add(sectionTitle);
        document.add(new Paragraph(" "));

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{2, 2, 2, 2});

        addTableHeader(table, "N° Lettre", "Date", "Statut", "Montant CSU");

        for (LettreGarantie lg : dossier.getLettresGarantie()) {
            addTableCell(table, lg.getNumero());
            addTableCell(table, lg.getCreatedAt() != null ? lg.getCreatedAt().format(DATE_FORMATTER) : "—");
            addTableCell(table, lg.getStatut() != null ? lg.getStatut().name() : "—");
            addTableCell(table, formatCurrency(lg.getMontantTotalSencsu()));
        }

        document.add(table);
    }

    private void addBonsCommandeSection(Document document, DossierPatientDto dossier) throws DocumentException {
        Paragraph sectionTitle = new Paragraph("Pharmacie & Bons de Commande", SECTION_FONT);
        document.add(sectionTitle);
        document.add(new Paragraph(" "));

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{2, 3, 2, 2});

        addTableHeader(table, "Pharmacie", "Médicament", "Qté", "Montant CSU");

        if (dossier.getLignesFactureOfficine() != null) {
            for (LigneFacture l : dossier.getLignesFactureOfficine()) {
                addTableCell(table, l.getPharmacieNom() != null ? l.getPharmacieNom() : "—");
                addTableCell(table, l.getMedicament());
                addTableCell(table, String.valueOf(l.getQuantite()));
                addTableCell(table, formatCurrency(l.getMontant()));
            }
        }

        document.add(table);
    }

    private void addFacturesStructureSection(Document document, DossierPatientDto dossier) throws DocumentException {
        Paragraph sectionTitle = new Paragraph("Prestations en Structure Sanitaire", SECTION_FONT);
        document.add(sectionTitle);
        document.add(new Paragraph(" "));

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{3, 2, 1, 2});

        addTableHeader(table, "Désignation", "Date", "Qté", "Montant CSU");

        for (FactureStructure fs : dossier.getFacturesStructure()) {
            if (fs.getLignes() != null) {
                for (LigneFactureStructure l : fs.getLignes()) {
                    addTableCell(table, l.getDesignation());
                    addTableCell(table, l.getDatePriseEnCharge() != null ? l.getDatePriseEnCharge().format(DATE_FORMATTER) : "—");
                    addTableCell(table, String.valueOf(l.getQuantite()));
                    addTableCell(table, formatCurrency(l.getMontantSencsu()));
                }
            }
        }

        document.add(table);
    }

    private void addTableHeader(PdfPTable table, String... headers) {
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, TABLE_HEADER_FONT));
            cell.setBackgroundColor(new BaseColor(15, 23, 42)); // Tailwind slate-900
            cell.setPadding(5);
            table.addCell(cell);
        }
    }

    private void addTableRow(PdfPTable table, String label, String value) {
        PdfPCell cell1 = new PdfPCell(new Phrase(label, BOLD_FONT));
        cell1.setBorder(Rectangle.NO_BORDER);
        table.addCell(cell1);

        PdfPCell cell2 = new PdfPCell(new Phrase(value, NORMAL_FONT));
        cell2.setBorder(Rectangle.NO_BORDER);
        table.addCell(cell2);
    }

    private void addTableCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", NORMAL_FONT));
        cell.setPadding(5);
        table.addCell(cell);
    }

    private String formatCurrency(double amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        DecimalFormat df = new DecimalFormat("#,##0", symbols);
        return df.format(amount) + " FCFA";
    }
}
