package com.csu.pharmacie.service;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.Document;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.Utilities;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfPageEventHelper;
import com.itextpdf.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * PDF des identifiants de connexion à remettre aux structures ou aux pharmacies : titre,
 * contexte (type, district, région), consignes de première connexion, puis un tableau
 * N° | Nom | Identifiant | Mot de passe. Gabarit validé le 14/10/2026 (postes du district
 * de Thiès) : une page A4 pour une quarantaine de lignes, en-tête répété au-delà.
 */
final class IdentifiantsPdf {

    /** Une ligne du tableau ; le mot de passe vient de {@link ExportIdentifiants#motDePasse}. */
    record Ligne(String nom, String identifiant, String motDePasse) {
    }

    /**
     * @param contexte     « Postes de santé — District de Thies (THDT08) · Région de Thiès »
     * @param effectif     « 34 postes de santé »
     * @param colonneNom   en-tête de la 2e colonne (« Poste de santé », « Pharmacie »…)
     * @param destinataire « chaque poste », « chaque pharmacie »… (consigne de remise)
     */
    record Entete(String contexte, String effectif, String colonneNom, String destinataire) {
    }

    private static final BaseColor BLEU = new BaseColor(0x1F, 0x4E, 0x78);
    private static final BaseColor BLEU_CLAIR = new BaseColor(0xEA, 0xF1, 0xF8);
    private static final BaseColor GRIS = new BaseColor(0x5F, 0x6B, 0x7A);
    private static final BaseColor TRAIT = new BaseColor(0xC9, 0xD3, 0xDE);
    private static final BaseColor TEXTE = new BaseColor(0x2B, 0x34, 0x40);
    private static final BaseColor FOND_CONSIGNE = new BaseColor(0xFF, 0xF6, 0xDD);
    private static final BaseColor OCRE = new BaseColor(0xE0, 0xA8, 0x00);

    private static final float MARGE = Utilities.millimetersToPoints(18);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);

    private IdentifiantsPdf() {
    }

    static byte[] generer(Entete entete, List<Ligne> lignes) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BaseFont helv = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            BaseFont helvGras = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            BaseFont helvItalique = BaseFont.createFont(BaseFont.HELVETICA_OBLIQUE, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            BaseFont mono = BaseFont.createFont(BaseFont.COURIER, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            BaseFont monoGras = BaseFont.createFont(BaseFont.COURIER_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);

            Document doc = new Document(PageSize.A4, MARGE, MARGE,
                    Utilities.millimetersToPoints(14), Utilities.millimetersToPoints(20));
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new PiedDePage(helv));
            doc.addTitle("Identifiants de connexion");
            doc.open();

            Paragraph titre = new Paragraph("Identifiants de connexion", new Font(helvGras, 18, Font.NORMAL, BLEU));
            titre.setSpacingAfter(4);
            doc.add(titre);
            Font sousTitre = new Font(helv, 10.5f, Font.NORMAL, GRIS);
            Paragraph contexte = new Paragraph(14, entete.contexte(), sousTitre);
            doc.add(contexte);
            Paragraph effectif = new Paragraph(14, entete.effectif() + " · Édité le "
                    + LocalDate.now(ZoneId.of("Africa/Dakar")).format(DATE), sousTitre);
            effectif.setSpacingAfter(10);
            doc.add(effectif);

            doc.add(consigne(entete.destinataire(), helv, helvGras));
            doc.add(tableau(entete.colonneNom(), lignes, helv, helvGras, helvItalique, mono, monoGras));

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de générer le PDF des identifiants : " + e.getMessage(), e);
        }
    }

    /** Encadré jaune à filet ocre : comment se connecter et à qui remettre chaque ligne. */
    private static PdfPTable consigne(String destinataire, BaseFont helv, BaseFont helvGras) {
        Font normal = new Font(helv, 9, Font.NORMAL, TEXTE);
        Font gras = new Font(helvGras, 9, Font.NORMAL, TEXTE);
        Phrase texte = new Phrase();
        texte.add(new Chunk("Première connexion : ", gras));
        texte.add(new Chunk("saisir l'identifiant et le mot de passe ci-dessous. Le mot de passe initial doit être ", normal));
        texte.add(new Chunk("changé dès la première connexion", gras));
        texte.add(new Chunk(". Ces identifiants sont personnels : remettre à " + destinataire
                + " uniquement la ligne qui le concerne.", normal));

        PdfPCell cellule = new PdfPCell(texte);
        cellule.setBackgroundColor(FOND_CONSIGNE);
        cellule.setBorder(Rectangle.LEFT);
        cellule.setBorderWidthLeft(3);
        cellule.setBorderColorLeft(OCRE);
        cellule.setLeading(0, 1.35f);
        cellule.setPaddingLeft(9);
        cellule.setPaddingRight(8);
        cellule.setPaddingTop(5);
        cellule.setPaddingBottom(8);

        PdfPTable encadre = new PdfPTable(1);
        encadre.setWidthPercentage(100);
        encadre.setSpacingAfter(12);
        encadre.addCell(cellule);
        return encadre;
    }

    private static PdfPTable tableau(String colonneNom, List<Ligne> lignes, BaseFont helv, BaseFont helvGras,
                                     BaseFont helvItalique, BaseFont mono, BaseFont monoGras) throws Exception {
        PdfPTable table = new PdfPTable(new float[]{11, 89, 38, 36});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);

        Font fEntete = new Font(helvGras, 9.5f, Font.NORMAL, BaseColor.WHITE);
        String[] entetes = {"N°", colonneNom, "Identifiant", "Mot de passe"};
        for (int c = 0; c < entetes.length; c++) {
            PdfPCell cellule = new PdfPCell(new Phrase(entetes[c], fEntete));
            cellule.setBackgroundColor(BLEU);
            cellule.setBorder(Rectangle.NO_BORDER);
            cellule.setHorizontalAlignment(c == 0 ? Element.ALIGN_CENTER : Element.ALIGN_LEFT);
            cellule.setVerticalAlignment(Element.ALIGN_MIDDLE);
            cellule.setUseAscender(true);
            cellule.setPaddingTop(6);
            cellule.setPaddingBottom(7);
            cellule.setPaddingLeft(6);
            table.addCell(cellule);
        }

        Font fNumero = new Font(helv, 9.5f, Font.NORMAL, GRIS);
        Font fNom = new Font(helv, 9.5f, Font.NORMAL, BaseColor.BLACK);
        Font fIdentifiant = new Font(monoGras, 9.5f, Font.NORMAL, BaseColor.BLACK);
        Font fMotDePasse = new Font(mono, 9.5f, Font.NORMAL, BaseColor.BLACK);
        // « (personnalisé) », « (aucun compte) »… : une mention, pas un mot de passe à recopier.
        Font fMention = new Font(helvItalique, 8.5f, Font.NORMAL, GRIS);

        for (int i = 0; i < lignes.size(); i++) {
            Ligne l = lignes.get(i);
            BaseColor fond = i % 2 == 1 ? BLEU_CLAIR : BaseColor.WHITE;
            String mdp = l.motDePasse() == null ? "" : l.motDePasse();
            PdfPCell[] cellules = {
                    new PdfPCell(new Phrase(String.valueOf(i + 1), fNumero)),
                    new PdfPCell(new Phrase(l.nom() == null ? "" : l.nom(), fNom)),
                    new PdfPCell(new Phrase(l.identifiant() == null || l.identifiant().isBlank() ? "—" : l.identifiant(), fIdentifiant)),
                    new PdfPCell(new Phrase(mdp, mdp.startsWith("(") ? fMention : fMotDePasse))
            };
            for (int c = 0; c < cellules.length; c++) {
                PdfPCell cellule = cellules[c];
                cellule.setBackgroundColor(fond);
                cellule.setBorder(Rectangle.BOTTOM | (c == 0 ? Rectangle.LEFT : 0) | (c == cellules.length - 1 ? Rectangle.RIGHT : 0));
                cellule.setBorderColor(TRAIT);
                cellule.setBorderWidth(0.4f);
                cellule.setHorizontalAlignment(c == 0 ? Element.ALIGN_CENTER : Element.ALIGN_LEFT);
                cellule.setVerticalAlignment(Element.ALIGN_MIDDLE);
                cellule.setUseAscender(true);
                cellule.setPaddingTop(3.5f);
                cellule.setPaddingBottom(5);
                cellule.setPaddingLeft(6);
                table.addCell(cellule);
            }
        }
        return table;
    }

    /** Filet, mention de confidentialité et numéro de page en bas de chaque page. */
    private static final class PiedDePage extends PdfPageEventHelper {
        private final Font police;

        PiedDePage(BaseFont helv) {
            this.police = new Font(helv, 8, Font.NORMAL, GRIS);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float gauche = document.left();
            float droite = document.right();
            float yFilet = Utilities.millimetersToPoints(13);
            cb.saveState();
            cb.setColorStroke(TRAIT);
            cb.setLineWidth(0.5f);
            cb.moveTo(gauche, yFilet);
            cb.lineTo(droite, yFilet);
            cb.stroke();
            cb.restoreState();
            float yTexte = Utilities.millimetersToPoints(9);
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase("Document confidentiel — identifiants de connexion à la plateforme CSU", police),
                    gauche, yTexte, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber(), police), droite, yTexte, 0);
        }
    }
}
