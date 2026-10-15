package com.csu.pharmacie.utils;

import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Function;

/**
 * Colonnes de l'« état récapitulatif nominatif » d'une facture de structure, par régime
 * (un modèle par onglet du classeur « GESTION DES FACTURES CS_EPS »).
 *
 * <p>Source unique du format : la facture exportée par le {@code coeur} (Excel, PDF, Word)
 * et les feuilles « facture » de la consolidation district du {@code socle} en dépendent.
 * Une colonne ajoutée ici apparaît partout ; deux copies auraient fini par diverger.</p>
 */
public final class ColonnesFactureStructure {

    private ColonnesFactureStructure() {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Comment une colonne réunit les prestations d'une visite sur sa ligne du document :
     * <ul>
     *   <li>{@code IDENTITE} — le patient : la première valeur renseignée ;</li>
     *   <li>{@code COMMUN} — date, service… : une seule fois si toutes les
     *       prestations ont la même, sinon une par prestation, à la ligne dans la cellule ;</li>
     *   <li>{@code LISTE} — prestation, quantité, prix : une par prestation, à la ligne, dans
     *       le même ordre d'une colonne à l'autre ;</li>
     *   <li>{@code SOMME} — montants : additionnés (et totalisés en bas du tableau) ;</li>
     *   <li>{@code MAX} — forfait : la valeur la plus haute (il ne porte que sur une prestation).</li>
     * </ul>
     */
    public enum Agregat { IDENTITE, COMMUN, LISTE, SOMME, MAX }

    /**
     * Colonne de l'état nominatif : intitulé, extraction de la valeur d'une prestation,
     * totalisation en bas du tableau, et réunion des prestations d'une visite.
     */
    public record Colonne(String entete, Function<LigneFactureStructure, Object> valeur, boolean total,
                          Agregat agregat) {
        public Colonne(String entete, Function<LigneFactureStructure, Object> valeur) {
            this(entete, valeur, false, Agregat.COMMUN);
        }

        public Colonne(String entete, Function<LigneFactureStructure, Object> valeur, boolean total) {
            this(entete, valeur, total, total ? Agregat.SOMME : Agregat.COMMUN);
        }

        /** Colonne qui décrit le patient (identité), écrite une fois par visite. */
        public static Colonne identite(String entete, Function<LigneFactureStructure, Object> valeur) {
            return new Colonne(entete, valeur, false, Agregat.IDENTITE);
        }

        /** Colonne propre à chaque prestation (désignation, quantité, prix), une par ligne de la cellule. */
        public static Colonne liste(String entete, Function<LigneFactureStructure, Object> valeur) {
            return new Colonne(entete, valeur, false, Agregat.LISTE);
        }
    }

    /**
     * Lignes de la facture une par une, dans l'ordre des dates de prise en charge (tri stable :
     * l'ordre de saisie départage un même jour).
     */
    public static List<LigneFactureStructure> parDate(List<LigneFactureStructure> lignes) {
        List<LigneFactureStructure> triees = new ArrayList<>(lignes == null ? List.of() : lignes);
        triees.sort(Comparator.comparing(LigneFactureStructure::getDatePriseEnCharge,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return triees;
    }

    /**
     * Les visites de la facture, une par ligne des documents (demande du 07/10/2026) : un
     * patient revenu dans le mois a autant de lignes que de visites, et les prestations d'une
     * visite, aussi nombreuses soient-elles, tiennent sur sa ligne (cf. {@link #valeursVisite}).
     *
     * <p>Une visite = un dossier patient ; à défaut (anciennes saisies d'un centre), une lettre
     * de garantie ; à défaut, un même nom — la règle de l'écran. Visites dans l'ordre de leur
     * première date de prise en charge, prestations de chacune par date (tris stables).</p>
     */
    public static List<List<LigneFactureStructure>> parVisite(List<LigneFactureStructure> lignes) {
        Map<String, List<LigneFactureStructure>> visites = new LinkedHashMap<>();
        for (LigneFactureStructure ligne : parDate(lignes)) {
            visites.computeIfAbsent(cleVisite(ligne), cle -> new ArrayList<>()).add(ligne);
        }
        return new ArrayList<>(visites.values());
    }

    private static String cleVisite(LigneFactureStructure l) {
        if (l.getDossierId() != null && !l.getDossierId().isBlank()) {
            return "d:" + l.getDossierId();
        }
        if (l.getLettreGarantieNumero() != null && !l.getLettreGarantieNumero().isBlank()) {
            return "l:" + l.getLettreGarantieNumero();
        }
        return "n:" + normaliser(l.getPatientPrenom()) + "|" + normaliser(l.getPatientNom());
    }

    /**
     * Groupes de lignes pour l'export des documents : une ligne par visite pour tous les régimes
     * (cf. {@link #parVisite}). Les prestations d'une même visite tiennent sur la ligne du patient,
     * chacune à la ligne dans sa cellule — prestation, P.U et montant alignés — sans dupliquer
     * l'identité du patient (une seule occurrence par visite, et non une ligne par prestation).
     */
    public static List<List<LigneFactureStructure>> groupesPourRegime(Regime regime, List<LigneFactureStructure> lignes) {
        return parVisite(lignes);
    }

    /**
     * Valeurs de la ligne d'une visite, colonne par colonne. Une visite d'une seule
     * prestation garde ses valeurs telles quelles (nombres compris) ; plusieurs prestations
     * sont réunies selon l'{@link Agregat} de chaque colonne — les listes en texte, une
     * prestation par ligne de la cellule ({@code \n}).
     */
    public static Object[] valeursVisite(List<LigneFactureStructure> visite, List<Colonne> colonnes) {
        Object[] valeurs = new Object[colonnes.size()];
        for (int i = 0; i < colonnes.size(); i++) {
            valeurs[i] = agreger(colonnes.get(i), visite);
        }
        return valeurs;
    }

    /**
     * Une cellule du bloc d'une visite : sa valeur et le nombre de lignes qu'elle occupe
     * ({@code rowspan}, fusion verticale). Une cellule « couverte » par la fusion de la cellule
     * du dessus est représentée par {@code null} dans la grille de {@link #grilleVisite}.
     */
    public record CelluleFusion(Object valeur, int rowspan) {}

    /**
     * Grille d'une visite : une ligne par prestation, dans sa propre cellule. Les colonnes
     * d'identité du patient (et les colonnes communes partagées par toutes les prestations, ainsi
     * que les totaux/forfaits de la visite) sont fusionnées verticalement sur toutes les lignes —
     * le patient n'est pas dupliqué — tandis que les colonnes « liste » (prestation, quantité,
     * P.U, montant) ont une cellule distincte par prestation.
     *
     * <p>La cellule du haut d'une colonne fusionnée porte sa valeur et un {@code rowspan} égal au
     * nombre de prestations ; les lignes suivantes de cette colonne valent {@code null} (couvertes).</p>
     */
    public static CelluleFusion[][] grilleVisite(List<LigneFactureStructure> visite, List<Colonne> colonnes) {
        int n = Math.max(1, visite == null ? 0 : visite.size());
        CelluleFusion[][] grille = new CelluleFusion[n][colonnes.size()];
        for (int c = 0; c < colonnes.size(); c++) {
            Colonne colonne = colonnes.get(c);
            if (estParLigne(colonne, visite)) {
                for (int r = 0; r < n; r++) {
                    Object v = r < visite.size() ? colonne.valeur().apply(visite.get(r)) : null;
                    grille[r][c] = new CelluleFusion(v, 1);
                }
            } else {
                // Une seule cellule pour tout le bloc : valeur en haut, lignes suivantes couvertes (null).
                grille[0][c] = new CelluleFusion(agreger(colonne, visite), n);
                for (int r = 1; r < n; r++) {
                    grille[r][c] = null;
                }
            }
        }
        return grille;
    }

    /** Une colonne a-t-elle une cellule par prestation (vrai) ou une cellule fusionnée (faux) ? */
    private static boolean estParLigne(Colonne colonne, List<LigneFactureStructure> visite) {
        if (visite == null || visite.size() <= 1) {
            return false; // une seule prestation : rien à séparer ni à fusionner.
        }
        return switch (colonne.agregat()) {
            case LISTE -> true;
            // Une colonne commune dont la valeur diffère d'une prestation à l'autre est détaillée ligne par ligne.
            case COMMUN -> visite.stream().map(colonne.valeur()).map(ColonnesFactureStructure::texte)
                    .filter(t -> !t.isBlank()).distinct().count() > 1;
            default -> false; // IDENTITE, SOMME, MAX : fusionnées sur tout le bloc.
        };
    }

    private static Object agreger(Colonne colonne, List<LigneFactureStructure> visite) {
        List<Object> brutes = visite.stream().map(colonne.valeur()).toList();
        if (brutes.size() == 1) {
            return brutes.get(0);
        }
        switch (colonne.agregat()) {
            case SOMME:
                return brutes.stream().filter(v -> v instanceof Number).mapToDouble(v -> ((Number) v).doubleValue()).sum();
            case MAX:
                return brutes.stream().filter(v -> v instanceof Number).mapToDouble(v -> ((Number) v).doubleValue())
                        .max().orElse(0);
            case IDENTITE:
                return brutes.stream().filter(v -> !texte(v).isBlank()).findFirst().orElse(brutes.get(0));
            case COMMUN:
                if (brutes.stream().map(ColonnesFactureStructure::texte).filter(t -> !t.isBlank()).distinct().count() <= 1) {
                    return brutes.stream().filter(v -> !texte(v).isBlank()).findFirst().orElse(brutes.get(0));
                }
                return String.join("\n", brutes.stream().map(ColonnesFactureStructure::texte).toList());
            case LISTE:
            default:
                return String.join("\n", brutes.stream().map(ColonnesFactureStructure::texte).toList());
        }
    }

    /**
     * Somme numérique d'une colonne sur une visite, pour la ligne des totaux — indépendante de
     * l'agrégation d'affichage : une colonne « montant » rendue en liste (une valeur par ligne de
     * la cellule) est quand même totalisée sur l'ensemble de ses prestations.
     */
    public static double sommeVisite(Colonne colonne, List<LigneFactureStructure> visite) {
        return visite.stream().map(colonne.valeur())
                .filter(v -> v instanceof Number)
                .mapToDouble(v -> ((Number) v).doubleValue())
                .sum();
    }

    /** Valeur écrite en texte : nombres au format « 1 400 », vide pour une valeur absente. */
    public static String texte(Object valeur) {
        if (valeur == null) return "";
        if (valeur instanceof Number nombre) {
            double d = nombre.doubleValue();
            java.text.DecimalFormatSymbols symboles = new java.text.DecimalFormatSymbols(Locale.FRANCE);
            symboles.setGroupingSeparator(' ');
            return new java.text.DecimalFormat(d == Math.rint(d) ? "#,##0" : "#,##0.##", symboles).format(d);
        }
        return valeur.toString();
    }

    /** Nombre de lignes de texte d'une cellule (hauteur de la ligne du tableau). */
    public static int nombreDeLignes(Object valeur) {
        return valeur instanceof String s ? s.split("\n", -1).length : 1;
    }

    /**
     * Patients distincts (pour les décomptes « nombre de patients ») — les documents, eux,
     * n'en regroupent plus les lignes (cf. {@link #parDate}).
     */
    public static List<List<LigneFactureStructure>> parPatient(List<LigneFactureStructure> lignes) {
        Map<String, List<LigneFactureStructure>> groupes = new LinkedHashMap<>();
        for (LigneFactureStructure ligne : lignes == null ? List.<LigneFactureStructure>of() : lignes) {
            groupes.computeIfAbsent(clePatient(ligne), cle -> new ArrayList<>()).add(ligne);
        }
        // Tri stable : deux prestations du même jour gardent leur ordre de saisie.
        groupes.values().forEach(siennes -> siennes.sort(Comparator.comparing(
                LigneFactureStructure::getDatePriseEnCharge, Comparator.nullsLast(Comparator.naturalOrder()))));
        return new ArrayList<>(groupes.values());
    }

    private static String clePatient(LigneFactureStructure l) {
        String matricule = normaliser(l.getPatientMatricule());
        if (!matricule.isEmpty()) {
            return "m:" + matricule;
        }
        if (l.getPatientId() != null && !l.getPatientId().isBlank()) {
            return "p:" + l.getPatientId();
        }
        return "n:" + normaliser(l.getPatientPrenom()) + "|" + normaliser(l.getPatientNom()) + "|"
                + (l.getPatientDateNaissance() == null ? "" : l.getPatientDateNaissance());
    }

    private static String normaliser(String texte) {
        if (texte == null) return "";
        return java.text.Normalizer.normalize(texte.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    public static List<Colonne> pourRegime(Regime regime) {
        if (regime == null) return classique();
        return switch (regime) {
            case ZERO_CINQ_ANS -> enfants();
            case CESARIENNE -> cesarienne();
            case DIALYSE_PERITONEALE -> dialysePeritoneale();
            case HEMODIALYSE, DIALYSE -> hemodialyse();
            case BSF, CEC, BSF_CEC -> bsfCec();
            case SESAME -> sesame();
            case CONTRIBUTIF, NDONGO_DARA, ELEVES -> classique();
        };
    }

    /** Régimes contributifs : Classique, Ndongo Daara et Élèves. */
    private static List<Colonne> classique() {
        List<Colonne> c = new ArrayList<>();
        c.add(Colonne.identite("Prénom(s)", LigneFactureStructure::getPatientPrenom));
        c.add(Colonne.identite("Nom", LigneFactureStructure::getPatientNom));
        c.add(Colonne.identite("Date de naissance", ColonnesFactureStructure::dateNaissance));
        c.add(Colonne.identite("Sexe (H/F)", l -> sexe(l, false)));
        c.add(Colonne.identite("N° Tél", LigneFactureStructure::getPatientTelephone));
        c.add(Colonne.identite("N° Matricule / Code Bénéficiaire", ColonnesFactureStructure::matricule));
        c.add(new Colonne("Date de prise en charge", ColonnesFactureStructure::datePriseEnCharge));
        c.add(new Colonne("Service", LigneFactureStructure::getService));
        c.add(Colonne.liste("Prestation(s)", LigneFactureStructure::getDesignation));
        c.add(Colonne.liste("Quantité", LigneFactureStructure::getQuantite));
        c.add(Colonne.liste("P.U", LigneFactureStructure::getPrixUnitaire));
        // Montant de chaque prestation à la ligne, aligné sur sa prestation et son P.U (et totalisé en bas).
        c.add(new Colonne("Montant Total", LigneFactureStructure::getMontant, true, Agregat.LISTE));
        return c;
    }

    /** Enfants de moins de 5 ans. */
    private static List<Colonne> enfants() {
        List<Colonne> c = new ArrayList<>();
        c.add(Colonne.identite("N° dans le registre", LigneFactureStructure::getNumeroRegistre));
        c.add(Colonne.identite("Prénom(s)", LigneFactureStructure::getPatientPrenom));
        c.add(Colonne.identite("Nom", LigneFactureStructure::getPatientNom));
        c.add(Colonne.identite("Date de naissance", ColonnesFactureStructure::dateNaissance));
        c.add(Colonne.identite("Sexe (M/F)", l -> sexe(l, true)));
        c.add(Colonne.identite("Adresse", LigneFactureStructure::getPatientAdresse));
        c.add(Colonne.identite("N° Matricule / N° Extrait de naissance / N° accompagnant", ColonnesFactureStructure::matricule));
        c.add(Colonne.identite("N° Téléphone", LigneFactureStructure::getPatientTelephone));
        c.add(new Colonne("Date de prise en Charge", ColonnesFactureStructure::datePriseEnCharge));
        c.add(new Colonne("Service", LigneFactureStructure::getService));
        c.add(Colonne.liste("Prestations et médicaments", LigneFactureStructure::getDesignation));
        // Le forfait ne porte que sur une prestation de la visite (les autres sont à 0).
        c.add(new Colonne("Forfait", LigneFactureStructure::getPrixUnitaire, false, Agregat.MAX));
        c.add(new Colonne("Montant Total", LigneFactureStructure::getMontant, true));
        return c;
    }

    /** Césarienne (pas de colonne montant dans le modèle : la part SEN-CSU figure en récapitulatif). */
    private static List<Colonne> cesarienne() {
        List<Colonne> c = new ArrayList<>();
        c.add(Colonne.identite("Prénom(s)", LigneFactureStructure::getPatientPrenom));
        c.add(Colonne.identite("Nom", LigneFactureStructure::getPatientNom));
        c.add(Colonne.identite("Date de naissance", ColonnesFactureStructure::dateNaissance));
        c.add(Colonne.identite("Sexe (H/F)", l -> sexe(l, false)));
        c.add(Colonne.identite("Adresse", LigneFactureStructure::getPatientAdresse));
        c.add(Colonne.identite("N° Téléphone", LigneFactureStructure::getPatientTelephone));
        c.add(Colonne.identite("N° Matricule / N° CNI Patient / N° accompagnant", ColonnesFactureStructure::matricule));
        c.add(new Colonne("Indication / Motif de CBT", ColonnesFactureStructure::indicationCesarienne));
        c.add(new Colonne("N° Registre Bloc opératoire", LigneFactureStructure::getNumeroRegistreBloc));
        c.add(new Colonne("Date et Heure Intervention", LigneFactureStructure::getDateHeureIntervention));
        c.add(new Colonne("Durée Hospitalisation (jours)", LigneFactureStructure::getDureeHospitalisationJours));
        return c;
    }

    /** Dialyse péritonéale (nombre de poches). */
    private static List<Colonne> dialysePeritoneale() {
        List<Colonne> c = new ArrayList<>();
        identiteDialyse(c);
        c.add(new Colonne("Date de prise en charge", ColonnesFactureStructure::datePriseEnCharge));
        c.add(Colonne.liste("Nbre de Poches", LigneFactureStructure::getQuantite));
        c.add(Colonne.liste("Prix Unitaire", LigneFactureStructure::getPrixUnitaire));
        c.add(new Colonne("Prix Total", LigneFactureStructure::getMontant, true));
        return c;
    }

    /** Hémodialyse (nombre de séances) — sert aussi à l'ancien régime DIALYSE. */
    private static List<Colonne> hemodialyse() {
        List<Colonne> c = new ArrayList<>();
        identiteDialyse(c);
        c.add(Colonne.liste("Nbre de Séances", LigneFactureStructure::getQuantite));
        c.add(Colonne.liste("Prix Unitaire", LigneFactureStructure::getPrixUnitaire));
        c.add(new Colonne("Prix Total", LigneFactureStructure::getMontant, true));
        return c;
    }

    /** Identité commune aux dialyses (jusqu'à la colonne IRC/IRA incluse). */
    private static void identiteDialyse(List<Colonne> c) {
        c.add(Colonne.identite("Prénom(s)", LigneFactureStructure::getPatientPrenom));
        c.add(Colonne.identite("Nom", LigneFactureStructure::getPatientNom));
        c.add(Colonne.identite("Date de naissance", ColonnesFactureStructure::dateNaissance));
        c.add(Colonne.identite("Sexe (H/F)", l -> sexe(l, false)));
        c.add(Colonne.identite("Adresse", LigneFactureStructure::getPatientAdresse));
        c.add(Colonne.identite("N° Téléphone", LigneFactureStructure::getPatientTelephone));
        c.add(Colonne.identite("N° Matricule / N° CNI Patient / N° accompagnant", ColonnesFactureStructure::matricule));
        c.add(Colonne.identite("IRC/IRA", LigneFactureStructure::getIrcIra));
    }

    /** Bourse de Sécurité Familiale et Carte Égalité des Chances (montant facturé à la SEN-CSU). */
    private static List<Colonne> bsfCec() {
        List<Colonne> c = new ArrayList<>();
        c.add(Colonne.identite("Prénom(s)", LigneFactureStructure::getPatientPrenom));
        c.add(Colonne.identite("Nom", LigneFactureStructure::getPatientNom));
        c.add(Colonne.identite("Date de naissance", ColonnesFactureStructure::dateNaissance));
        c.add(Colonne.identite("Sexe (H/F)", l -> sexe(l, false)));
        c.add(Colonne.identite("Adresse", LigneFactureStructure::getPatientAdresse));
        c.add(Colonne.identite("N° Tél", LigneFactureStructure::getPatientTelephone));
        c.add(Colonne.identite("N° Matricule / CNI", ColonnesFactureStructure::matricule));
        c.add(new Colonne("Date de prise en charge", ColonnesFactureStructure::datePriseEnCharge));
        c.add(new Colonne("Service", LigneFactureStructure::getService));
        c.add(Colonne.liste("Prestation(s)", LigneFactureStructure::getDesignation));
        c.add(Colonne.liste("Quantité", LigneFactureStructure::getQuantite));
        c.add(Colonne.liste("P.U", LigneFactureStructure::getPrixUnitaire));
        c.add(new Colonne("Montant facturé à la SEN-CSU", LigneFactureStructure::getMontantSencsu, true));
        return c;
    }

    /** Plan Sésame (matricule ET n° CNI dans deux colonnes distinctes). */
    private static List<Colonne> sesame() {
        List<Colonne> c = new ArrayList<>();
        c.add(Colonne.identite("Prénom(s)", LigneFactureStructure::getPatientPrenom));
        c.add(Colonne.identite("Nom", LigneFactureStructure::getPatientNom));
        c.add(Colonne.identite("Date de naissance", ColonnesFactureStructure::dateNaissance));
        c.add(Colonne.identite("Sexe (H/F)", l -> sexe(l, false)));
        c.add(Colonne.identite("Adresse", LigneFactureStructure::getPatientAdresse));
        c.add(Colonne.identite("N° Tél", LigneFactureStructure::getPatientTelephone));
        c.add(Colonne.identite("N° Matricule", l -> nonVide(l.getPatientMatricule())));
        c.add(Colonne.identite("N° CNI", LigneFactureStructure::getPatientNumeroCni));
        c.add(new Colonne("Date de prise en charge", ColonnesFactureStructure::datePriseEnCharge));
        c.add(new Colonne("Service", LigneFactureStructure::getService));
        c.add(Colonne.liste("Prestation(s)", LigneFactureStructure::getDesignation));
        c.add(Colonne.liste("Quantité", LigneFactureStructure::getQuantite));
        c.add(Colonne.liste("P.U", LigneFactureStructure::getPrixUnitaire));
        c.add(new Colonne("Montant facturé à la SEN-CSU", LigneFactureStructure::getMontantSencsu, true));
        return c;
    }

    // ===================== Extracteurs de valeurs =====================

    public static String dateNaissance(LigneFactureStructure l) {
        return l.getPatientDateNaissance() != null ? l.getPatientDateNaissance().format(DATE) : "";
    }

    public static String datePriseEnCharge(LigneFactureStructure l) {
        return l.getDatePriseEnCharge() != null ? l.getDatePriseEnCharge().format(DATE) : "";
    }

    /** Matricule du bénéficiaire, avec repli sur le n° CNI si le matricule n'est pas renseigné. */
    public static String matricule(LigneFactureStructure l) {
        if (l.getPatientMatricule() != null && !l.getPatientMatricule().isBlank()) {
            return l.getPatientMatricule();
        }
        return nonVide(l.getPatientNumeroCni());
    }

    private static String indicationCesarienne(LigneFactureStructure l) {
        if (l.getIndicationCbt() != null && !l.getIndicationCbt().isBlank()) {
            return l.getIndicationCbt();
        }
        return nonVide(l.getMotifCesarienne());
    }

    /**
     * Sexe affiché selon le modèle : les enfants de moins de 5 ans utilisent M/F,
     * tous les autres régimes utilisent H/F (le sexe est stocké de façon canonique en M/F).
     */
    public static String sexe(LigneFactureStructure l, boolean enfants) {
        String s = l.getPatientSexe();
        if (s == null || s.isBlank()) return "";
        boolean feminin = s.trim().toUpperCase(Locale.ROOT).startsWith("F");
        if (feminin) return "F";
        return enfants ? "M" : "H";
    }

    private static String nonVide(String s) {
        return s != null ? s : "";
    }

    // ===================== Libellés =====================

    public static String titreLong(Regime regime) {
        if (regime == null) return "ETAT RECAPITULATIF NOMINATIF";
        return switch (regime) {
            case CONTRIBUTIF -> "ETAT RECAPITULATIF NOMINATIF DU REGIME CLASSIQUE";
            case ZERO_CINQ_ANS -> "LISTE NOMINATIVE DES ENFANTS DE MOINS DE 5 ANS";
            case CESARIENNE -> "LISTE NOMINATIVE DE LA CESARIENNE";
            case DIALYSE_PERITONEALE -> "ETAT RECAPITULATIF NOMINATIF DE LA DIALYSE PERITONEALE";
            case HEMODIALYSE -> "ETAT RECAPITULATIF NOMINATIF DE L'HEMODIALYSE";
            case BSF -> "ETAT RECAPITULATIF NOMINATIF DE LA BOURSE DE SECURITE FAMILIALE";
            case CEC -> "ETAT RECAPITULATIF NOMINATIF DE LA CARTE EGALITE DES CHANCES";
            case SESAME -> "ETAT RECAPITULATIF NOMINATIF DU PLAN SESAME";
            case NDONGO_DARA -> "ETAT RECAPITULATIF NOMINATIF DU REGIME NDONGO DAARA";
            case ELEVES -> "ETAT RECAPITULATIF NOMINATIF DU REGIME ELEVES";
            case DIALYSE -> "ETAT RECAPITULATIF NOMINATIF DE LA DIALYSE";
            case BSF_CEC -> "ETAT RECAPITULATIF NOMINATIF BSF / CEC";
        };
    }

    public static String titreCourt(Regime regime) {
        if (regime == null) return "Facture";
        return switch (regime) {
            case CONTRIBUTIF -> "Classique";
            case ZERO_CINQ_ANS -> "Enfants 0-5 ans";
            case CESARIENNE -> "Césariennes";
            case DIALYSE_PERITONEALE -> "Dialyse péritonéale";
            case HEMODIALYSE -> "Hémodialyse";
            case BSF -> "BSF";
            case CEC -> "CEC";
            case SESAME -> "Sésame";
            case NDONGO_DARA -> "Ndongo Daara";
            case ELEVES -> "Élèves";
            case DIALYSE -> "Dialyse";
            case BSF_CEC -> "BSF-CEC";
        };
    }
}
