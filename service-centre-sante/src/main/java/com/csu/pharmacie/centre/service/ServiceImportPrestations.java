package com.csu.pharmacie.centre.service;

import com.csu.pharmacie.commun.export.LecteurExcel;
import com.csu.pharmacie.commun.export.LecteurExcel.LigneImport;
import com.csu.pharmacie.commun.facturation.DepotFactureStructure;
import com.csu.pharmacie.commun.facturation.ReglesFacturation;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.RapportRapprochement;
import com.csu.pharmacie.entity.RapportRapprochement.Anomalie;
import com.csu.pharmacie.entity.RapportRapprochement.PatientEnAttente;
import com.csu.pharmacie.entity.RapportRapprochement.SansCorrespondance;
import com.csu.pharmacie.entity.RapportRapprochement.Visite;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.utils.CleRapprochement;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Import de fin de mois d'un centre de santé ou d'un EPS (spécification « Facturation CS /
 * EPS », §4 et §5) : la structure complète, avec les prestations et les montants de son
 * logiciel, les patients que le BCSU a enregistrés au fil du mois.
 *
 * <ul>
 *   <li><b>Notre modèle, un par régime</b> (arbitrage du 12/10/2026) — prérempli des patients
 *       du BCSU : identifiant, date de prise en charge, identité. La structure n'a plus qu'à
 *       écrire les prestations.</li>
 *   <li><b>Rapprochement</b> sur la clé du régime + la date de prise en charge (+ l'enfant pour
 *       les 0-5 ans), par {@link CleRapprochement}, la même règle que la saisie du BCSU.</li>
 *   <li><b>En deux temps</b> : l'aperçu (rien n'est enregistré) puis la confirmation. L'import
 *       est <b>partiel</b> : les visites rapprochées sont complétées, le reste est rapporté.</li>
 *   <li><b>Réimport sans doublon</b> : les prestations importées d'un patient sont remplacées,
 *       jamais additionnées (cf. {@code ServiceFacturation#appliquerImport}).</li>
 *   <li><b>Montants</b> : quantité et prix du fichier ; les parts sont calculées par les règles
 *       de facturation, selon le régime.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ServiceImportPrestations {

    /** Lignes vides proposées sous les patients du BCSU, pour les prestations supplémentaires. */
    private static final int LIGNES_LIBRES = 30;
    private static final DateTimeFormatter JJ_MM_AAAA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** Les messages du lecteur commencent par « Ligne N : » ; le rapport porte déjà le numéro. */
    private static final Pattern PREFIXE_LIGNE = Pattern.compile("^Ligne \\d+ : ");

    private final DepotFactureStructure depot;
    private final ServiceFactureCentre serviceFacture;
    private final ReglesFacturation regles;

    // ══════════════════════════ colonnes ══════════════════════════

    /** Colonne du modèle : l'intitulé écrit dans le modèle, puis les autres intitulés acceptés. */
    enum Colonne {
        ORDRE("N°"),
        /** Identifiant du régime : son intitulé dépend du régime (cf. {@link #intitulesCle}). */
        CLE(),
        DATE("Date de prise en charge", "Date des soins", "Date de soins", "Date de consultation"),
        PRENOM("Prénom(s)", "Prénom", "Prénoms"),
        NOM("Nom", "Nom de famille"),
        EXTRAIT("N° extrait de naissance", "N° de l'extrait de naissance", "Extrait de naissance", "N° extrait"),
        NAISSANCE("Date de naissance", "Né le", "Née le"),
        REGISTRE("N° dans le registre", "N° registre", "N° de registre"),
        SERVICE("Service"),
        INDICATION("Indication / Motif de CBT", "Indication", "Motif de CBT"),
        REGISTRE_BLOC("N° registre bloc opératoire", "N° registre bloc"),
        DATE_INTERVENTION("Date et heure de l'intervention", "Date et heure intervention", "Date intervention"),
        DUREE("Durée d'hospitalisation (jours)", "Durée hospitalisation (jours)", "Durée hospitalisation"),
        PRESTATION("Prestation", "Prestation(s)", "Prestations", "Désignation", "Acte"),
        CODE("Code acte", "Code"),
        QUANTITE("Quantité", "Qté"),
        PRIX("Prix unitaire (FCFA)", "Prix unitaire", "P.U", "PU"),
        /** Calculé dans le modèle pour la lecture ; ignoré à l'import (quantité × prix fait foi). */
        MONTANT("Montant (FCFA)");

        private final String[] intitules;

        Colonne(String... intitules) {
            this.intitules = intitules;
        }

        String[] intitules(Regime regime) {
            return this == CLE ? intitulesCle(regime) : intitules;
        }

        String intitule(Regime regime) {
            return intitules(regime)[0];
        }

        /** Colonnes que le BCSU a renseignées : préremplies et grisées dans le modèle. */
        boolean duBcsu() {
            return this == ORDRE || this == CLE || this == DATE || this == PRENOM || this == NOM
                    || this == EXTRAIT || this == NAISSANCE;
        }
    }

    static String[] intitulesCle(Regime regime) {
        return switch (regime) {
            case ZERO_CINQ_ANS -> new String[]{"N° CNI de l'accompagnant", "CNI de l'accompagnant",
                    "CNI accompagnant", "N° CNI accompagnant"};
            case SESAME, CESARIENNE -> new String[]{"N° CNI", "CNI", "Numéro CNI", "N° de CNI"};
            default -> new String[]{"N° d'immatriculation", "Code d'immatriculation", "N° immatriculation",
                    "Immatriculation", "N° Matricule", "Matricule"};
        };
    }

    /** Colonnes du modèle d'un régime, dans l'ordre. */
    static List<Colonne> colonnes(Regime regime) {
        List<Colonne> colonnes = new ArrayList<>(List.of(Colonne.ORDRE, Colonne.CLE, Colonne.DATE,
                Colonne.PRENOM, Colonne.NOM));
        if (regime == Regime.ZERO_CINQ_ANS) {
            colonnes.addAll(List.of(Colonne.EXTRAIT, Colonne.NAISSANCE, Colonne.REGISTRE));
        }
        colonnes.add(Colonne.SERVICE);
        if (regime == Regime.CESARIENNE) {
            colonnes.addAll(List.of(Colonne.INDICATION, Colonne.REGISTRE_BLOC, Colonne.DATE_INTERVENTION, Colonne.DUREE));
        }
        colonnes.addAll(List.of(Colonne.PRESTATION, Colonne.CODE, Colonne.QUANTITE, Colonne.PRIX, Colonne.MONTANT));
        return colonnes;
    }

    // ══════════════════════════ import ══════════════════════════

    /** Ce que l'analyse du fichier produit : le rapport, et les prestations à appliquer par dossier. */
    record Analyse(RapportRapprochement rapport, Map<String, List<LigneFactureStructure>> lignesParDossier) {
    }

    /**
     * Rapproche le fichier de la facture du mois et, hors aperçu, l'applique.
     *
     * @param simulation aperçu seulement : le rapport dit ce que l'import ferait, rien n'est enregistré
     */
    @Transactional
    public RapportRapprochement importer(MultipartFile fichier, Regime regime, int mois, int annee, boolean simulation) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        String structureId = ContexteUtilisateur.structureCourante();
        controler(regime, mois, annee);
        if (fichier == null || fichier.isEmpty()) {
            throw ExceptionMetier.invalide("Aucun fichier reçu.");
        }
        FactureStructure facture = depot.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(structureId, regime, mois, annee)
                .orElse(null);

        Analyse analyse;
        try (InputStream flux = fichier.getInputStream()) {
            analyse = analyser(LecteurExcel.lire(flux), regime, mois, annee, facture);
        } catch (ExceptionMetier e) {
            // Fichier illisible ou vide : le rapport le dit, comme le reste.
            RapportRapprochement rapport = new RapportRapprochement();
            rapport.getErreurs().add(e.getMessage());
            analyse = new Analyse(rapport, Map.of());
        } catch (IOException e) {
            throw ExceptionMetier.invalide("Lecture du fichier impossible : " + e.getMessage());
        }

        RapportRapprochement rapport = analyse.rapport();
        rapport.setDate(LocalDateTime.now());
        rapport.setUtilisateurNom(utilisateur.nomComplet());
        rapport.setFichier(fichier.getOriginalFilename());
        rapport.setRegime(regime);
        rapport.setMois(mois);
        rapport.setAnnee(annee);
        if (facture != null) {
            rapport.setFactureId(facture.getId());
            rapport.setFactureNumero(facture.getNumero());
            if (!facture.getStatut().estModifiable()) {
                rapport.getErreurs().add("La facture " + facture.getNumero() + " est « " + facture.getStatut().getLibelle()
                        + " » : elle ne peut plus être complétée.");
            }
        }
        rapport.setApplicable(facture != null && rapport.getErreurs().isEmpty() && !analyse.lignesParDossier().isEmpty());

        if (simulation || !rapport.isApplicable()) {
            rapport.setSimulation(true);
            return rapport;
        }
        rapport.setSimulation(false);
        serviceFacture.appliquerImport(facture.getId(), analyse.lignesParDossier(), rapport);
        return rapport;
    }

    /** Rapport du dernier import confirmé de la facture du mois, {@code null} s'il n'y en a pas. */
    public RapportRapprochement dernierRapport(Regime regime, int mois, int annee) {
        controler(regime, mois, annee);
        return depot.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(ContexteUtilisateur.structureCourante(),
                        regime, mois, annee)
                .map(FactureStructure::getDernierRapprochement)
                .orElse(null);
    }

    private static void controler(Regime regime, int mois, int annee) {
        if (!CleRapprochement.regimeConcerne(regime)) {
            throw ExceptionMetier.invalide("L'import de fin de mois concerne les régimes Classique, BSF, CEC, "
                    + "Plan Sésame, Césarienne et Enfants de moins de 5 ans.");
        }
        if (mois < 1 || mois > 12) {
            throw ExceptionMetier.invalide("Mois invalide : " + mois);
        }
        if (annee < 2000 || annee > 2100) {
            throw ExceptionMetier.invalide("Année invalide : " + annee);
        }
    }

    // ══════════════════════════ analyse ══════════════════════════

    /** Une ligne du fichier, ses valeurs converties, et ce qui l'empêche éventuellement d'être importée. */
    private static final class Lue {
        final int numero;
        String cleBrute;
        String cle;
        String dateBrute;
        LocalDate date;
        String prenom;
        String nom;
        String extrait;
        LocalDate naissance;
        String registre;
        String service;
        String indication;
        String registreBloc;
        String dateIntervention;
        Integer duree;
        String prestation;
        String code;
        int quantite = 1;
        Double prix;
        /** Identifiant ou date inexploitable : vaut pour toutes les lignes de la visite. */
        String anomalieVisite;
        String anomaliePrestation;

        Lue(int numero) {
            this.numero = numero;
        }

        boolean porteVisite() {
            return cleBrute != null || dateBrute != null;
        }

        /** Ligne sans identifiant ni date : elle prolonge la visite du dessus. */
        void prolonger(Lue visite) {
            cleBrute = visite.cleBrute;
            cle = visite.cle;
            dateBrute = visite.dateBrute;
            date = visite.date;
            prenom = prenom != null ? prenom : visite.prenom;
            nom = nom != null ? nom : visite.nom;
            extrait = extrait != null ? extrait : visite.extrait;
            naissance = naissance != null ? naissance : visite.naissance;
            registre = registre != null ? registre : visite.registre;
            service = service != null ? service : visite.service;
            indication = indication != null ? indication : visite.indication;
            registreBloc = registreBloc != null ? registreBloc : visite.registreBloc;
            dateIntervention = dateIntervention != null ? dateIntervention : visite.dateIntervention;
            duree = duree != null ? duree : visite.duree;
            if (visite.anomalieVisite != null) {
                anomalieVisite = "rattachée au patient de la ligne " + visite.numero + " : " + visite.anomalieVisite;
            }
        }

        String patient() {
            return ((prenom == null ? "" : prenom) + " " + (nom == null ? "" : nom)).trim();
        }

        double montant() {
            return quantite * (prix == null ? 0 : prix);
        }
    }

    /**
     * Rapproche les lignes du fichier des patients enregistrés par le BCSU dans la facture (qui
     * peut ne pas exister encore). Ne modifie rien : c'est {@code ServiceFacturation#appliquerImport}
     * qui applique.
     */
    Analyse analyser(List<LigneImport> toutes, Regime regime, int mois, int annee, FactureStructure facture) {
        RapportRapprochement rapport = new RapportRapprochement();
        if (toutes.isEmpty()) {
            rapport.getErreurs().add("Aucune ligne sous les en-têtes : le fichier est vide.");
            return new Analyse(rapport, Map.of());
        }
        if (!controlerEntetes(toutes.get(0).valeurs().keySet(), regime, rapport)) {
            return new Analyse(rapport, Map.of());
        }
        // Les lignes libres du modèle ne portent que la formule du montant (que le lecteur rend
        // en texte) ou un n° d'ordre : ce ne sont pas des lignes saisies.
        Set<String> sansDonnee = Set.of(LecteurExcel.normaliser(Colonne.ORDRE.intitule(regime)),
                LecteurExcel.normaliser(Colonne.MONTANT.intitule(regime)));
        List<LigneImport> lignes = toutes.stream()
                .filter(l -> l.valeurs().entrySet().stream().anyMatch(e -> !sansDonnee.contains(e.getKey())
                        && e.getValue() != null && !e.getValue().isBlank()))
                .toList();
        rapport.setLignesLues(lignes.size());

        // 1. Les lignes, une à une. Une ligne sans identifiant ni date prolonge la visite du dessus.
        List<Lue> prestations = new ArrayList<>();
        Lue visite = null;
        for (LigneImport ligne : lignes) {
            Lue lue = lire(ligne, regime, mois, annee);
            if (lue.porteVisite()) {
                visite = lue;
            } else if (lue.prestation != null) {
                if (visite == null) {
                    anomalie(rapport, lue.numero, "ni identifiant ni date de prise en charge, et aucun patient au-dessus.");
                    continue;
                }
                lue.prolonger(visite);
            }
            // Patient du modèle laissé sans prestation : rien à importer, ce n'est pas une erreur.
            if (lue.prestation == null) {
                continue;
            }
            if (lue.anomalieVisite != null) {
                anomalie(rapport, lue.numero, lue.anomalieVisite);
            } else if (lue.anomaliePrestation != null) {
                anomalie(rapport, lue.numero, lue.anomaliePrestation);
            } else {
                prestations.add(lue);
            }
        }

        // 2. Regroupées par visite ; une ligne recopiée à l'identique n'est importée qu'une fois.
        Map<String, List<Lue>> visites = new LinkedHashMap<>();
        Map<String, Integer> signatures = new HashMap<>();
        for (Lue lue : prestations) {
            String cleVisite = cleVisite(regime, lue);
            String signature = cleVisite + "|" + LecteurExcel.normaliser(lue.prestation) + "|"
                    + LecteurExcel.normaliser(lue.code) + "|" + lue.quantite + "|" + lue.prix;
            Integer premiere = signatures.putIfAbsent(signature, lue.numero);
            if (premiere != null) {
                anomalie(rapport, lue.numero, "identique à la ligne " + premiere + " (même patient, même prestation, "
                        + "même prix) : non importée. Si l'acte a été fait deux fois, indiquez-le dans la quantité.");
                continue;
            }
            visites.computeIfAbsent(cleVisite, k -> new ArrayList<>()).add(lue);
        }

        // 3. Chaque visite face aux patients du BCSU.
        List<DossierPatient> bcsu = facture == null ? List.of() : dossiersBcsu(facture);
        Map<String, List<Lue>> parDossier = new LinkedHashMap<>();
        Map<String, DossierPatient> dossiers = new HashMap<>();
        for (List<Lue> groupe : visites.values()) {
            Lue tete = groupe.get(0);
            List<DossierPatient> candidats = bcsu.stream()
                    .filter(d -> tete.cle.equals(CleRapprochement.cleDe(regime, d)) && tete.date.equals(d.getDatePriseEnCharge()))
                    .filter(d -> regime != Regime.ZERO_CINQ_ANS
                            || CleRapprochement.enfantCompatible(tete.extrait, tete.naissance, tete.prenom, d))
                    .toList();
            if (candidats.size() > 1) {
                String noms = candidats.stream().map(DossierPatient::nomComplet).collect(Collectors.joining(", "));
                groupe.forEach(l -> anomalie(rapport, l.numero, "plusieurs patients enregistrés par le BCSU correspondent ("
                        + noms + ") : précisez le n° d'extrait de naissance de l'enfant."));
                continue;
            }
            if (candidats.isEmpty()) {
                List<LocalDate> datesConnues = bcsu.stream()
                        .filter(d -> tete.cle.equals(CleRapprochement.cleDe(regime, d)))
                        .map(DossierPatient::getDatePriseEnCharge)
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList();
                rapport.getSansCorrespondance().add(new SansCorrespondance(numeros(groupe), tete.patient(),
                        tete.cleBrute, tete.date, groupe.size(), !datesConnues.isEmpty(), datesConnues));
                continue;
            }
            DossierPatient dossier = candidats.get(0);
            String age = controlerAge(regime, dossier, tete.date);
            if (age != null) {
                groupe.forEach(l -> anomalie(rapport, l.numero, age));
                continue;
            }
            parDossier.computeIfAbsent(dossier.getId(), k -> new ArrayList<>()).addAll(groupe);
            dossiers.put(dossier.getId(), dossier);
        }

        // 4. Ce que l'import apportera, et ce qu'il laisse.
        Map<String, List<LigneFactureStructure>> lignesParDossier = new LinkedHashMap<>();
        int avecSaisieEcran = 0;
        for (Map.Entry<String, List<Lue>> entree : parDossier.entrySet()) {
            DossierPatient dossier = dossiers.get(entree.getKey());
            List<Lue> groupe = entree.getValue();
            lignesParDossier.put(dossier.getId(), groupe.stream().map(l -> prestation(regime, l)).collect(Collectors.toList()));
            double montant = groupe.stream().mapToDouble(Lue::montant).sum();
            rapport.getRapprochees().add(new Visite(dossier.getId(), dossier.nomComplet(), groupe.get(0).cleBrute,
                    dossier.getDatePriseEnCharge(), dossier.getLettreGarantieNumero(), groupe.size(),
                    ReglesFacturation.arrondir(montant), numeros(groupe)));
            rapport.setPrestationsImportees(rapport.getPrestationsImportees() + groupe.size());
            rapport.setMontantImporte(rapport.getMontantImporte() + montant);
            if (lignesDu(facture, dossier.getId()).stream().anyMatch(l -> !importee(l))) {
                avecSaisieEcran++;
            }
        }
        rapport.setMontantImporte(ReglesFacturation.arrondir(rapport.getMontantImporte()));

        for (DossierPatient dossier : bcsu) {
            if (parDossier.containsKey(dossier.getId())) {
                continue;
            }
            List<LigneFactureStructure> siennes = lignesDu(facture, dossier.getId());
            if (siennes.isEmpty()) {
                rapport.getSansPrestations().add(new PatientEnAttente(dossier.getId(), dossier.nomComplet(),
                        identifiantAffiche(regime, dossier), dossier.getDatePriseEnCharge(), dossier.getLettreGarantieNumero()));
            } else if (siennes.stream().anyMatch(ServiceImportPrestations::importee)) {
                rapport.setDossiersConserves(rapport.getDossiersConserves() + 1);
            }
        }

        if (facture == null) {
            rapport.getAvertissements().add("Aucun patient n'a été enregistré par le BCSU pour ce régime et ce mois : "
                    + "il n'y a rien à rapprocher.");
        }
        if (rapport.getDossiersConserves() > 0) {
            rapport.getAvertissements().add(rapport.getDossiersConserves() + " patient(s) déjà importé(s) n'apparaissent "
                    + "pas dans ce fichier : leurs prestations sont conservées.");
        }
        long sansTicket = dossiers.values().stream().filter(DossierPatient::attendTicketDeCaisse).count();
        if (sansTicket > 0) {
            rapport.getAvertissements().add(sansTicket + " patient(s) rapproché(s) n'ont pas encore de ticket de caisse : "
                    + "ils restent « à compléter » tant que vous ne l'avez pas ajouté (« Ajouter des tickets »).");
        }
        if (avecSaisieEcran > 0) {
            rapport.getAvertissements().add(avecSaisieEcran + " patient(s) avaient déjà des prestations saisies à "
                    + "l'écran : elles sont conservées, celles du fichier s'y ajoutent. Vérifiez qu'elles ne se répètent pas.");
        }
        rapport.getAnomalies().sort(Comparator.comparingInt(Anomalie::ligne));
        rapport.getSansPrestations().sort(Comparator.comparing(PatientEnAttente::date,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(PatientEnAttente::patient));
        return new Analyse(rapport, lignesParDossier);
    }

    /** Colonnes indispensables, et le bon modèle : l'identifiant d'un autre régime trahit une erreur de choix. */
    private static boolean controlerEntetes(Set<String> entetes, Regime regime, RapportRapprochement rapport) {
        if (!present(entetes, intitulesCle(regime))) {
            for (Regime autre : List.of(Regime.CONTRIBUTIF, Regime.SESAME, Regime.ZERO_CINQ_ANS)) {
                if (present(entetes, intitulesCle(autre)) && !intitulesCle(autre)[0].equals(intitulesCle(regime)[0])) {
                    rapport.getErreurs().add("Ce fichier est le modèle d'un autre régime (colonne « "
                            + intitulesCle(autre)[0] + " ») : choisissez le bon régime, ou téléchargez le modèle « "
                            + regime.getLibelle() + " ».");
                    return false;
                }
            }
        }
        List<String> manquantes = new ArrayList<>();
        for (Colonne colonne : List.of(Colonne.CLE, Colonne.DATE, Colonne.PRESTATION, Colonne.PRIX)) {
            if (!present(entetes, colonne.intitules(regime))) {
                manquantes.add(colonne.intitule(regime));
            }
        }
        if (!manquantes.isEmpty()) {
            manquantes.forEach(c -> rapport.getErreurs().add("Colonne « " + c + " » absente de la première ligne. "
                    + "Utilisez le modèle « " + regime.getLibelle() + " », sans modifier ses en-têtes."));
            return false;
        }
        return true;
    }

    private static boolean present(Set<String> entetes, String... intitules) {
        for (String intitule : intitules) {
            if (entetes.contains(LecteurExcel.normaliser(intitule))) {
                return true;
            }
        }
        return false;
    }

    private static Lue lire(LigneImport ligne, Regime regime, int mois, int annee) {
        Lue lue = new Lue(ligne.numeroLigne());
        lue.cleBrute = ligne.texte(Colonne.CLE.intitules(regime));
        lue.dateBrute = ligne.texte(Colonne.DATE.intitules);
        lue.prenom = ligne.texte(Colonne.PRENOM.intitules);
        lue.nom = ligne.texte(Colonne.NOM.intitules);
        lue.extrait = ligne.texte(Colonne.EXTRAIT.intitules);
        lue.registre = ligne.texte(Colonne.REGISTRE.intitules);
        lue.service = ligne.texte(Colonne.SERVICE.intitules);
        lue.indication = ligne.texte(Colonne.INDICATION.intitules);
        lue.registreBloc = ligne.texte(Colonne.REGISTRE_BLOC.intitules);
        lue.dateIntervention = ligne.texte(Colonne.DATE_INTERVENTION.intitules);
        lue.prestation = ligne.texte(Colonne.PRESTATION.intitules);
        lue.code = ligne.texte(Colonne.CODE.intitules);
        if (lue.porteVisite()) {
            lue.anomalieVisite = lireVisite(ligne, lue, regime, mois, annee);
        }
        try {
            if (ligne.texte(Colonne.QUANTITE.intitules) != null) {
                lue.quantite = ligne.entier(Colonne.QUANTITE.intitules);
            }
            if (ligne.texte(Colonne.PRIX.intitules) != null) {
                lue.prix = ligne.nombre(Colonne.PRIX.intitules);
            }
            if (ligne.texte(Colonne.DUREE.intitules) != null) {
                lue.duree = ligne.entier(Colonne.DUREE.intitules);
            }
        } catch (ExceptionMetier e) {
            lue.anomaliePrestation = sansPrefixe(e.getMessage());
            return lue;
        }
        if (lue.prix == null) {
            lue.anomaliePrestation = "prix unitaire manquant.";
        } else if (lue.prix < 0) {
            lue.anomaliePrestation = "prix unitaire négatif.";
        } else if (lue.quantite <= 0) {
            lue.anomaliePrestation = "quantité nulle ou négative.";
        }
        return lue;
    }

    /** Identifiant et date de la visite ; le motif qui les rend inexploitables, ou {@code null}. */
    private static String lireVisite(LigneImport ligne, Lue lue, Regime regime, int mois, int annee) {
        String libelle = CleRapprochement.libelleIdentifiant(regime);
        lue.cle = CleRapprochement.normaliser(lue.cleBrute);
        if (lue.cle == null) {
            return libelle + " manquant.";
        }
        if (!CleRapprochement.formatValide(regime, lue.cle)) {
            boolean cni = regime == Regime.SESAME || regime == Regime.CESARIENNE || regime == Regime.ZERO_CINQ_ANS;
            return libelle + " « " + lue.cleBrute + " » au format invalide" + (cni ? " (chiffres uniquement)." : ".");
        }
        try {
            lue.date = ligne.date(Colonne.DATE.intitules);
            lue.naissance = ligne.date(Colonne.NAISSANCE.intitules);
        } catch (ExceptionMetier e) {
            return sansPrefixe(e.getMessage());
        }
        if (lue.date == null) {
            return "date de prise en charge manquante.";
        }
        if (lue.date.getMonthValue() != mois || lue.date.getYear() != annee) {
            return "date de prise en charge du " + lue.date.format(JJ_MM_AAAA) + " hors du mois de "
                    + FactureStructure.MOIS[mois] + " " + annee + ".";
        }
        if (lue.date.isAfter(LocalDate.now())) {
            return "date de prise en charge du " + lue.date.format(JJ_MM_AAAA) + " dans le futur.";
        }
        return null;
    }

    /** Clé de la visite : identifiant et date, et pour un enfant ce qui le distingue de ses frères et sœurs. */
    private static String cleVisite(Regime regime, Lue lue) {
        String cle = lue.cle + "|" + lue.date;
        if (regime != Regime.ZERO_CINQ_ANS) {
            return cle;
        }
        String extrait = CleRapprochement.normaliser(lue.extrait);
        return cle + "|" + (extrait != null ? "E" + extrait
                : "N" + lue.naissance + "|" + LecteurExcel.normaliser(lue.prenom));
    }

    /** Prestation du fichier : la visite et l'acte ; l'identité sera celle du dossier. */
    private static LigneFactureStructure prestation(Regime regime, Lue lue) {
        boolean enfant = regime == Regime.ZERO_CINQ_ANS;
        boolean cesarienne = regime == Regime.CESARIENNE;
        return LigneFactureStructure.builder()
                .datePriseEnCharge(lue.date)
                .designation(lue.prestation)
                .codeActe(lue.code)
                .quantite(lue.quantite)
                .prixUnitaire(lue.prix == null ? 0 : lue.prix)
                .service(lue.service)
                .numeroRegistre(enfant ? lue.registre : null)
                .indicationCbt(cesarienne ? lue.indication : null)
                .numeroRegistreBloc(cesarienne ? lue.registreBloc : null)
                .dateHeureIntervention(cesarienne ? lue.dateIntervention : null)
                .dureeHospitalisationJours(cesarienne ? lue.duree : null)
                .build();
    }

    /** Le contrôle d'âge des règles de facturation, appliqué d'avance : un refus n'écarte que ce patient. */
    private String controlerAge(Regime regime, DossierPatient dossier, LocalDate date) {
        try {
            regles.controlerAge(regime, LigneFactureStructure.builder()
                    .patientNom(dossier.getNom())
                    .patientPrenom(dossier.getPrenom())
                    .patientDateNaissance(dossier.getDateNaissance())
                    .datePriseEnCharge(date)
                    .build());
            return null;
        } catch (ExceptionMetier e) {
            return e.getMessage();
        }
    }

    /** Patients enregistrés par le BCSU : ceux que l'import complète. */
    private static List<DossierPatient> dossiersBcsu(FactureStructure facture) {
        return (facture.getDossiers() == null ? List.<DossierPatient>of() : facture.getDossiers()).stream()
                .filter(d -> d.getLettreGarantieId() != null && d.getDatePriseEnCharge() != null)
                .toList();
    }

    private static List<LigneFactureStructure> lignesDu(FactureStructure facture, String dossierId) {
        if (facture == null || facture.getLignes() == null) {
            return List.of();
        }
        return facture.getLignes().stream().filter(l -> dossierId.equals(l.getDossierId())).toList();
    }

    private static boolean importee(LigneFactureStructure ligne) {
        return LigneFactureStructure.ORIGINE_IMPORT.equals(ligne.getOrigine());
    }

    /** Identifiant tel que le BCSU l'a saisi (la clé normalisée à défaut). */
    private static String identifiantAffiche(Regime regime, DossierPatient dossier) {
        String saisi = switch (regime) {
            case ZERO_CINQ_ANS -> dossier.getCniAccompagnant();
            case SESAME, CESARIENNE -> dossier.getNumeroCni();
            default -> dossier.getMatricule();
        };
        return saisi != null && !saisi.isBlank() ? saisi : dossier.getCleRapprochement();
    }

    private static List<Integer> numeros(List<Lue> lignes) {
        return lignes.stream().map(l -> l.numero).toList();
    }

    private static void anomalie(RapportRapprochement rapport, int ligne, String motif) {
        rapport.getAnomalies().add(new Anomalie(ligne, motif));
    }

    private static String sansPrefixe(String message) {
        return message == null ? "valeur illisible." : PREFIXE_LIGNE.matcher(message).replaceFirst("");
    }

    // ══════════════════════════ modèle ══════════════════════════

    /**
     * Modèle d'import d'un régime pour un mois : une ligne par patient enregistré par le BCSU
     * (par prestation déjà importée, s'il y en a — le fichier se corrige et se réimporte), puis
     * des lignes libres pour les prestations supplémentaires.
     */
    public byte[] modele(Regime regime, int mois, int annee) {
        controler(regime, mois, annee);
        FactureStructure facture = depot.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(
                ContexteUtilisateur.structureCourante(), regime, mois, annee).orElse(null);
        List<DossierPatient> patients = facture == null ? List.of() : dossiersBcsu(facture).stream()
                .sorted(Comparator.comparing(DossierPatient::getDatePriseEnCharge)
                        .thenComparing(DossierPatient::nomComplet))
                .toList();
        List<Colonne> colonnes = colonnes(regime);

        try (XSSFWorkbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Styles styles = new Styles(classeur);
            Sheet feuille = classeur.createSheet("Prestations");
            Row entete = feuille.createRow(0);
            entete.setHeightInPoints(32);
            for (int i = 0; i < colonnes.size(); i++) {
                String intitule = colonnes.get(i).intitule(regime);
                Cell cellule = entete.createCell(i);
                cellule.setCellValue(intitule);
                cellule.setCellStyle(styles.entete);
                feuille.setColumnWidth(i, Math.min(42, Math.max(12, intitule.length() + 4)) * 256);
            }

            int r = 1;
            int ordre = 1;
            for (DossierPatient dossier : patients) {
                List<LigneFactureStructure> importees = lignesDu(facture, dossier.getId()).stream()
                        .filter(ServiceImportPrestations::importee).toList();
                if (importees.isEmpty()) {
                    ecrire(feuille.createRow(r++), colonnes, regime, dossier, null, ordre, styles);
                } else {
                    for (LigneFactureStructure ligne : importees) {
                        ecrire(feuille.createRow(r++), colonnes, regime, dossier, ligne, ordre, styles);
                    }
                }
                ordre++;
            }
            for (int k = 0; k < LIGNES_LIBRES; k++) {
                ecrire(feuille.createRow(r++), colonnes, regime, null, null, 0, styles);
            }
            feuille.createFreezePane(0, 1);

            modeDEmploi(classeur.createSheet("Mode d'emploi"), regime, mois, annee, patients.size(), styles);
            classeur.write(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw ExceptionMetier.invalide("Génération du modèle impossible : " + e.getMessage());
        }
    }

    /** Une ligne du modèle : le patient du BCSU (grisé) et, s'il y en a une, sa prestation importée. */
    private static void ecrire(Row row, List<Colonne> colonnes, Regime regime, DossierPatient dossier,
                               LigneFactureStructure ligne, int ordre, Styles styles) {
        int indexQuantite = colonnes.indexOf(Colonne.QUANTITE);
        int indexPrix = colonnes.indexOf(Colonne.PRIX);
        for (int i = 0; i < colonnes.size(); i++) {
            Colonne colonne = colonnes.get(i);
            Cell cellule = row.createCell(i);
            boolean grise = dossier != null && colonne.duBcsu();
            cellule.setCellStyle(styles.pour(colonne, grise));
            switch (colonne) {
                case ORDRE -> {
                    if (ordre > 0) cellule.setCellValue(ordre);
                }
                case CLE -> texte(cellule, dossier == null ? null : identifiantAffiche(regime, dossier));
                case DATE -> date(cellule, dossier == null ? null : dossier.getDatePriseEnCharge());
                case PRENOM -> texte(cellule, dossier == null ? null : dossier.getPrenom());
                case NOM -> texte(cellule, dossier == null ? null : dossier.getNom());
                case EXTRAIT -> texte(cellule, dossier == null ? null : dossier.getMatricule());
                case NAISSANCE -> date(cellule, dossier == null ? null : dossier.getDateNaissance());
                case REGISTRE -> texte(cellule, ligne != null && ligne.getNumeroRegistre() != null
                        ? ligne.getNumeroRegistre() : dossier == null ? null : dossier.getNumeroRegistre());
                case SERVICE -> texte(cellule, ligne == null ? null : ligne.getService());
                case INDICATION -> texte(cellule, ligne == null ? null : ligne.getIndicationCbt());
                case REGISTRE_BLOC -> texte(cellule, ligne == null ? null : ligne.getNumeroRegistreBloc());
                case DATE_INTERVENTION -> texte(cellule, ligne == null ? null : ligne.getDateHeureIntervention());
                case DUREE -> {
                    if (ligne != null && ligne.getDureeHospitalisationJours() != null) {
                        cellule.setCellValue(ligne.getDureeHospitalisationJours());
                    }
                }
                case PRESTATION -> texte(cellule, ligne == null ? null : ligne.getDesignation());
                case CODE -> texte(cellule, ligne == null ? null : ligne.getCodeActe());
                case QUANTITE -> {
                    if (ligne != null) cellule.setCellValue(ligne.getQuantite());
                }
                case PRIX -> {
                    if (ligne != null) cellule.setCellValue(ligne.getPrixUnitaire());
                }
                case MONTANT -> {
                    // Quantité vide = 1, comme à l'import ; rien tant que le prix est vide.
                    String q = CellReference.convertNumToColString(indexQuantite) + (row.getRowNum() + 1);
                    String p = CellReference.convertNumToColString(indexPrix) + (row.getRowNum() + 1);
                    cellule.setCellFormula("IF(" + p + "=\"\",\"\",IF(" + q + "=\"\",1," + q + ")*" + p + ")");
                }
            }
        }
    }

    private static void texte(Cell cellule, String valeur) {
        if (valeur != null && !valeur.isBlank()) {
            cellule.setCellValue(valeur);
        }
    }

    private static void date(Cell cellule, LocalDate valeur) {
        if (valeur != null) {
            cellule.setCellValue(valeur);
        }
    }

    private static void modeDEmploi(Sheet aide, Regime regime, int mois, int annee, int patients, Styles styles) {
        String periode = FactureStructure.MOIS[mois] + " " + annee;
        List<String> textes = new ArrayList<>();
        textes.add(patients > 0
                ? patients + " patient(s) enregistré(s) par le BCSU en " + periode + " sont déjà listés (colonnes grisées) : "
                + "complétez pour chacun la prestation, la quantité et le prix unitaire."
                : "Aucun patient n'a encore été enregistré par le BCSU en " + periode + " pour ce régime.");
        textes.add("Une ligne par prestation. Plusieurs prestations pour une même visite : ajoutez des lignes sous le "
                + "patient, en recopiant l'identifiant et la date — ou en les laissant vides : la ligne prolonge alors "
                + "la visite du dessus.");
        textes.add("Identifiant : « " + intitulesCle(regime)[0] + " ». Espaces et tirets sont ignorés.");
        if (regime == Regime.ZERO_CINQ_ANS) {
            textes.add("Deux enfants du même accompagnant vus le même jour : le n° d'extrait de naissance (à défaut, la "
                    + "date de naissance et le prénom) les distingue.");
        }
        textes.add("Dates au format jj/mm/aaaa, toutes en " + periode + ".");
        textes.add("Quantité vide = 1. Le montant se calcule tout seul ; la part CSU est calculée par l'application "
                + "selon le régime.");
        textes.add("Ne modifiez pas la première ligne (les en-têtes) et laissez les prestations sur la première feuille.");
        textes.add("L'import montre d'abord un aperçu : patients rapprochés, lignes sans correspondance, anomalies. "
                + "Rien n'est enregistré avant votre confirmation.");
        textes.add("Réimporter un fichier corrigé remplace les prestations déjà importées des patients qu'il contient. "
                + "Les patients absents du fichier gardent les leurs ; les prestations saisies à l'écran ne sont jamais touchées.");

        Row titre = aide.createRow(0);
        titre.createCell(0).setCellValue("Import de fin de mois — " + regime.getLibelle() + " — " + periode);
        titre.getCell(0).setCellStyle(styles.titre);
        int r = 2;
        for (String texte : textes) {
            Cell cellule = aide.createRow(r++).createCell(0);
            cellule.setCellValue("• " + texte);
            cellule.setCellStyle(styles.aide);
        }
        aide.setColumnWidth(0, 130 * 256);
    }

    /** Styles du modèle, créés une fois par classeur. */
    private static final class Styles {
        final XSSFCellStyle entete;
        final XSSFCellStyle titre;
        final XSSFCellStyle aide;
        private final Map<String, XSSFCellStyle> cases = new HashMap<>();
        private final XSSFWorkbook classeur;

        Styles(XSSFWorkbook classeur) {
            this.classeur = classeur;
            XSSFColor vert = new XSSFColor(new byte[]{(byte) 47, (byte) 110, (byte) 84}, null);
            XSSFFont blanche = classeur.createFont();
            blanche.setBold(true);
            blanche.setFontHeightInPoints((short) 10);
            blanche.setColor(IndexedColors.WHITE.getIndex());
            entete = bordee();
            entete.setFont(blanche);
            entete.setFillForegroundColor(vert);
            entete.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            entete.setAlignment(HorizontalAlignment.CENTER);
            entete.setVerticalAlignment(VerticalAlignment.CENTER);
            entete.setWrapText(true);

            XSSFFont grande = classeur.createFont();
            grande.setBold(true);
            grande.setFontHeightInPoints((short) 13);
            titre = classeur.createCellStyle();
            titre.setFont(grande);
            aide = classeur.createCellStyle();
            aide.setWrapText(true);
            aide.setVerticalAlignment(VerticalAlignment.TOP);
        }

        /**
         * Style d'une case : grisée pour les données du BCSU, au format de la colonne (texte pour
         * les identifiants — une CNI de 13 chiffres tournerait sinon en notation scientifique —,
         * date, montant).
         */
        XSSFCellStyle pour(Colonne colonne, boolean grise) {
            String format = switch (colonne) {
                case CLE, EXTRAIT, REGISTRE, REGISTRE_BLOC, CODE -> "@";
                case DATE, NAISSANCE -> "dd/mm/yyyy";
                case PRIX, MONTANT -> "#,##0";
                default -> null;
            };
            return cases.computeIfAbsent(format + "|" + grise, k -> {
                XSSFCellStyle style = bordee();
                if (format != null) {
                    style.setDataFormat(classeur.createDataFormat().getFormat(format));
                }
                if (grise) {
                    style.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 236, (byte) 239, (byte) 241}, null));
                    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                }
                return style;
            });
        }

        private XSSFCellStyle bordee() {
            XSSFCellStyle style = classeur.createCellStyle();
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            return style;
        }
    }
}
