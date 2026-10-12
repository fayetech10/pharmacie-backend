package com.csu.pharmacie.commun.export;

import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Attestation de soins poste de santé : un seul exemplaire sur le document (demande du 07/10/2026). */
class AttestationSoinsTest {

    @Test
    void lAttestationNeContientQuUnSeulExemplaire() throws Exception {
        DossierPatient dossier = new DossierPatient();
        dossier.setPrenom("Fatou");
        dossier.setNom("Ndiaye");
        dossier.setMatricule("SN-00123");
        dossier.setMutuelle("MUTUELLE SANTE");

        FactureStructure facture = FactureStructure.builder()
                .numero("FS-202610-00042")
                .regime(Regime.CONTRIBUTIF)
                .mois(10)
                .annee(2026)
                .build();

        LigneFactureStructure ligne = LigneFactureStructure.builder()
                .designation("Consultation générale")
                .quantite(1)
                .prixUnitaire(1000)
                .montant(1000)
                .montantBeneficiaire(200)
                .montantSencsu(800)
                .datePriseEnCharge(LocalDate.of(2026, 10, 5))
                .build();

        EnteteDocument entete = EnteteDocument.builder()
                .organisme("SEN-CSU")
                .titre("Attestation de soins")
                .build();

        byte[] pdf = new GenerateurPdf().attestationSoins(facture, dossier, List.of(ligne), entete);

        PdfReader lecteur = new PdfReader(pdf);
        // Le PDF doit comporter une seule page
        assertEquals(1, lecteur.getNumberOfPages(), "L'attestation doit tenir sur une seule page");

        String texte = PdfTextExtractor.getTextFromPage(lecteur, 1);
        lecteur.close();

        // Le titre doit apparaître exactement 1 fois
        int occurrencesTitre = 0;
        int index = 0;
        String titreCherche = "ATTESTATION DE SOINS POSTE DE SANTE";
        while ((index = texte.indexOf(titreCherche, index)) != -1) {
            occurrencesTitre++;
            index += titreCherche.length();
        }
        assertEquals(1, occurrencesTitre, "Il ne doit y avoir qu'un seul exemplaire du titre sur le document");

        // Vérifier la présence des données
        assertTrue(texte.contains("Fatou"), "Prénom présent");
        assertTrue(texte.contains("Ndiaye"), "Nom présent");
        assertTrue(texte.contains("Consultation générale"), "Désignation présente");
        assertTrue(texte.contains("Document généré par la plateforme SEN-CSU"), "Pied de page présent");
    }
}
