package com.csu.pharmacie.commun.export;

import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.Regime;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Aperçu image du dossier patient : un vrai document généré devient un JPEG lisible. */
class ApercuImageTest {

    @Test
    void laFicheAssureDevientUneImage() throws Exception {
        DossierPatient dossier = new DossierPatient();
        dossier.setPrenom("Awa");
        dossier.setNom("Diop");
        dossier.setMatricule("1 234 567 890");
        FactureStructure facture = FactureStructure.builder().regime(Regime.CONTRIBUTIF).mois(10).annee(2026).build();
        EnteteDocument entete = EnteteDocument.builder().titre("Informations de l'assuré").build();
        byte[] pdf = new GenerateurPdf().ficheAssure(facture, dossier, null, entete);

        byte[] jpeg = ApercuImage.depuisPdf(pdf);

        assertTrue(jpeg.length > 2 && (jpeg[0] & 0xFF) == 0xFF && (jpeg[1] & 0xFF) == 0xD8, "signature JPEG");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(image);
        assertTrue(image.getWidth() > 500, "largeur lisible : " + image.getWidth());
    }
}
