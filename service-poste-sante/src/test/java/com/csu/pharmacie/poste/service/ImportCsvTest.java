package com.csu.pharmacie.poste.service;

import com.csu.pharmacie.commun.export.LecteurExcel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Import des prestations du paramétrage depuis un CSV (cahier « Espace PS », §4). */
class ImportCsvTest {

    @Test
    void csvExcelFrancaisPointVirguleEtWindows1252() {
        String csv = "Désignation;Catégorie;Prix\r\n"
                + "Consultation ICP;Consultation;1 500\r\n"
                + "\"Pansement; grand\";Soins;2000\r\n"
                + ";;\r\n";
        List<LecteurExcel.LigneImport> lignes = LecteurExcel.lire(
                new ByteArrayInputStream(csv.getBytes(Charset.forName("windows-1252"))));

        assertThat(lignes).hasSize(2);
        assertThat(lignes.get(0).texte("designation")).isEqualTo("Consultation ICP");
        assertThat(lignes.get(0).texte("categorie")).isEqualTo("Consultation");
        assertThat(lignes.get(0).nombre("prix")).isEqualTo(1500);
        assertThat(lignes.get(1).texte("designation")).isEqualTo("Pansement; grand");
    }

    @Test
    void csvVirguleUtf8AvecBom() {
        String csv = "﻿Code,Designation,Prix\n"
                + "VACC,\"Vaccination \"\"BCG\"\"\",0\n";
        List<LecteurExcel.LigneImport> lignes = LecteurExcel.lire(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0).texte("code")).isEqualTo("VACC");
        assertThat(lignes.get(0).texte("designation")).isEqualTo("Vaccination \"BCG\"");
    }
}
