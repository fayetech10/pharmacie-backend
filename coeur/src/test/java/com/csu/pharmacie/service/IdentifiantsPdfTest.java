package com.csu.pharmacie.service;

import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IdentifiantsPdfTest {

    @Test
    void pdfReprendLEnteteEtLesLignes() throws Exception {
        List<IdentifiantsPdf.Ligne> lignes = new ArrayList<>();
        for (int i = 0; i < 34; i++) {
            lignes.add(new IdentifiantsPdf.Ligne("Poste de santé de Médina Fall " + i, "THPS" + (120 + i), "password123"));
        }
        lignes.set(2, new IdentifiantsPdf.Ligne("Poste de santé de Cité Lamy", "THPS122", "(personnalisé)"));

        byte[] pdf = IdentifiantsPdf.generer(new IdentifiantsPdf.Entete(
                "Postes de santé — District de Thies (THDT08) · Région de Thiès", "34 postes de santé",
                "Poste de santé", "chaque poste"), lignes);
        // Aperçu du rendu, sans effet sur le test.
        Files.write(Path.of("target", "apercu-identifiants.pdf"), pdf);

        PdfReader reader = new PdfReader(pdf);
        assertEquals(1, reader.getNumberOfPages(), "34 lignes tiennent sur une page A4");
        String texte = PdfTextExtractor.getTextFromPage(reader, 1);
        assertTrue(texte.contains("Identifiants de connexion"));
        assertTrue(texte.contains("District de Thies (THDT08)"));
        assertTrue(texte.contains("THPS153"));
        assertTrue(texte.contains("(personnalisé)"));
        assertTrue(texte.contains("Document confidentiel"));
    }

    @Test
    void enTeteRepeteAuDelaDUnePage() throws Exception {
        List<IdentifiantsPdf.Ligne> lignes = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            lignes.add(new IdentifiantsPdf.Ligne("Structure " + i, "DKCS" + i, "password123"));
        }
        PdfReader reader = new PdfReader(IdentifiantsPdf.generer(
                new IdentifiantsPdf.Entete("Structures sanitaires", "120 structures sanitaires", "Structure", "chaque structure"), lignes));
        assertTrue(reader.getNumberOfPages() > 1);
        assertTrue(PdfTextExtractor.getTextFromPage(reader, 2).contains("Mot de passe"));
    }
}
