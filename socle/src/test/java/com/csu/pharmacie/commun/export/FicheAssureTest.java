package com.csu.pharmacie.commun.export;

import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fiche de l'assuré : la carte d'assuré au format officiel y figure (demande du 07/10/2026). */
class FicheAssureTest {

    private static String photoDataUrl() throws Exception {
        BufferedImage img = new BufferedImage(60, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(120, 160, 200));
        g.fillRect(0, 0, 60, 80);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @Test
    void laFicheReprendLaCarteAuFormatOfficiel() throws Exception {
        DossierPatient dossier = new DossierPatient();
        dossier.setPrenom("Awa");
        dossier.setNom("Diop");
        dossier.setMatricule("1 234 567 890");
        FactureStructure facture = FactureStructure.builder().regime(Regime.CONTRIBUTIF).mois(10).annee(2026).build();
        EnteteDocument entete = EnteteDocument.builder().titre("Informations de l'assuré").build();

        byte[] pdf = new GenerateurPdf().ficheAssure(facture, dossier, photoDataUrl(), entete);
        String sortie = System.getProperty("fiche.sortie");
        if (sortie != null) Files.write(Path.of(sortie), pdf);

        PdfReader lecteur = new PdfReader(pdf);
        String texte = PdfTextExtractor.getTextFromPage(lecteur, 1);
        lecteur.close();
        assertTrue(texte.contains("CARTE DE COUVERTURE SANTE") || texte.replace(" ", "").contains("CARTEDECOUVERTURESANTE"), texte);
        assertTrue(texte.contains("DIOP"), "nom en majuscules sur la carte : " + texte);
        assertTrue(texte.contains("N° Immatriculation"), texte);
        assertTrue(texte.contains("AGENCE SENEGALAISE DE LA"), "logo de l'Agence trouvé dans les ressources : " + texte);
    }
}
