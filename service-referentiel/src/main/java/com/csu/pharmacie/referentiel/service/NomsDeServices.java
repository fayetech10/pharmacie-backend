package com.csu.pharmacie.referentiel.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Noms de services d'un import de prestations, ramenés à une seule écriture.
 *
 * <p>Les listes du terrain écrivent le même service de plusieurs façons — « Dépositaire » et
 * « DEPOSITAIRE », « CPC », « Cpc » et « CPC, », « Pédiatrie », « PEDIATRIE » et la coquille
 * « PADIATRIE » (fichier type du 07/10/2026). Importés tels quels, ce seraient autant de
 * services distincts dans la liste de la facturation, chacun avec une partie des prestations.</p>
 *
 * <p>Règles, dans l'ordre : un service déjà connu de la structure (ou de la liste par défaut)
 * garde son écriture ; sinon, entre les écritures du fichier, celle qui porte des accents
 * (signe d'une saisie soignée), puis la plus fréquente, puis la première. Une coquille d'une
 * lettre sur un nom d'au moins six caractères rejoint le service le plus employé.</p>
 */
final class NomsDeServices {

    /** Services proposés par défaut — même liste que {@code SERVICES_PAR_DEFAUT} du tableau de bord. */
    static final List<String> PAR_DEFAUT = List.of("Consultation", "Soins", "Maternité", "Laboratoire",
            "Vaccination", "Dépositaire", "Analyses", "Imagerie", "Autre");

    /** Écriture retenue, par clé (minuscules, sans accents ni ponctuation). */
    private final Map<String, String> canoniques = new HashMap<>();
    /** Nombre de lignes par écriture retenue (services rares à signaler). */
    private final Map<String, Integer> lignesParService = new LinkedHashMap<>();
    private final java.util.Set<String> connus = new java.util.HashSet<>();

    /**
     * @param existants services déjà employés par la structure (ils font foi)
     * @param saisis    services lus dans le fichier, ligne par ligne (null ou vide ignorés)
     */
    NomsDeServices(Collection<String> existants, List<String> saisis) {
        List<String> references = new ArrayList<>();
        if (existants != null) references.addAll(existants);
        references.addAll(PAR_DEFAUT);
        for (String reference : references) {
            String cle = cle(reference);
            if (!cle.isEmpty() && !canoniques.containsKey(cle)) {
                canoniques.put(cle, nettoyer(reference));
                connus.add(cle);
            }
        }

        // Écritures du fichier, groupées par clé, dans l'ordre d'apparition.
        Map<String, Map<String, Integer>> variantes = new LinkedHashMap<>();
        Map<String, Integer> totalParCle = new LinkedHashMap<>();
        for (String saisi : saisis) {
            String cle = cle(saisi);
            if (cle.isEmpty()) continue;
            variantes.computeIfAbsent(cle, c -> new LinkedHashMap<>()).merge(nettoyer(saisi), 1, Integer::sum);
            totalParCle.merge(cle, 1, Integer::sum);
        }
        variantes.forEach((cle, ecritures) -> canoniques.computeIfAbsent(cle, c -> meilleure(ecritures)));

        // Coquilles d'une lettre : rattachées au service le plus employé (ou déjà connu).
        List<String> cles = new ArrayList<>(totalParCle.keySet());
        for (String cle : cles) {
            if (connus.contains(cle) || cle.length() < 6) continue;
            String cible = null;
            int meilleurPoids = totalParCle.get(cle);
            for (String autre : canoniques.keySet()) {
                if (autre.equals(cle) || Math.abs(autre.length() - cle.length()) > 1 || distance(autre, cle) != 1) continue;
                int poids = connus.contains(autre) ? Integer.MAX_VALUE : totalParCle.getOrDefault(autre, 0);
                if (poids > meilleurPoids) {
                    meilleurPoids = poids;
                    cible = autre;
                }
            }
            if (cible != null) canoniques.put(cle, canoniques.get(cible));
        }

        for (String saisi : saisis) {
            String nom = canonique(saisi);
            if (nom != null) lignesParService.merge(nom, 1, Integer::sum);
        }
    }

    /** Écriture retenue pour ce service lu dans le fichier ; null s'il est vide. */
    String canonique(String saisi) {
        String cle = cle(saisi);
        return cle.isEmpty() ? null : canoniques.getOrDefault(cle, nettoyer(saisi));
    }

    /**
     * Services du fichier très peu employés et inconnus de la structure : souvent une colonne
     * décalée (un médicament saisi dans « Service »). Signalés, pas refusés.
     */
    Map<String, Integer> rares(int seuil) {
        Map<String, Integer> rares = new LinkedHashMap<>();
        lignesParService.forEach((nom, n) -> {
            if (n <= seuil && !connus.contains(cle(nom))) rares.put(nom, n);
        });
        return rares;
    }

    private static String meilleure(Map<String, Integer> ecritures) {
        List<Map.Entry<String, Integer>> ordre = new ArrayList<>(ecritures.entrySet());
        // Tri stable : à égalité, la première écriture rencontrée reste devant.
        ordre.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> accentuee(e.getKey()) ? 0 : 1)
                .thenComparing(Map.Entry::getValue, Comparator.reverseOrder()));
        return ordre.get(0).getKey();
    }

    private static boolean accentuee(String texte) {
        return !Normalizer.normalize(texte, Normalizer.Form.NFD).equals(texte);
    }

    /** Clé de comparaison : minuscules, sans accents, lettres et chiffres seulement. */
    static String cle(String texte) {
        if (texte == null) return "";
        return Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Espaces resserrés, ponctuation retirée aux extrémités (« CPC, » → « CPC »). */
    static String nettoyer(String texte) {
        return texte == null ? "" : texte.trim().replaceAll("\\s+", " ")
                .replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N})]+$", "");
    }

    /** Distance d'édition (Levenshtein) entre deux clés courtes. */
    static int distance(String a, String b) {
        int[] precedent = new int[b.length() + 1];
        int[] courant = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) precedent[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            courant[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cout = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                courant[j] = Math.min(Math.min(courant[j - 1] + 1, precedent[j] + 1), precedent[j - 1] + cout);
            }
            int[] t = precedent;
            precedent = courant;
            courant = t;
        }
        return precedent[b.length()];
    }
}
