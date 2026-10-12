package com.csu.pharmacie.commun.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import com.csu.pharmacie.commun.web.ExceptionMetier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lecture des classeurs d'import (prestations, structures, référentiel Hors CSU, §5-§6).
 *
 * <p>Les fichiers viennent du terrain : entêtes accentués ou non, majuscules variables,
 * colonnes déplacées, lignes vides intercalées. Les colonnes sont donc repérées par leur
 * <em>libellé normalisé</em> (sans accent, sans casse, sans ponctuation) et non par leur
 * position, et plusieurs libellés peuvent désigner la même donnée.</p>
 */
public final class LecteurExcel {

    private static final DataFormatter FORMATEUR = new DataFormatter(Locale.FRANCE);
    private static final DateTimeFormatter[] FORMATS_DATE = {
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy")
    };

    private LecteurExcel() {
    }

    /** Une ligne du classeur : valeurs indexées par libellé de colonne normalisé. */
    public record LigneImport(int numeroLigne, Map<String, String> valeurs) {

        /** Première valeur non vide parmi les libellés proposés (synonymes de colonne). */
        public String texte(String... libelles) {
            for (String libelle : libelles) {
                String valeur = valeurs.get(normaliser(libelle));
                if (valeur != null && !valeur.isBlank()) {
                    return valeur.trim();
                }
            }
            return null;
        }

        public double nombre(String... libelles) {
            String brut = texte(libelles);
            if (brut == null) {
                return 0;
            }
            // Les montants saisis à la main contiennent souvent espaces, « FCFA » et virgule décimale.
            String nettoye = brut.replaceAll("[^0-9,.-]", "").replace(',', '.');
            if (nettoye.isBlank() || nettoye.equals("-") || nettoye.equals(".")) {
                return 0;
            }
            try {
                return Double.parseDouble(nettoye);
            } catch (NumberFormatException e) {
                throw ExceptionMetier.invalide(
                        "Ligne " + numeroLigne + " : « " + brut + " » n'est pas un nombre valide.");
            }
        }

        public int entier(String... libelles) {
            return (int) Math.round(nombre(libelles));
        }

        public LocalDate date(String... libelles) {
            String brut = texte(libelles);
            if (brut == null) {
                return null;
            }
            for (DateTimeFormatter format : FORMATS_DATE) {
                try {
                    return LocalDate.parse(brut.trim(), format);
                } catch (Exception ignore) {
                    // format suivant
                }
            }
            throw ExceptionMetier.invalide("Ligne " + numeroLigne + " : date « " + brut
                    + " » illisible (formats acceptés : jj/mm/aaaa ou aaaa-mm-jj).");
        }

        /** Vrai pour « oui », « o », « x », « vrai », « true », « 1 ». */
        public boolean booleen(String... libelles) {
            String brut = texte(libelles);
            if (brut == null) {
                return false;
            }
            return switch (brut.trim().toLowerCase(Locale.ROOT)) {
                case "oui", "o", "x", "vrai", "true", "1", "yes", "y" -> true;
                default -> false;
            };
        }

        public boolean estVide() {
            return valeurs.values().stream().allMatch(v -> v == null || v.isBlank());
        }
    }

    /**
     * Lit un fichier d'import : classeur Excel (.xlsx, .xls — première feuille) ou fichier
     * CSV. Dans les deux cas, première ligne = entêtes, lignes suivantes = données.
     *
     * <p>Le format est reconnu au contenu et non à l'extension : un CSV renommé en .xlsx,
     * ou l'inverse, arrive souvent du terrain.</p>
     */
    public static List<LigneImport> lire(InputStream flux) {
        byte[] contenu;
        try {
            contenu = flux.readAllBytes();
        } catch (IOException e) {
            throw ExceptionMetier.invalide("Lecture du fichier impossible : " + e.getMessage());
        }
        if (contenu.length == 0) {
            throw ExceptionMetier.invalide("Le fichier est vide.");
        }
        return estClasseur(contenu) ? lireClasseur(new ByteArrayInputStream(contenu)) : lireCsv(contenu);
    }

    /** Signature ZIP (xlsx) ou OLE2 (xls). */
    private static boolean estClasseur(byte[] contenu) {
        if (contenu.length < 4) {
            return false;
        }
        boolean zip = contenu[0] == 'P' && contenu[1] == 'K';
        boolean ole2 = (contenu[0] & 0xFF) == 0xD0 && (contenu[1] & 0xFF) == 0xCF
                && (contenu[2] & 0xFF) == 0x11 && (contenu[3] & 0xFF) == 0xE0;
        return zip || ole2;
    }

    /**
     * CSV tel qu'Excel l'enregistre : séparateur « ; » en français, « , » ailleurs (détecté
     * sur la ligne d'entêtes), guillemets doublés, UTF-8 avec ou sans BOM — à défaut
     * Windows-1252, l'encodage d'un « CSV (séparateur : point-virgule) » d'Excel.
     */
    static List<LigneImport> lireCsv(byte[] contenu) {
        String texte = decoder(contenu);
        if (texte.startsWith("﻿")) {
            texte = texte.substring(1);
        }
        int finPremiereLigne = texte.indexOf('\n');
        String premiere = finPremiereLigne < 0 ? texte : texte.substring(0, finPremiereLigne);
        char separateur = separateur(premiere);

        List<List<String>> enregistrements = decouper(texte, separateur);
        if (enregistrements.isEmpty()) {
            throw ExceptionMetier.invalide("La première ligne du fichier doit contenir les entêtes de colonnes.");
        }
        List<String> entetes = enregistrements.get(0).stream().map(LecteurExcel::normaliser).toList();
        if (entetes.stream().allMatch(String::isBlank)) {
            throw ExceptionMetier.invalide("Aucun entête de colonne exploitable sur la première ligne.");
        }

        List<LigneImport> lignes = new ArrayList<>();
        for (int i = 1; i < enregistrements.size(); i++) {
            List<String> champs = enregistrements.get(i);
            Map<String, String> valeurs = new LinkedHashMap<>();
            for (int c = 0; c < entetes.size(); c++) {
                if (!entetes.get(c).isBlank()) {
                    valeurs.put(entetes.get(c), c < champs.size() ? champs.get(c).trim() : "");
                }
            }
            LigneImport ligne = new LigneImport(i + 1, valeurs);
            if (!ligne.estVide()) {
                lignes.add(ligne);
            }
        }
        return lignes;
    }

    private static String decoder(byte[] contenu) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contenu))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(contenu, Charset.forName("windows-1252"));
        }
    }

    /** Le séparateur le plus fréquent hors guillemets sur la ligne d'entêtes. */
    private static char separateur(String ligne) {
        int pointVirgule = 0;
        int virgule = 0;
        int tabulation = 0;
        boolean entreGuillemets = false;
        for (char c : ligne.toCharArray()) {
            if (c == '"') {
                entreGuillemets = !entreGuillemets;
            } else if (!entreGuillemets) {
                if (c == ';') pointVirgule++;
                else if (c == ',') virgule++;
                else if (c == '\t') tabulation++;
            }
        }
        if (tabulation > pointVirgule && tabulation > virgule) {
            return '\t';
        }
        return virgule > pointVirgule ? ',' : ';';
    }

    /** Découpe en enregistrements et champs, guillemets et retours à la ligne cités compris. */
    private static List<List<String>> decouper(String texte, char separateur) {
        List<List<String>> enregistrements = new ArrayList<>();
        List<String> courant = new ArrayList<>();
        StringBuilder champ = new StringBuilder();
        boolean entreGuillemets = false;
        for (int i = 0; i < texte.length(); i++) {
            char c = texte.charAt(i);
            if (entreGuillemets) {
                if (c == '"') {
                    if (i + 1 < texte.length() && texte.charAt(i + 1) == '"') {
                        champ.append('"');
                        i++;
                    } else {
                        entreGuillemets = false;
                    }
                } else {
                    champ.append(c);
                }
            } else if (c == '"') {
                entreGuillemets = true;
            } else if (c == separateur) {
                courant.add(champ.toString());
                champ.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texte.length() && texte.charAt(i + 1) == '\n') {
                    i++;
                }
                courant.add(champ.toString());
                champ.setLength(0);
                enregistrements.add(courant);
                courant = new ArrayList<>();
            } else {
                champ.append(c);
            }
        }
        if (champ.length() > 0 || !courant.isEmpty()) {
            courant.add(champ.toString());
            enregistrements.add(courant);
        }
        return enregistrements;
    }

    private static List<LigneImport> lireClasseur(InputStream flux) {
        try (Workbook classeur = WorkbookFactory.create(flux)) {
            if (classeur.getNumberOfSheets() == 0) {
                throw ExceptionMetier.invalide("Le fichier ne contient aucune feuille.");
            }
            Sheet feuille = classeur.getSheetAt(0);
            Row entetes = feuille.getRow(feuille.getFirstRowNum());
            if (entetes == null) {
                throw ExceptionMetier.invalide("La première ligne du fichier doit contenir les entêtes de colonnes.");
            }

            Map<Integer, String> colonnes = new LinkedHashMap<>();
            for (Cell cellule : entetes) {
                String libelle = normaliser(valeur(cellule));
                if (!libelle.isBlank()) {
                    colonnes.put(cellule.getColumnIndex(), libelle);
                }
            }
            if (colonnes.isEmpty()) {
                throw ExceptionMetier.invalide("Aucun entête de colonne exploitable sur la première ligne.");
            }

            List<LigneImport> lignes = new ArrayList<>();
            for (int i = entetes.getRowNum() + 1; i <= feuille.getLastRowNum(); i++) {
                Row row = feuille.getRow(i);
                if (row == null) {
                    continue;
                }
                Map<String, String> valeurs = new LinkedHashMap<>();
                colonnes.forEach((index, libelle) -> valeurs.put(libelle, valeur(row.getCell(index))));
                LigneImport ligne = new LigneImport(i + 1, valeurs);
                // Les classeurs du terrain contiennent presque toujours des lignes vides
                // de séparation : les remonter comme erreurs n'aurait aucun sens.
                if (!ligne.estVide()) {
                    lignes.add(ligne);
                }
            }
            return lignes;
        } catch (ExceptionMetier e) {
            throw e;
        } catch (Exception e) {
            throw ExceptionMetier.invalide("Fichier Excel illisible : " + e.getMessage());
        }
    }

    private static String valeur(Cell cellule) {
        if (cellule == null) {
            return "";
        }
        if (cellule.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cellule)) {
            return cellule.getLocalDateTimeCellValue().toLocalDate().format(FORMATS_DATE[0]);
        }
        return FORMATEUR.formatCellValue(cellule).trim();
    }

    /** « Prix Unitaire (FCFA) » → « prixunitairefcfa » : la comparaison devient insensible à la mise en forme. */
    public static String normaliser(String libelle) {
        if (libelle == null) {
            return "";
        }
        return Normalizer.normalize(libelle, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }
}
