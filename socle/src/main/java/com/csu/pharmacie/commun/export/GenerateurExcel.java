package com.csu.pharmacie.commun.export;

import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.TypeStructure;
import com.csu.pharmacie.utils.ColonnesFactureStructure;
import com.csu.pharmacie.utils.ColonnesFactureStructure.Colonne;
import com.csu.pharmacie.utils.NombreEnLettres;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Classeurs Excel produits par la plateforme.
 *
 * <p>Le format attendu par la consolidation district (§9) est précis : une feuille de
 * synthèse portant les sous-totaux et le total général, la facturation facture par facture,
 * un récapitulatif par régime, puis <em>une feuille par structure</em> portant sa facture —
 * la même que celle que la structure exporte elle-même, pour que le district puisse la
 * contrôler ligne à ligne sans ouvrir un autre document.</p>
 */
public class GenerateurExcel {

    private static final String CLIENT_LIGNE_1 = "Agence sénégalaise de la couverture sanitaire";
    private static final String CLIENT_LIGNE_2 = "universelle";

    /**
     * Consolidation d'un district.
     *
     * @param structures structures du district (service identité) : code, type et centre de
     *                   rattachement des feuilles, et liste de celles qui n'ont rien transmis
     */
    public byte[] consolidationDistrict(List<FactureStructure> factures, List<StructureDto> structures,
                                        EnteteDocument entete) {
        return consolidationDistrict(factures, structures, entete, true);
    }

    /**
     * @param feuillesDeSynthese faux : seulement les feuilles par structure (leurs factures),
     *                           sans « Synthèse », « Facturation » ni « Par régime » — la
     *                           « Situation 0-5 ans » du district (demande du 07/10/2026)
     */
    public byte[] consolidationDistrict(List<FactureStructure> factures, List<StructureDto> structures,
                                        EnteteDocument entete, boolean feuillesDeSynthese) {
        try (XSSFWorkbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Styles styles = new Styles(classeur);

            Map<String, StructureDto> annuaire = new HashMap<>();
            if (structures != null) {
                structures.stream().filter(s -> s.getId() != null).forEach(s -> annuaire.put(s.getId(), s));
            }
            List<Lot> lots = regrouper(factures, annuaire);
            Set<String> ontFacture = lots.stream().map(Lot::cle).collect(Collectors.toSet());
            List<StructureDto> nonFacturees = annuaire.values().stream()
                    .filter(StructureDto::isActif)
                    .filter(s -> !ontFacture.contains(s.getId()))
                    .sorted(ordre(StructureDto::getTypeStructure, s -> Documents.ouVide(s.getNom())))
                    .toList();

            // La synthèse montre TOUTES les structures actives du district — postes, centres,
            // EPS —, à zéro quand elles n'ont rien facturé (demande du 07/10/2026 : sans cela,
            // un centre sans facture du régime filtré manquait au tableau du district).
            List<Lot> toutes = new ArrayList<>(lots);
            nonFacturees.forEach(s -> toutes.add(new Lot(s.getId(), Documents.ouVide(s.getNom()), s.getCode(),
                    s.getTypeStructure(), s.getStructureRattachementNom(), new ArrayList<>())));
            toutes.sort(ordre(Lot::type, Lot::nom));
            if (feuillesDeSynthese) {
                feuilleSynthese(classeur, styles, entete, toutes, nonFacturees);
                feuilleFacturation(classeur, styles, entete, lots);
                feuilleRegimes(classeur, styles, factures);
            }
            // Les feuilles par structure viennent après la synthèse : c'est l'ordre de lecture
            // attendu par les services régionaux qui reçoivent le classeur.
            Set<String> nomsUtilises = new HashSet<>(Set.of("synthèse", "facturation", "par régime"));
            for (Lot lot : lots) {
                feuilleStructure(classeur, styles, entete, lot, nomsUtilises);
            }
            if (classeur.getNumberOfSheets() == 0) {
                // Un classeur sans feuille ne s'ouvre pas : on dit qu'il n'y a rien.
                Sheet vide = classeur.createSheet("Aucune facture");
                titre(vide, styles, 0, "Aucune facture sur la période — " + Documents.ouVide(entete.getPeriode()), 6);
            }

            classeur.write(sortie);
            return sortie.toByteArray();
        } catch (Exception e) {
            throw ExceptionMetier.invalide("Génération du classeur impossible : " + e.getMessage());
        }
    }

    // ══════════════════════════════ regroupement ══════════════════════════════

    /** Factures d'une structure, avec ce que l'on sait d'elle. */
    private record Lot(String cle, String nom, String code, TypeStructure type, String rattachement,
                       List<FactureStructure> factures) {

        double montant() {
            return factures.stream().mapToDouble(FactureStructure::getMontantTotal).sum();
        }

        double partBeneficiaire() {
            return factures.stream().mapToDouble(FactureStructure::getMontantTotalBeneficiaire).sum();
        }

        double partSencsu() {
            return factures.stream().mapToDouble(FactureStructure::getMontantTotalSencsu).sum();
        }

        double partSencsuTransmise() {
            return factures.stream().filter(GenerateurExcel::transmise)
                    .mapToDouble(FactureStructure::getMontantTotalSencsu).sum();
        }

        int beneficiaires() {
            return factures.stream().mapToInt(FactureStructure::getNombreBeneficiaires).sum();
        }

        int prestations() {
            return factures.stream().mapToInt(GenerateurExcel::nombrePrestations).sum();
        }
    }

    /**
     * Une entrée par structure émettrice — par identifiant et non par nom, deux postes
     * pouvant porter le même. Le nom, le code et le type viennent de l'annuaire quand la
     * structure y figure : une facture créée par le cœur ne porte pas son type.
     */
    private static List<Lot> regrouper(List<FactureStructure> factures, Map<String, StructureDto> annuaire) {
        Map<String, Lot> lots = new LinkedHashMap<>();
        for (FactureStructure facture : factures) {
            String cle = facture.getStructureSanitaireId() != null ? facture.getStructureSanitaireId()
                    : "nom:" + Documents.ouVide(facture.getStructureNom());
            Lot lot = lots.computeIfAbsent(cle, c -> {
                StructureDto structure = annuaire.get(c);
                String nom = structure != null && structure.getNom() != null && !structure.getNom().isBlank()
                        ? structure.getNom() : Documents.ouVide(facture.getStructureNom());
                TypeStructure type = facture.getTypeStructure() != null ? facture.getTypeStructure()
                        : structure == null ? null : structure.getTypeStructure();
                return new Lot(c, nom.isBlank() ? "Structure" : nom,
                        structure == null ? null : structure.getCode(), type,
                        structure == null ? null : structure.getStructureRattachementNom(), new ArrayList<>());
            });
            lot.factures().add(facture);
        }
        lots.values().forEach(lot -> lot.factures().sort(Comparator.comparingInt(FactureStructure::getAnnee)
                .thenComparingInt(FactureStructure::getMois)
                .thenComparing(f -> f.getRegime() == null ? "" : f.getRegime().getLibelle())));
        return lots.values().stream().sorted(ordre(Lot::type, Lot::nom)).toList();
    }

    /** Postes de santé d'abord, puis centres et EPS ; ordre alphabétique dans chaque groupe. */
    private static <T> Comparator<T> ordre(Function<T, TypeStructure> type, Function<T, String> nom) {
        Function<T, Integer> rang = t -> type.apply(t) == null ? TypeStructure.values().length : type.apply(t).ordinal();
        return Comparator.comparing(rang).thenComparing(nom, String.CASE_INSENSITIVE_ORDER);
    }

    // ══════════════════════════════ feuilles de synthèse ══════════════════════════════

    private void feuilleSynthese(XSSFWorkbook classeur, Styles styles, EnteteDocument entete,
                                 List<Lot> lots, List<StructureDto> nonFacturees) {
        Sheet feuille = classeur.createSheet("Synthèse");
        int ligne = 0;

        ligne = titre(feuille, styles, ligne, Documents.ouVide(entete.getTitre()), 12);
        ligne = info(feuille, styles, ligne, "District", entete.getDistrictNom());
        ligne = info(feuille, styles, ligne, "Région", entete.getRegionNom());
        ligne = info(feuille, styles, ligne, "Période", entete.getPeriode());
        ligne = info(feuille, styles, ligne, "Régime", entete.getRegimeLibelle());
        fusion(feuille, ligne++, 0, 11, "Toutes les factures de la période sont comptées. « En cours » = brouillon "
                + "ou facture rejetée, encore à transmettre ou à corriger par la structure.", styles.info);
        ligne++;

        enTete(feuille, styles, ligne++, "N°", "Structure", "Type", "Centre de rattachement", "Factures",
                "Bénéficiaires", "Prestations", "Montant total", "Part bénéficiaire", "Part SEN-CSU",
                "dont transmis", "dont en cours");

        int index = 1;
        double total = 0;
        double totalBeneficiaire = 0;
        double totalSencsu = 0;
        double totalTransmis = 0;
        int totalFactures = 0;
        int totalBeneficiaires = 0;
        int totalPrestations = 0;

        for (Lot lot : lots) {
            Row row = feuille.createRow(ligne++);
            cellule(row, 0, index++, styles.centre);
            cellule(row, 1, lot.nom(), styles.texte);
            cellule(row, 2, libelle(lot.type()), styles.texte);
            cellule(row, 3, Documents.ouVide(lot.rattachement()), styles.texte);
            cellule(row, 4, lot.factures().size(), styles.centre);
            cellule(row, 5, lot.beneficiaires(), styles.centre);
            cellule(row, 6, lot.prestations(), styles.centre);
            cellule(row, 7, lot.montant(), styles.montant);
            cellule(row, 8, lot.partBeneficiaire(), styles.montant);
            cellule(row, 9, lot.partSencsu(), styles.montant);
            cellule(row, 10, lot.partSencsuTransmise(), styles.montant);
            cellule(row, 11, lot.partSencsu() - lot.partSencsuTransmise(), styles.montant);

            total += lot.montant();
            totalBeneficiaire += lot.partBeneficiaire();
            totalSencsu += lot.partSencsu();
            totalTransmis += lot.partSencsuTransmise();
            totalFactures += lot.factures().size();
            totalBeneficiaires += lot.beneficiaires();
            totalPrestations += lot.prestations();
        }

        Row totaux = feuille.createRow(ligne++);
        cellule(totaux, 0, "", styles.total);
        cellule(totaux, 1, "TOTAL GÉNÉRAL DU DISTRICT", styles.total);
        cellule(totaux, 2, "", styles.total);
        cellule(totaux, 3, "", styles.total);
        cellule(totaux, 4, totalFactures, styles.totalNombre);
        cellule(totaux, 5, totalBeneficiaires, styles.totalNombre);
        cellule(totaux, 6, totalPrestations, styles.totalNombre);
        cellule(totaux, 7, total, styles.totalMontant);
        cellule(totaux, 8, totalBeneficiaire, styles.totalMontant);
        cellule(totaux, 9, totalSencsu, styles.totalMontant);
        cellule(totaux, 10, totalTransmis, styles.totalMontant);
        cellule(totaux, 11, totalSencsu - totalTransmis, styles.totalMontant);

        // Ce que le district doit relancer en fin de mois : les structures sans facture.
        if (!nonFacturees.isEmpty()) {
            ligne += 2;
            ligne = titre(feuille, styles, ligne, "Structures n'ayant pas facturé sur la période — à relancer", 12);
            enTete(feuille, styles, ligne++, "N°", "Structure", "Type", "Centre de rattachement");
            int numero = 1;
            for (StructureDto structure : nonFacturees) {
                Row row = feuille.createRow(ligne++);
                cellule(row, 0, numero++, styles.centre);
                cellule(row, 1, Documents.ouVide(structure.getNom()), styles.texte);
                cellule(row, 2, libelle(structure.getTypeStructure()), styles.texte);
                cellule(row, 3, Documents.ouVide(structure.getStructureRattachementNom()), styles.texte);
            }
        }

        ajusterColonnes(feuille, 12);
    }

    /** Facturation des postes et centres, une ligne par facture, avec son statut. */
    private void feuilleFacturation(XSSFWorkbook classeur, Styles styles, EnteteDocument entete, List<Lot> lots) {
        Sheet feuille = classeur.createSheet("Facturation");
        int ligne = titre(feuille, styles, 0, "Facturation des postes et centres — "
                + Documents.ouVide(entete.getPeriode()), 12);
        ligne++;

        enTete(feuille, styles, ligne++, "N°", "Structure", "Type", "N° facture", "Régime", "Période", "Statut",
                "Patients", "Prestations", "Montant total", "Part bénéficiaire", "Part SEN-CSU");

        int index = 1;
        int totalPatients = 0;
        int totalPrestations = 0;
        double total = 0;
        double totalBeneficiaire = 0;
        double totalSencsu = 0;

        for (Lot lot : lots) {
            for (FactureStructure facture : lot.factures()) {
                Row row = feuille.createRow(ligne++);
                cellule(row, 0, index++, styles.centre);
                cellule(row, 1, lot.nom(), styles.texte);
                cellule(row, 2, libelle(lot.type()), styles.texte);
                cellule(row, 3, Documents.ouVide(facture.getNumero()), styles.texte);
                cellule(row, 4, facture.getRegime() == null ? "" : facture.getRegime().getLibelle(), styles.texte);
                cellule(row, 5, facture.periodeLibelle(), styles.centre);
                cellule(row, 6, facture.getStatut() == null ? "" : facture.getStatut().getLibelle(), styles.texte);
                cellule(row, 7, facture.getNombreBeneficiaires(), styles.centre);
                cellule(row, 8, nombrePrestations(facture), styles.centre);
                cellule(row, 9, facture.getMontantTotal(), styles.montant);
                cellule(row, 10, facture.getMontantTotalBeneficiaire(), styles.montant);
                cellule(row, 11, facture.getMontantTotalSencsu(), styles.montant);

                totalPatients += facture.getNombreBeneficiaires();
                totalPrestations += nombrePrestations(facture);
                total += facture.getMontantTotal();
                totalBeneficiaire += facture.getMontantTotalBeneficiaire();
                totalSencsu += facture.getMontantTotalSencsu();
            }
        }

        Row totaux = feuille.createRow(ligne);
        for (int c = 0; c < 7; c++) {
            cellule(totaux, c, c == 1 ? "TOTAL" : "", styles.total);
        }
        cellule(totaux, 7, totalPatients, styles.totalNombre);
        cellule(totaux, 8, totalPrestations, styles.totalNombre);
        cellule(totaux, 9, total, styles.totalMontant);
        cellule(totaux, 10, totalBeneficiaire, styles.totalMontant);
        cellule(totaux, 11, totalSencsu, styles.totalMontant);

        ajusterColonnes(feuille, 12);
    }

    private void feuilleRegimes(XSSFWorkbook classeur, Styles styles, List<FactureStructure> factures) {
        Sheet feuille = classeur.createSheet("Par régime");
        int ligne = 0;
        ligne = titre(feuille, styles, ligne, "Récapitulatif par régime", 6);
        ligne++;
        enTete(feuille, styles, ligne++, "Régime", "Factures", "Bénéficiaires",
                "Montant total", "Part bénéficiaire", "Part SEN-CSU");

        Map<Regime, List<FactureStructure>> parRegime = new LinkedHashMap<>();
        for (Regime regime : Regime.regimesActifs()) {
            parRegime.put(regime, new ArrayList<>());
        }
        for (FactureStructure facture : factures) {
            if (facture.getRegime() != null) {
                parRegime.computeIfAbsent(facture.getRegime(), r -> new ArrayList<>()).add(facture);
            }
        }

        double total = 0;
        double totalBeneficiaire = 0;
        double totalSencsu = 0;
        for (Map.Entry<Regime, List<FactureStructure>> entree : parRegime.entrySet()) {
            List<FactureStructure> lot = entree.getValue();
            double montant = lot.stream().mapToDouble(FactureStructure::getMontantTotal).sum();
            double part = lot.stream().mapToDouble(FactureStructure::getMontantTotalBeneficiaire).sum();
            double sencsu = lot.stream().mapToDouble(FactureStructure::getMontantTotalSencsu).sum();

            Row row = feuille.createRow(ligne++);
            cellule(row, 0, entree.getKey().getLibelle(), styles.texte);
            cellule(row, 1, lot.size(), styles.centre);
            cellule(row, 2, lot.stream().mapToInt(FactureStructure::getNombreBeneficiaires).sum(), styles.centre);
            cellule(row, 3, montant, styles.montant);
            cellule(row, 4, part, styles.montant);
            cellule(row, 5, sencsu, styles.montant);

            total += montant;
            totalBeneficiaire += part;
            totalSencsu += sencsu;
        }

        Row totaux = feuille.createRow(ligne);
        cellule(totaux, 0, "TOTAL", styles.total);
        cellule(totaux, 1, "", styles.total);
        cellule(totaux, 2, "", styles.total);
        cellule(totaux, 3, total, styles.totalMontant);
        cellule(totaux, 4, totalBeneficiaire, styles.totalMontant);
        cellule(totaux, 5, totalSencsu, styles.totalMontant);

        ajusterColonnes(feuille, 6);
    }

    // ══════════════════════════════ feuille d'une structure ══════════════════════════════

    /**
     * Une feuille par structure, portant sa facture. Une structure facture un régime par
     * facture : si elle en a plusieurs sur la période, elles se suivent sur la même feuille.
     */
    private void feuilleStructure(XSSFWorkbook classeur, Styles styles, EnteteDocument entete, Lot lot,
                                  Set<String> nomsUtilises) {
        Sheet feuille = classeur.createSheet(nomFeuilleUnique(nomCourt(lot), nomsUtilises));
        int ligne = 0;
        int largeur = 1;
        for (FactureStructure facture : lot.factures()) {
            if (ligne > 0) {
                ligne += 3; // aération entre deux factures
            }
            ligne = blocFacture(feuille, styles, entete, lot, facture, ligne);
            largeur = Math.max(largeur, ColonnesFactureStructure.pourRegime(facture.getRegime()).size());
        }

        if (lot.factures().size() > 1) {
            ligne += 2;
            fusion(feuille, ligne, 0, largeur - 1, "TOTAL DES " + lot.factures().size() + " FACTURES DE LA STRUCTURE — "
                    + "MONTANT À RÉGLER PAR LA SEN-CSU : " + Documents.montantAvecDevise(lot.partSencsu()), styles.encadre);
        }

        for (int i = 0; i < largeur; i++) {
            feuille.autoSizeColumn(i);
            int largeurColonne = feuille.getColumnWidth(i);
            if (largeurColonne > 12000) feuille.setColumnWidth(i, 12000);
            if (largeurColonne < 2800) feuille.setColumnWidth(i, 2800);
        }
    }

    /**
     * Facture d'une structure, au format de la facture qu'elle exporte elle-même (cœur,
     * {@code FactureStructureExportService}) : mêmes colonnes par régime, même encadré
     * « montant à régler », même phrase d'arrêt en lettres.
     *
     * @return la première ligne libre après la facture
     */
    private int blocFacture(Sheet feuille, Styles styles, EnteteDocument entete, Lot lot,
                            FactureStructure facture, int r) {
        Regime regime = facture.getRegime();
        List<Colonne> colonnes = ColonnesFactureStructure.pourRegime(regime);
        List<LigneFactureStructure> lignes = facture.getLignes() == null ? List.of() : facture.getLignes();
        int nbCol = colonnes.size();
        int dernierCol = Math.max(1, nbCol - 1);
        int colClient = Math.max(7, dernierCol);

        // ── 1. En-tête de la structure ──
        fusion(feuille, r++, 0, dernierCol, lot.nom().toUpperCase(), styles.nomStructure);
        fusion(feuille, r++, 0, dernierCol, joindre(libelle(lot.type()),
                lot.rattachement() == null ? null : "Rattaché à " + lot.rattachement(),
                entete.getDistrictNom() == null ? null : "District " + entete.getDistrictNom(),
                entete.getRegionNom() == null ? null : "Région " + entete.getRegionNom()), styles.info);
        r++;

        // ── 2. Titre ──
        fusion(feuille, r++, 0, 2, "Facture", styles.titreFacture);
        r++;

        // ── 3. Métadonnées, client à droite ──
        meta(feuille, styles, r, "PÉRIODE :", facture.periodeLibelle());
        aDroite(feuille, r++, colClient, "CLIENT", styles.clientLibelle);
        meta(feuille, styles, r, "N° FACTURE :", Documents.ouVide(facture.getNumero()));
        aDroite(feuille, r++, colClient, CLIENT_LIGNE_1, styles.clientValeur);
        meta(feuille, styles, r, "CODE :", Documents.ouVide(lot.code()));
        aDroite(feuille, r++, colClient, CLIENT_LIGNE_2, styles.clientValeur);
        meta(feuille, styles, r++, "STATUT :", facture.getStatut() == null ? ""
                : facture.getStatut().getLibelle() + (transmise(facture) ? "" : " — non transmise"));
        meta(feuille, styles, r++, "PATIENTS :", String.valueOf(facture.getNombreBeneficiaires()));
        meta(feuille, styles, r++, "PRESTATIONS :", String.valueOf(lignes.size()));
        meta(feuille, styles, r++, "MONNAIE :", "FRANCS CFA");
        r++;

        // ── 4. Titre du régime ──
        fusion(feuille, r++, 0, dernierCol, ColonnesFactureStructure.titreLong(regime), styles.sousTitre);
        r++;

        // ── 5. Tableau nominatif ──
        Row ligneEntete = ligne(feuille, r++);
        ligneEntete.setHeightInPoints(32);
        for (int i = 0; i < nbCol; i++) {
            cellule(ligneEntete, i, colonnes.get(i).entete(), styles.enteteFacture);
        }

        double[] totaux = new double[nbCol];
        for (List<LigneFactureStructure> visite : ColonnesFactureStructure.groupesPourRegime(regime, lignes)) {
            // Une ligne par prestation (cellules séparées) ; l'identité du patient est fusionnée
            // verticalement sur tout le bloc de la visite, sans la dupliquer.
            ColonnesFactureStructure.CelluleFusion[][] grille =
                    ColonnesFactureStructure.grilleVisite(visite, colonnes);
            int premiereLigne = r;
            for (int rr = 0; rr < grille.length; rr++) {
                Row row = ligne(feuille, r++);
                for (int i = 0; i < nbCol; i++) {
                    ColonnesFactureStructure.CelluleFusion cf = grille[rr][i];
                    if (cf == null) {
                        cellule(row, i, "", styles.texteFacture); // couverte par la fusion verticale.
                        continue;
                    }
                    Object valeur = cf.valeur();
                    if (valeur instanceof Number nombre) {
                        cellule(row, i, nombre.doubleValue(), styles.nombreFacture);
                    } else {
                        cellule(row, i, valeur == null ? "" : valeur.toString(), styles.texteFacture);
                    }
                    if (cf.rowspan() > 1) {
                        feuille.addMergedRegion(new CellRangeAddress(
                                premiereLigne + rr, premiereLigne + rr + cf.rowspan() - 1, i, i));
                    }
                }
            }
            for (int i = 0; i < nbCol; i++) {
                if (colonnes.get(i).total()) {
                    totaux[i] += ColonnesFactureStructure.sommeVisite(colonnes.get(i), visite);
                }
            }
        }
        if (colonnes.stream().anyMatch(Colonne::total)) {
            Row row = ligne(feuille, r++);
            for (int i = 0; i < nbCol; i++) {
                if (colonnes.get(i).total()) {
                    cellule(row, i, totaux[i], styles.totalFacture);
                } else {
                    cellule(row, i, i == 0 ? "TOTAUX" : "", styles.enteteFacture);
                }
            }
        }

        // ── 6. Récapitulatif et montant à régler ──
        r++;
        int colEncadre = Math.max(nbCol - 3, 0);
        recapitulatif(feuille, styles, r++, colEncadre, dernierCol, "Montant total", facture.getMontantTotal());
        recapitulatif(feuille, styles, r++, colEncadre, dernierCol, "Part bénéficiaire",
                facture.getMontantTotalBeneficiaire());
        fusion(feuille, r++, colEncadre, dernierCol, "MONTANT À RÉGLER PAR LA SEN-CSU", styles.encadre);
        fusion(feuille, r++, colEncadre, dernierCol, Documents.montantAvecDevise(facture.getMontantTotalSencsu()),
                styles.encadreValeur);

        // ── 7. Phrase d'arrêt ──
        r++;
        fusion(feuille, r++, 0, dernierCol, "Arrêtée la présente facture, pour la part SEN-CSU, à la somme de "
                + enLettres(facture.getMontantTotalSencsu()) + " francs CFA.", styles.info);

        // ── 8. Signature ──
        r++;
        aDroite(feuille, r++, colClient, "--------------------------------------------------", styles.info);
        aDroite(feuille, r++, colClient, "Le Responsable (cachet et signature)", styles.signature);
        return r;
    }

    private void meta(Sheet feuille, Styles styles, int r, String libelle, String valeur) {
        Row row = ligne(feuille, r);
        cellule(row, 0, libelle, styles.metaLibelle);
        cellule(row, 1, valeur, styles.metaValeur);
    }

    /**
     * Texte calé à droite sur quelques colonnes fusionnées : fusionnées, elles n'entrent pas
     * dans l'ajustement automatique des largeurs, qui sinon élargirait la colonne des montants
     * à la taille du libellé du client.
     */
    private void aDroite(Sheet feuille, int r, int colonne, String texte, CellStyle style) {
        fusion(feuille, r, Math.max(2, colonne - 4), colonne, texte, style);
    }

    private void recapitulatif(Sheet feuille, Styles styles, int r, int premiere, int derniere,
                               String libelle, double montant) {
        if (derniere > premiere) {
            fusion(feuille, r, premiere, derniere - 1, libelle, styles.recapLibelle);
        } else {
            cellule(ligne(feuille, r), premiere, libelle, styles.recapLibelle);
        }
        cellule(ligne(feuille, r), derniere, montant, styles.nombreFacture);
    }

    // ══════════════════════════════ utilitaires ══════════════════════════════

    /** Transmise = soumise au centre, envoyée, validée ou payée ; sinon brouillon ou rejetée. */
    private static boolean transmise(FactureStructure facture) {
        return facture.getStatut() != null && facture.getStatut().estComptabilise();
    }

    private static int nombrePrestations(FactureStructure facture) {
        return facture.getLignes() == null ? 0 : facture.getLignes().size();
    }

    private static String libelle(TypeStructure type) {
        return type == null ? "" : type.getLibelle();
    }

    private static String joindre(String... morceaux) {
        return Arrays.stream(morceaux)
                .filter(m -> m != null && !m.isBlank())
                .collect(Collectors.joining("  ·  "));
    }

    private static String enLettres(double montant) {
        String texte = NombreEnLettres.convertir(Math.round(montant));
        return texte.isEmpty() ? texte : Character.toUpperCase(texte.charAt(0)) + texte.substring(1);
    }

    /** « Poste de santé de » / « Centre de santé de » en tête de nom, quelle que soit la casse. */
    private static final Pattern PREFIXE_TYPE = Pattern.compile(
            "^(poste|centre)\\s+de\\s+sant[ée]\\s*(de\\s+|du\\s+|d['’]\\s*)?", Pattern.CASE_INSENSITIVE);

    /**
     * Nom d'onglet d'une structure. Excel coupe à 31 caractères : « Poste de santé de Keur
     * Massar Nord » et « … Sud » y deviendraient le même onglet. Le préfixe commun cède donc
     * la place au sigle du type — « PS Keur Massar Nord » —, qui distingue aussi un poste
     * du centre de la même localité.
     */
    private static String nomCourt(Lot lot) {
        String reste = PREFIXE_TYPE.matcher(lot.nom().trim()).replaceFirst("").trim();
        if (reste.isEmpty()) {
            return lot.nom();
        }
        if (lot.type() == null || reste.toUpperCase().startsWith(lot.type().name() + " ")) {
            return reste;
        }
        return lot.type().name() + " " + reste;
    }

    /**
     * Excel refuse les noms de feuille de plus de 31 caractères, certains caractères
     * spéciaux et les doublons — trois façons différentes de produire un classeur illisible.
     */
    private static String nomFeuilleUnique(String souhaite, Set<String> dejaUtilises) {
        String base = WorkbookUtil.createSafeSheetName(
                souhaite == null || souhaite.isBlank() ? "Structure" : souhaite).trim();
        String candidat = base;
        int suffixe = 2;
        while (!dejaUtilises.add(candidat.toLowerCase())) {
            String numero = " (" + suffixe++ + ")";
            candidat = base.substring(0, Math.min(base.length(), 31 - numero.length())) + numero;
        }
        return candidat;
    }

    private static Row ligne(Sheet feuille, int index) {
        Row row = feuille.getRow(index);
        return row != null ? row : feuille.createRow(index);
    }

    /** Titre fusionné sur la largeur du tableau : il n'élargit plus la première colonne. */
    private int titre(Sheet feuille, Styles styles, int ligne, String texte, int largeur) {
        Row row = ligne(feuille, ligne);
        cellule(row, 0, texte, styles.titre);
        if (largeur > 1) {
            feuille.addMergedRegion(new CellRangeAddress(ligne, ligne, 0, largeur - 1));
        }
        return ligne + 1;
    }

    /** Texte sur des colonnes fusionnées ; le style est posé sur chaque cellule pour que les bordures suivent. */
    private void fusion(Sheet feuille, int r, int premiere, int derniere, String texte, CellStyle style) {
        Row row = ligne(feuille, r);
        for (int c = premiere; c <= Math.max(premiere, derniere); c++) {
            cellule(row, c, c == premiere ? texte : "", style);
        }
        if (derniere > premiere) {
            feuille.addMergedRegion(new CellRangeAddress(r, r, premiere, derniere));
        }
    }

    private int info(Sheet feuille, Styles styles, int ligne, String libelle, String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return ligne;
        }
        Row row = feuille.createRow(ligne);
        cellule(row, 0, libelle, styles.gras);
        cellule(row, 1, valeur, styles.texte);
        return ligne + 1;
    }

    private void enTete(Sheet feuille, Styles styles, int ligne, String... libelles) {
        Row row = feuille.createRow(ligne);
        for (int i = 0; i < libelles.length; i++) {
            cellule(row, i, libelles[i], styles.entete);
        }
    }

    private void cellule(Row row, int colonne, String valeur, CellStyle style) {
        Cell cellule = row.createCell(colonne);
        cellule.setCellValue(valeur == null ? "" : valeur);
        cellule.setCellStyle(style);
    }

    private void cellule(Row row, int colonne, double valeur, CellStyle style) {
        Cell cellule = row.createCell(colonne);
        cellule.setCellValue(valeur);
        cellule.setCellStyle(style);
    }

    private void ajusterColonnes(Sheet feuille, int nombre) {
        for (int i = 0; i < nombre; i++) {
            feuille.autoSizeColumn(i);
            // autoSizeColumn colle au contenu : on desserre un peu pour rester lisible.
            int largeur = Math.min(feuille.getColumnWidth(i) + 700, 12000);
            feuille.setColumnWidth(i, largeur);
        }
    }

    /** Jeu de styles créé une seule fois par classeur (Excel plafonne le nombre de styles). */
    static final class Styles {
        final CellStyle titre;
        final CellStyle gras;
        final CellStyle texte;
        final CellStyle centre;
        final CellStyle montant;
        final CellStyle entete;
        final CellStyle total;
        final CellStyle totalNombre;
        final CellStyle totalMontant;

        // Feuille d'une structure : thème vert SEN-CSU de la facture exportée par le cœur.
        final CellStyle nomStructure;
        final CellStyle info;
        final CellStyle titreFacture;
        final CellStyle metaLibelle;
        final CellStyle metaValeur;
        final CellStyle clientLibelle;
        final CellStyle clientValeur;
        final CellStyle sousTitre;
        final CellStyle enteteFacture;
        final CellStyle totalFacture;
        final CellStyle texteFacture;
        final CellStyle nombreFacture;
        final CellStyle recapLibelle;
        final CellStyle encadre;
        final CellStyle encadreValeur;
        final CellStyle signature;

        Styles(XSSFWorkbook classeur) {
            var policeTitre = classeur.createFont();
            policeTitre.setBold(true);
            policeTitre.setFontHeightInPoints((short) 13);

            var policeGras = classeur.createFont();
            policeGras.setBold(true);

            var policeEntete = classeur.createFont();
            policeEntete.setBold(true);
            policeEntete.setColor(IndexedColors.WHITE.getIndex());

            short formatMontant = classeur.createDataFormat().getFormat("#,##0");

            titre = classeur.createCellStyle();
            titre.setFont(policeTitre);

            gras = classeur.createCellStyle();
            gras.setFont(policeGras);

            texte = classeur.createCellStyle();
            bordures(texte);

            centre = classeur.createCellStyle();
            centre.setAlignment(HorizontalAlignment.CENTER);
            bordures(centre);

            montant = classeur.createCellStyle();
            montant.setAlignment(HorizontalAlignment.RIGHT);
            montant.setDataFormat(formatMontant);
            bordures(montant);

            entete = classeur.createCellStyle();
            entete.setFont(policeEntete);
            entete.setFillForegroundColor(IndexedColors.SEA_GREEN.getIndex());
            entete.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            entete.setAlignment(HorizontalAlignment.CENTER);
            bordures(entete);

            total = classeur.createCellStyle();
            total.setFont(policeGras);
            total.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            total.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            total.setAlignment(HorizontalAlignment.RIGHT);
            bordures(total);

            totalNombre = classeur.createCellStyle();
            totalNombre.cloneStyleFrom(total);
            totalNombre.setAlignment(HorizontalAlignment.CENTER);

            totalMontant = classeur.createCellStyle();
            totalMontant.cloneStyleFrom(total);
            totalMontant.setDataFormat(formatMontant);

            // ── Facture ──
            XSSFColor vert = couleur(47, 110, 84);          // #2F6E54
            XSSFColor vertClair = couleur(230, 244, 234);   // #E6F4EA
            XSSFColor gris = couleur(100, 100, 100);
            XSSFColor sombre = couleur(51, 51, 51);
            XSSFColor blanc = couleur(255, 255, 255);

            nomStructure = style(classeur, police(classeur, 16, true, vert));
            info = style(classeur, police(classeur, 9, false, gris));
            titreFacture = style(classeur, police(classeur, 20, true, sombre));
            metaLibelle = style(classeur, police(classeur, 9, true, gris));
            metaValeur = style(classeur, police(classeur, 9, true, vert));

            XSSFCellStyle client = style(classeur, police(classeur, 8, false, gris));
            client.setAlignment(HorizontalAlignment.RIGHT);
            clientLibelle = client;

            XSSFCellStyle clientNom = style(classeur, police(classeur, 10, true, vert));
            clientNom.setAlignment(HorizontalAlignment.RIGHT);
            clientValeur = clientNom;

            XSSFCellStyle regime = style(classeur, police(classeur, 12, true, sombre));
            regime.setAlignment(HorizontalAlignment.CENTER);
            regime.setVerticalAlignment(VerticalAlignment.CENTER);
            sousTitre = regime;

            XSSFCellStyle enteteVert = style(classeur, police(classeur, 9, true, blanc));
            enteteVert.setFillForegroundColor(vert);
            enteteVert.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            enteteVert.setAlignment(HorizontalAlignment.CENTER);
            enteteVert.setVerticalAlignment(VerticalAlignment.CENTER);
            enteteVert.setWrapText(true);
            bordures(enteteVert);
            enteteFacture = enteteVert;

            XSSFCellStyle totalVert = classeur.createCellStyle();
            totalVert.cloneStyleFrom(enteteVert);
            totalVert.setAlignment(HorizontalAlignment.RIGHT);
            totalVert.setDataFormat(formatMontant);
            totalFacture = totalVert;

            XSSFFont corps = police(classeur, 8, false, sombre);
            // Centré verticalement : l'identité fusionnée d'un patient s'affiche au milieu de son bloc.
            XSSFCellStyle texteCorps = style(classeur, corps);
            texteCorps.setAlignment(HorizontalAlignment.LEFT);
            texteCorps.setVerticalAlignment(VerticalAlignment.CENTER);
            texteCorps.setWrapText(true);
            bordures(texteCorps);
            texteFacture = texteCorps;

            XSSFCellStyle nombreCorps = style(classeur, corps);
            nombreCorps.setAlignment(HorizontalAlignment.RIGHT);
            nombreCorps.setVerticalAlignment(VerticalAlignment.CENTER);
            nombreCorps.setDataFormat(formatMontant);
            bordures(nombreCorps);
            nombreFacture = nombreCorps;

            XSSFCellStyle recap = style(classeur, police(classeur, 9, true, sombre));
            recap.setAlignment(HorizontalAlignment.RIGHT);
            bordures(recap);
            recapLibelle = recap;

            XSSFCellStyle cadre = style(classeur, police(classeur, 9, true, sombre));
            cadre.setFillForegroundColor(vertClair);
            cadre.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            cadre.setAlignment(HorizontalAlignment.CENTER);
            cadre.setBorderTop(BorderStyle.MEDIUM);
            cadre.setBorderBottom(BorderStyle.MEDIUM);
            cadre.setBorderLeft(BorderStyle.MEDIUM);
            cadre.setBorderRight(BorderStyle.MEDIUM);
            cadre.setTopBorderColor(vert);
            cadre.setBottomBorderColor(vert);
            cadre.setLeftBorderColor(vert);
            cadre.setRightBorderColor(vert);
            encadre = cadre;

            XSSFCellStyle cadreValeur = classeur.createCellStyle();
            cadreValeur.cloneStyleFrom(cadre);
            cadreValeur.setFont(police(classeur, 14, true, vert));
            encadreValeur = cadreValeur;

            XSSFFont policeSignature = police(classeur, 9, false, sombre);
            policeSignature.setItalic(true);
            XSSFCellStyle styleSignature = style(classeur, policeSignature);
            styleSignature.setAlignment(HorizontalAlignment.RIGHT);
            signature = styleSignature;
        }

        private static XSSFColor couleur(int rouge, int vert, int bleu) {
            return new XSSFColor(new byte[]{(byte) rouge, (byte) vert, (byte) bleu}, null);
        }

        private static XSSFFont police(XSSFWorkbook classeur, int taille, boolean grasse, XSSFColor couleur) {
            XSSFFont police = classeur.createFont();
            police.setFontHeightInPoints((short) taille);
            police.setBold(grasse);
            police.setColor(couleur);
            return police;
        }

        private static XSSFCellStyle style(XSSFWorkbook classeur, XSSFFont police) {
            XSSFCellStyle style = classeur.createCellStyle();
            style.setFont(police);
            return style;
        }

        private static void bordures(CellStyle style) {
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
    }
}
