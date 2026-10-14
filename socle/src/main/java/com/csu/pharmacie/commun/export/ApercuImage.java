package com.csu.pharmacie.commun.export;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Aperçu en image (JPEG) d'un PDF généré, pour l'afficher dans le dossier patient.
 *
 * <p>Un PDF dans un {@code <iframe>} ne s'affiche pas partout : Chrome Android n'a pas de
 * visionneuse intégrée et laisse le cadre vide. Une image s'affiche sur tous les appareils.
 * Le PDF reste le document de référence (téléchargement, impression).</p>
 *
 * <p>Les pages sont empilées verticalement dans une seule image, séparées d'un filet gris.</p>
 */
public final class ApercuImage {

    /** Résolution : lisible en plein écran sur un téléphone, ~200-400 Ko par page A4. */
    private static final float DPI = 130f;
    /** Un document du dossier tient sur une ou deux pages ; au-delà, l'aperçu s'arrête. */
    private static final int PAGES_MAX = 4;
    private static final int ECART_PAGES = 12;
    private static final float QUALITE_JPEG = 0.85f;

    private ApercuImage() {
    }

    public static byte[] depuisPdf(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer rendu = new PDFRenderer(document);
            int nbPages = Math.min(document.getNumberOfPages(), PAGES_MAX);
            List<BufferedImage> pages = new ArrayList<>(nbPages);
            for (int i = 0; i < nbPages; i++) {
                pages.add(rendu.renderImageWithDPI(i, DPI, ImageType.RGB));
            }
            return jpeg(empiler(pages));
        } catch (IOException e) {
            throw new UncheckedIOException("Aperçu du document impossible", e);
        }
    }

    private static BufferedImage empiler(List<BufferedImage> pages) {
        if (pages.size() == 1) {
            return pages.get(0);
        }
        int largeur = pages.stream().mapToInt(BufferedImage::getWidth).max().orElse(1);
        int hauteur = pages.stream().mapToInt(BufferedImage::getHeight).sum() + ECART_PAGES * (pages.size() - 1);
        BufferedImage image = new BufferedImage(largeur, hauteur, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(0xE4E7EC));
            g.fillRect(0, 0, largeur, hauteur);
            int y = 0;
            for (BufferedImage page : pages) {
                g.drawImage(page, (largeur - page.getWidth()) / 2, y, null);
                y += page.getHeight() + ECART_PAGES;
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter ecrivain = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (ImageOutputStream flux = ImageIO.createImageOutputStream(sortie)) {
            ecrivain.setOutput(flux);
            ImageWriteParam parametres = ecrivain.getDefaultWriteParam();
            parametres.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parametres.setCompressionQuality(QUALITE_JPEG);
            ecrivain.write(null, new IIOImage(image, null, null), parametres);
        } finally {
            ecrivain.dispose();
        }
        return sortie.toByteArray();
    }
}
