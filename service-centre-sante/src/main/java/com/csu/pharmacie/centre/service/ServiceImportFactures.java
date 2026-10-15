package com.csu.pharmacie.centre.service;

import com.csu.pharmacie.centre.dto.ResultatImportFactures;
import com.csu.pharmacie.centre.dto.ResultatImportFactures.FactureReconnue;
import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.facturation.DepotFactureStructure;
import com.csu.pharmacie.commun.facturation.Numerotation;
import com.csu.pharmacie.commun.facturation.ReglesFacturation;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.HistoriqueDossier;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.utils.ColonnesFactureStructure;
import com.csu.pharmacie.utils.ColonnesFactureStructure.Colonne;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reprise des factures déjà générées par l'application utilisée avant la bascule.
 *
 * <p><b>Un fichier = une facture</b>, au format de la facture Excel du régime — celui que
 * l'application exporte ({@link ColonnesFactureStructure#pourRegime}) : en-tête de la
 * structure, titre du régime, « N° Référence facture », puis le tableau. Le Service Régional
 * choisit à l'écran la structure, le régime, la période et le statut d'origine ; le fichier
 * n'apporte que les patients et leurs prestations.</p>
 *
 * <ul>
 *   <li><b>Lecture tolérante</b> : la ligne d'en-têtes est cherchée (dans toutes les feuilles —
 *       le classeur officiel a un onglet par régime) ; les cellules d'identité fusionnées sur
 *       les prestations d'une visite, comme dans la facture exportée, valent pour tout le bloc ;
 *       une ligne sans prénom ni nom prolonge la visite du dessus ; la lecture s'arrête aux
 *       « TOTAUX ».</li>
 *   <li><b>Statut d'origine conservé</b>, hors circuit et hors clôture mensuelle automatique
 *       ({@link FactureStructure#getImportee()}).</li>
 *   <li><b>Montants</b> : prix et quantités du fichier, parts recalculées par les règles de
 *       facturation (taux du régime, forfait 0-5 ans) comme pour toute facture.</li>
 *   <li><b>Tout ou rien</b>, et une facture déjà présente pour la même structure, le même
 *       régime et le même mois est refusée : réimporter un fichier corrigé est sans danger.</li>
 *   <li>Aucune sortie de stock ni mise à jour de feuille de soins : factures passées.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ServiceImportFactures {

    private static final String PREFIXE_NUMERO = "FS";
    /** Lignes vides proposées sous les en-têtes du modèle. */
    private static final int LIGNES_MODELE = 30;
    /** Les en-têtes sont cherchés dans les premières lignes de chaque feuille. */
    private static final int LIGNES_EXPLOREES = 60;

    /** Donnée d'une colonne de la facture, quel que soit son intitulé selon le régime. */
    enum Champ {
        // Patient et visite : repris d'une ligne à l'autre (cellules fusionnées ou laissées vides).
        REGISTRE, PRENOM, NOM, NAISSANCE, SEXE, TELEPHONE, ADRESSE, MATRICULE, CNI, IRC,
        DATE_SOINS, SERVICE, INDICATION, REGISTRE_BLOC, DATE_INTERVENTION, DUREE,
        // Propres à chaque prestation.
        PRESTATION, QUANTITE, PRIX, MONTANT, FORFAIT;

        boolean deLaVisite() {
            return ordinal() <= DUREE.ordinal();
        }
    }

    /** Intitulés de colonnes reconnus (normalisés), ceux des modèles officiels en premier. */
    private static final Map<String, Champ> CHAMPS = new HashMap<>();
    /**
     * Colonnes connues mais sans donnée à reprendre : numéro d'ordre, et diagnostic (retiré de
     * l'application le 15/10/2026, encore présent dans les factures exportées avant).
     */
    private static final Set<String> SANS_DONNEE = Set.of("n", "no", "numero", "ndordre", "numerodordre", "ordre",
            "diagnosticmotifdeconsultation", "diagnostic", "diagnostique", "motifdeconsultation");

    static {
        champ(Champ.REGISTRE, "N° dans le registre", "N° registre", "Numéro registre", "N° de registre");
        champ(Champ.PRENOM, "Prénom(s)", "Prénom", "Prénoms");
        champ(Champ.NOM, "Nom", "Nom de famille");
        champ(Champ.NAISSANCE, "Date de naissance", "Né le", "Née le");
        champ(Champ.SEXE, "Sexe (H/F)", "Sexe (M/F)", "Sexe");
        champ(Champ.TELEPHONE, "N° Tél", "N° Téléphone", "Téléphone", "Tél");
        champ(Champ.ADRESSE, "Adresse");
        champ(Champ.MATRICULE, "N° Matricule / Code Bénéficiaire",
                "N° Matricule / N° Extrait de naissance / N° accompagnant",
                "N° Matricule / N° CNI Patient / N° accompagnant", "N° Matricule / CNI", "N° Matricule",
                "Matricule", "Code d'immatriculation", "Code bénéficiaire");
        champ(Champ.CNI, "N° CNI", "CNI", "Numéro CNI");
        champ(Champ.IRC, "IRC/IRA");
        champ(Champ.DATE_SOINS, "Date de prise en charge", "Date des soins", "Date de soins", "Date de consultation");
        champ(Champ.SERVICE, "Service");
        champ(Champ.INDICATION, "Indication / Motif de CBT", "Indication", "Motif de CBT");
        champ(Champ.REGISTRE_BLOC, "N° Registre Bloc opératoire", "N° registre bloc");
        champ(Champ.DATE_INTERVENTION, "Date et Heure Intervention", "Date intervention");
        champ(Champ.DUREE, "Durée Hospitalisation (jours)", "Durée hospitalisation");
        champ(Champ.PRESTATION, "Prestation(s)", "Prestations et médicaments", "Prestation", "Prestations",
                "Désignation", "Acte");
        champ(Champ.QUANTITE, "Quantité", "Nbre de Poches", "Nbre de Séances", "Qté");
        champ(Champ.PRIX, "P.U", "Prix Unitaire", "Prix unitaire (FCFA)");
        champ(Champ.MONTANT, "Montant Total", "Prix Total", "Montant facturé à la SEN-CSU", "Montant",
                "Montant (FCFA)");
        champ(Champ.FORFAIT, "Forfait");
    }

    private static final DateTimeFormatter JJ_MM_AAAA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter[] FORMATS_DATE = {
            JJ_MM_AAAA, DateTimeFormatter.ofPattern("d/M/yyyy"), DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"), DateTimeFormatter.ofPattern("d/M/yy")
    };
    private static final Pattern DATE_DANS_TEXTE = Pattern.compile("\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}");
    private static final DataFormatter FORMATEUR = new DataFormatter(Locale.FRANCE);

    private final DepotFactureStructure depot;
    private final ReglesFacturation regles;
    private final ClientIdentite clientIdentite;
    private final Numerotation numerotation;

    /**
     * La facture que représente le fichier, choisie à l'écran. {@code numeroOrigine} facultatif :
     * à défaut, celui de la ligne « N° Référence facture » du fichier.
     */
    public record Cible(String structureId, Regime regime, int mois, int annee, StatutFacture statut,
                        String numeroOrigine) {
    }

    // ══════════════════════════ import ══════════════════════════

    /**
     * @param simulation contrôle et aperçu seulement, rien n'est enregistré : l'écran montre
     *                   d'abord ce que le fichier va produire, puis l'agent confirme.
     */
    @Transactional
    public ResultatImportFactures importer(MultipartFile fichier, Cible cible, boolean simulation) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.exigerRole(Role.SERVICE_REGIONAL, Role.ADMIN);
        controler(cible);
        if (fichier == null || fichier.isEmpty()) {
            throw ExceptionMetier.invalide("Aucun fichier reçu.");
        }
        StructureDto structure = clientIdentite.structure(cible.structureId());
        exigerPerimetre(utilisateur, structure);

        ResultatImportFactures resultat = new ResultatImportFactures();
        resultat.setStructureId(structure.getId());
        resultat.setStructureNom(structure.getNom());

        byte[] contenu;
        try {
            contenu = fichier.getBytes();
        } catch (IOException e) {
            throw ExceptionMetier.invalide("Lecture du fichier impossible : " + e.getMessage());
        }
        Lecture lecture = lire(contenu, cible.regime(), resultat);
        if (lecture == null) {
            return resultat;
        }
        resultat.setLignesLues(lecture.lignes().size());
        if (lecture.lignes().isEmpty()) {
            resultat.erreur("Aucune prestation sous les en-têtes du tableau.");
            return resultat;
        }

        String libelle = libelle(cible);
        if (depot.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(
                structure.getId(), cible.regime(), cible.mois(), cible.annee()).isPresent()) {
            resultat.erreur("« " + structure.getNom() + " » a déjà une facture " + libelle + " dans l'application : "
                    + "elle n'est pas réimportée (peut-être déjà importée ?).");
        }

        List<PrestationLue> prestations = convertir(lecture.lignes(), cible, resultat);
        String numeroOrigine = nonVide(cible.numeroOrigine()) != null
                ? cible.numeroOrigine().trim() : lecture.reference();
        Construite construite = construire(cible, numeroOrigine, prestations, structure, utilisateur);
        calculer(construite, libelle, resultat);
        if (cible.statut().estModifiable()) {
            resultat.avertir("Facture importée « " + cible.statut().getLibelle() + " » : elle reste modifiable par "
                    + "la structure, qui l'enverra elle-même (pas d'envoi automatique à la clôture du mois).");
        }

        // Tout ou rien — et rien du tout en simulation.
        if (resultat.enErreur() || simulation) {
            resultat.getFactures().add(reconnue(construite.facture()));
            return resultat;
        }
        FactureStructure facture = construite.facture();
        facture.setNumero(numerotation.prochainNumero(PREFIXE_NUMERO, facture.getAnnee(), facture.getMois()));
        FactureStructure enregistree = depot.save(facture);
        resultat.getFactures().add(reconnue(enregistree));
        resultat.setFacturesCreees(1);
        resultat.setPatients(enregistree.getDossiers().size());
        resultat.setMontantTotal(enregistree.getMontantTotal());
        return resultat;
    }

    private static void controler(Cible cible) {
        if (cible.regime() == null) throw ExceptionMetier.invalide("Choisissez le régime de la facture.");
        if (cible.statut() == null) throw ExceptionMetier.invalide("Choisissez le statut d'origine de la facture.");
        if (cible.mois() < 1 || cible.mois() > 12) throw ExceptionMetier.invalide("Mois invalide : " + cible.mois());
        if (cible.annee() < 2000 || cible.annee() > 2100) throw ExceptionMetier.invalide("Année invalide : " + cible.annee());
    }

    /** Le Service Régional n'agit que pour les structures de sa région. */
    private static void exigerPerimetre(UtilisateurAuthentifie utilisateur, StructureDto structure) {
        if (utilisateur.getRole() != Role.SERVICE_REGIONAL) {
            return;
        }
        if (utilisateur.getRegionId() == null || !utilisateur.getRegionId().equals(structure.getRegionId())) {
            throw ExceptionMetier.interdit("« " + structure.getNom() + " » n'appartient pas à votre région.");
        }
    }

    private static String libelle(Cible cible) {
        return "« " + cible.regime().getLibelle() + " » de " + FactureStructure.MOIS[cible.mois()] + " " + cible.annee();
    }

    // ══════════════════════════ lecture du classeur ══════════════════════════

    /** Une ligne du tableau : ses valeurs par donnée, et son n° de ligne Excel. */
    record LigneTableau(int numero, Map<Champ, String> valeurs) {
        String get(Champ champ) {
            return nonVide(valeurs.get(champ));
        }
    }

    private record Lecture(List<LigneTableau> lignes, String reference) {
    }

    /** {@code null} si le fichier est inexploitable (l'erreur est alors consignée). */
    private Lecture lire(byte[] contenu, Regime regime, ResultatImportFactures resultat) {
        try (Workbook classeur = WorkbookFactory.create(new ByteArrayInputStream(contenu))) {
            FormulaEvaluator evaluateur = classeur.getCreationHelper().createFormulaEvaluator();
            Set<Champ> attendus = attendus(regime);

            // 1. Le tableau : la ligne d'en-têtes qui reconnaît le mieux les colonnes du régime.
            Sheet feuille = null;
            int ligneEntete = -1;
            Map<Integer, Champ> colonnes = new LinkedHashMap<>();
            List<String> inconnues = new ArrayList<>();
            int meilleurRegime = -1;
            for (Sheet candidate : classeur) {
                int fin = Math.min(candidate.getLastRowNum(), candidate.getFirstRowNum() + LIGNES_EXPLOREES);
                for (int i = candidate.getFirstRowNum(); i <= fin; i++) {
                    Row ligne = candidate.getRow(i);
                    if (ligne == null) continue;
                    Map<Integer, Champ> trouvees = new LinkedHashMap<>();
                    List<String> autres = new ArrayList<>();
                    for (Cell cellule : ligne) {
                        String entete = texte(cellule, evaluateur);
                        Champ champ = CHAMPS.get(cle(entete));
                        if (champ != null && !trouvees.containsValue(champ)) {
                            trouvees.put(cellule.getColumnIndex(), champ);
                        } else if (champ == null && !entete.isBlank() && !SANS_DONNEE.contains(cle(entete))) {
                            autres.add(entete);
                        }
                    }
                    if (!trouvees.containsValue(Champ.PRENOM) || !trouvees.containsValue(Champ.NOM)) continue;
                    int scoreRegime = (int) trouvees.values().stream().filter(attendus::contains).count();
                    if (scoreRegime > meilleurRegime
                            || (scoreRegime == meilleurRegime && trouvees.size() > colonnes.size())) {
                        meilleurRegime = scoreRegime;
                        feuille = candidate;
                        ligneEntete = i;
                        colonnes = trouvees;
                        inconnues = autres;
                    }
                }
            }
            if (feuille == null) {
                resultat.erreur("Tableau introuvable : aucune ligne d'en-têtes avec « Prénom(s) » et « Nom ». "
                        + "Utilisez le modèle « " + ColonnesFactureStructure.titreCourt(regime) + " ».");
                return null;
            }

            // 2. Le bon modèle ?
            Set<Champ> presents = EnumSet.copyOf(colonnes.values());
            for (Regime autre : Regime.values()) {
                Set<Champ> siens = attendus(autre);
                if (!siens.equals(attendus) && presents.equals(siens)) {
                    resultat.erreur("Ce tableau est le modèle « " + ColonnesFactureStructure.titreLong(autre)
                            + " », pas celui du régime choisi (« " + regime.getLibelle()
                            + " »). Choisissez le bon régime ou le bon fichier.");
                    return null;
                }
            }
            List<String> manquantes = new ArrayList<>();
            List<String> facultatives = new ArrayList<>();
            for (Colonne colonne : ColonnesFactureStructure.pourRegime(regime)) {
                Champ champ = CHAMPS.get(cle(colonne.entete()));
                if (champ == null || presents.contains(champ)) continue;
                if (champ == Champ.PRENOM || champ == Champ.NOM || champ == Champ.PRESTATION || champ == Champ.DATE_SOINS) {
                    manquantes.add(colonne.entete());
                } else if (champ != Champ.FORFAIT && champ != Champ.MONTANT) {
                    facultatives.add(colonne.entete());
                }
            }
            if (!manquantes.isEmpty()) {
                manquantes.forEach(c -> resultat.erreur("Colonne « " + c + " » absente du tableau (modèle « "
                        + ColonnesFactureStructure.titreCourt(regime) + " »)."));
                return null;
            }
            if (!facultatives.isEmpty()) {
                resultat.avertir("Colonnes du modèle absentes du fichier, laissées vides : "
                        + String.join(", ", facultatives) + ".");
            }
            if (!inconnues.isEmpty()) {
                resultat.avertir("Colonnes non reconnues, ignorées : " + String.join(", ", inconnues) + ".");
            }

            // 3. « N° Référence facture : … » au-dessus du tableau.
            String reference = reference(feuille, ligneEntete, evaluateur);

            // 4. Cellules fusionnées du tableau : la valeur de la cellule du haut vaut pour tout le bloc.
            Map<Long, String> fusions = new HashMap<>();
            for (CellRangeAddress zone : feuille.getMergedRegions()) {
                if (zone.getFirstRow() <= ligneEntete) continue;
                Row haut = feuille.getRow(zone.getFirstRow());
                String valeur = haut == null ? "" : texte(haut.getCell(zone.getFirstColumn()), evaluateur);
                for (int r = zone.getFirstRow(); r <= zone.getLastRow(); r++) {
                    for (int c = zone.getFirstColumn(); c <= zone.getLastColumn(); c++) {
                        fusions.put(position(r, c), valeur);
                    }
                }
            }

            // 5. Les lignes, jusqu'aux totaux.
            List<LigneTableau> lignes = new ArrayList<>();
            Map<Champ, String> precedente = null;
            for (int i = ligneEntete + 1; i <= feuille.getLastRowNum(); i++) {
                Row ligne = feuille.getRow(i);
                if (ligne == null) continue;
                if (finDuTableau(ligne, evaluateur)) break;
                Map<Champ, String> valeurs = new EnumMap<>(Champ.class);
                for (Map.Entry<Integer, Champ> colonne : colonnes.entrySet()) {
                    String valeur = texte(ligne.getCell(colonne.getKey()), evaluateur);
                    if (valeur.isBlank() && colonne.getValue().deLaVisite()) {
                        valeur = fusions.getOrDefault(position(i, colonne.getKey()), "");
                    }
                    if (!valeur.isBlank()) valeurs.put(colonne.getValue(), valeur.trim());
                }
                // Colonnes de montant seules (bas d'un bloc fusionné) : rien à reprendre.
                if (valeurs.keySet().stream().allMatch(c -> c == Champ.MONTANT || c == Champ.FORFAIT)) continue;
                if (!valeurs.containsKey(Champ.PRENOM) && !valeurs.containsKey(Champ.NOM)) {
                    // Suite de la visite du dessus (identité laissée vide).
                    if (precedente == null) {
                        resultat.erreur(i + 1, "prénom et nom manquants.");
                        continue;
                    }
                    for (Map.Entry<Champ, String> e : precedente.entrySet()) {
                        if (e.getKey().deLaVisite()) valeurs.putIfAbsent(e.getKey(), e.getValue());
                    }
                }
                lignes.add(new LigneTableau(i + 1, valeurs));
                precedente = valeurs;
            }
            return new Lecture(lignes, reference);
        } catch (ExceptionMetier e) {
            throw e;
        } catch (Exception e) {
            resultat.erreur("Fichier Excel illisible (.xlsx ou .xls attendu) : " + e.getMessage());
            return null;
        }
    }

    /** Données attendues par le modèle d'un régime. */
    static Set<Champ> attendus(Regime regime) {
        Set<Champ> champs = EnumSet.noneOf(Champ.class);
        ColonnesFactureStructure.pourRegime(regime).forEach(c -> {
            Champ champ = CHAMPS.get(cle(c.entete()));
            if (champ != null) champs.add(champ);
        });
        return champs;
    }

    private static String reference(Sheet feuille, int ligneEntete, FormulaEvaluator evaluateur) {
        for (int i = feuille.getFirstRowNum(); i < ligneEntete; i++) {
            Row ligne = feuille.getRow(i);
            if (ligne == null) continue;
            boolean apresLibelle = false;
            for (Cell cellule : ligne) {
                String valeur = texte(cellule, evaluateur);
                if (apresLibelle && !valeur.isBlank()) return valeur.trim();
                if (cle(valeur).startsWith("nreferencefacture")) {
                    int deuxPoints = valeur.indexOf(':');
                    String suite = deuxPoints < 0 ? "" : valeur.substring(deuxPoints + 1).trim();
                    if (!suite.isBlank()) return suite;
                    apresLibelle = true;
                }
            }
        }
        return null;
    }

    /** Ligne des totaux, encadré « montant à régler » ou phrase d'arrêt : le tableau est fini. */
    private static boolean finDuTableau(Row ligne, FormulaEvaluator evaluateur) {
        for (Cell cellule : ligne) {
            String c = cle(texte(cellule, evaluateur));
            if (c.equals("total") || c.startsWith("totaux") || c.startsWith("montantaregler")
                    || c.startsWith("arreteelapresente")) {
                return true;
            }
        }
        return false;
    }

    private static long position(int ligne, int colonne) {
        return ((long) ligne << 20) | colonne;
    }

    /** Valeur d'une cellule en texte : dates en jj/mm/aaaa, nombres sans séparateur, formules calculées. */
    private static String texte(Cell cellule, FormulaEvaluator evaluateur) {
        if (cellule == null) return "";
        CellType type = cellule.getCellType();
        if (type == CellType.FORMULA) {
            try {
                CellValue calcule = evaluateur.evaluate(cellule);
                if (calcule == null) return "";
                return switch (calcule.getCellType()) {
                    case NUMERIC -> DateUtil.isCellDateFormatted(cellule)
                            ? DateUtil.getLocalDateTime(calcule.getNumberValue()).toLocalDate().format(JJ_MM_AAAA)
                            : nombre(calcule.getNumberValue());
                    case STRING -> calcule.getStringValue().trim();
                    case BOOLEAN -> String.valueOf(calcule.getBooleanValue());
                    default -> "";
                };
            } catch (RuntimeException e) {
                return FORMATEUR.formatCellValue(cellule).trim();
            }
        }
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cellule)
                    ? cellule.getLocalDateTimeCellValue().toLocalDate().format(JJ_MM_AAAA)
                    : nombre(cellule.getNumericCellValue());
            case STRING -> cellule.getStringCellValue().trim();
            case BOOLEAN -> String.valueOf(cellule.getBooleanCellValue());
            default -> "";
        };
    }

    private static String nombre(double valeur) {
        return BigDecimal.valueOf(valeur).stripTrailingZeros().toPlainString();
    }

    // ══════════════════════════ contrôle des prestations ══════════════════════════

    /** Une prestation contrôlée : sa ligne de facture, sa visite et son n° de ligne Excel. */
    private record PrestationLue(int numero, String cleVisite, LigneFactureStructure ligne) {
    }

    private List<PrestationLue> convertir(List<LigneTableau> lignes, Cible cible, ResultatImportFactures resultat) {
        Regime regime = cible.regime();
        Set<Champ> attendus = attendus(regime);
        boolean montantParPrestation = montantParPrestation(regime);
        LocalDate debut = LocalDate.of(cible.annee(), cible.mois(), 1);
        List<Integer> sansPrix = new ArrayList<>();
        List<Integer> horsPeriode = new ArrayList<>();
        List<Integer> sexeIllisible = new ArrayList<>();
        List<PrestationLue> prestations = new ArrayList<>();

        for (LigneTableau l : lignes) {
            int n = l.numero();
            List<String> problemes = new ArrayList<>();

            String prenom = l.get(Champ.PRENOM);
            String nom = l.get(Champ.NOM);
            if (prenom == null) problemes.add("prénom manquant");
            if (nom == null) problemes.add("nom manquant");
            LocalDate naissance = date(l.get(Champ.NAISSANCE), "date de naissance", problemes);

            LocalDate soins = date(l.get(Champ.DATE_SOINS), "date de prise en charge", problemes);
            if (soins == null && l.get(Champ.DATE_SOINS) == null) {
                soins = dateDansTexte(l.get(Champ.DATE_INTERVENTION));
                if (soins == null) {
                    if (attendus.contains(Champ.DATE_SOINS)) problemes.add("date de prise en charge manquante");
                    else soins = debut; // modèle sans colonne de date (hémodialyse)
                }
            }
            if (soins != null && (soins.getMonthValue() != cible.mois() || soins.getYear() != cible.annee())) {
                horsPeriode.add(n);
            }

            String designation = l.get(Champ.PRESTATION);
            if (designation == null) {
                if (attendus.contains(Champ.PRESTATION)) problemes.add("prestation manquante");
                else designation = designationParDefaut(regime);
            }

            Double quantiteLue = decimal(l.get(Champ.QUANTITE), "quantité", problemes);
            int quantite = quantiteLue == null ? 1 : (int) Math.round(quantiteLue);
            if (quantite < 1) problemes.add("la quantité doit être au moins 1");
            Double prixLu = decimal(l.get(Champ.PRIX), "prix unitaire", problemes);
            Double montantLu = decimal(l.get(Champ.MONTANT), "montant", problemes);
            double prix;
            if (prixLu != null) {
                prix = prixLu;
            } else if (regime.estForfaitaire()) {
                prix = 0; // le forfait s'applique
            } else if (montantLu != null && montantParPrestation) {
                prix = montantLu / Math.max(1, quantite);
            } else {
                prix = 0;
                sansPrix.add(n);
            }
            if (prix < 0) problemes.add("le prix ne peut pas être négatif");
            Double duree = decimal(l.get(Champ.DUREE), "durée d'hospitalisation", problemes);

            String sexe = sexe(l.get(Champ.SEXE));
            if (sexe == null && l.get(Champ.SEXE) != null) sexeIllisible.add(n);

            if (!problemes.isEmpty()) {
                problemes.forEach(p -> resultat.erreur(n, p + "."));
                continue;
            }

            String matricule = l.get(Champ.MATRICULE);
            LigneFactureStructure ligne = LigneFactureStructure.builder()
                    .designation(designation)
                    .service(l.get(Champ.SERVICE))
                    .datePriseEnCharge(soins)
                    .quantite(quantite)
                    .prixUnitaire(prix)
                    .numeroRegistre(l.get(Champ.REGISTRE))
                    .ircIra(l.get(Champ.IRC))
                    .indicationCbt(l.get(Champ.INDICATION))
                    .numeroRegistreBloc(l.get(Champ.REGISTRE_BLOC))
                    .dateHeureIntervention(l.get(Champ.DATE_INTERVENTION))
                    .dureeHospitalisationJours(duree == null ? null : (int) Math.round(duree))
                    .patientPrenom(prenom)
                    .patientNom(nom)
                    .patientSexe(sexe)
                    .patientDateNaissance(naissance)
                    .patientTelephone(l.get(Champ.TELEPHONE))
                    .patientAdresse(l.get(Champ.ADRESSE))
                    .patientMatricule(matricule)
                    .patientNumeroCni(l.get(Champ.CNI))
                    .build();
            String identite = matricule != null ? "M:" + cle(matricule)
                    : "N:" + cle(prenom) + "|" + cle(nom) + "|" + naissance;
            prestations.add(new PrestationLue(n, identite + "|" + soins, ligne));
        }

        if (!sansPrix.isEmpty()) {
            resultat.avertir("Prix vide, prestation(s) comptée(s) à 0 F — " + lignes(sansPrix) + "."
                    + (regime == Regime.CESARIENNE
                    ? " Le modèle césarienne n'a pas de colonne de montant : ajoutez une colonne « Montant (FCFA) »."
                    : ""));
        }
        if (!horsPeriode.isEmpty()) {
            resultat.avertir("Date de prise en charge hors de " + FactureStructure.MOIS[cible.mois()] + " "
                    + cible.annee() + " — " + lignes(horsPeriode) + " (gardée telle quelle).");
        }
        if (!sexeIllisible.isEmpty()) {
            resultat.avertir("Sexe illisible, laissé vide — " + lignes(sexeIllisible) + ".");
        }
        return prestations;
    }

    /** Le montant du modèle est-il celui de chaque prestation (et non le total de la visite) ? */
    private static boolean montantParPrestation(Regime regime) {
        return ColonnesFactureStructure.pourRegime(regime).stream()
                .filter(c -> CHAMPS.get(cle(c.entete())) == Champ.MONTANT)
                .findFirst()
                .map(c -> c.agregat() == ColonnesFactureStructure.Agregat.LISTE)
                .orElse(true);
    }

    /** Modèles sans colonne de prestation : la nature de l'acte se déduit du régime. */
    private static String designationParDefaut(Regime regime) {
        return switch (regime) {
            case CESARIENNE -> "Césarienne";
            case DIALYSE_PERITONEALE -> "Dialyse péritonéale (poches)";
            case HEMODIALYSE, DIALYSE -> "Séance d'hémodialyse";
            default -> regime.getLibelle();
        };
    }

    private static String lignes(List<Integer> numeros) {
        String liste = numeros.stream().limit(12).map(String::valueOf).collect(Collectors.joining(", "));
        return (numeros.size() > 1 ? "lignes " : "ligne ") + liste + (numeros.size() > 12 ? "…" : "");
    }

    private static LocalDate date(String brut, String quoi, List<String> problemes) {
        if (brut == null) return null;
        LocalDate date = lireDate(brut);
        if (date == null) problemes.add(quoi + " « " + brut + " » illisible (jj/mm/aaaa)");
        return date;
    }

    private static LocalDate lireDate(String brut) {
        String texte = brut.trim();
        for (DateTimeFormatter format : FORMATS_DATE) {
            try {
                return LocalDate.parse(texte, format);
            } catch (Exception ignore) {
                // format suivant
            }
        }
        return null;
    }

    /** Première date d'un texte (« 15/09/2026 10h30 » → 15/09/2026). */
    private static LocalDate dateDansTexte(String texte) {
        if (texte == null) return null;
        Matcher m = DATE_DANS_TEXTE.matcher(texte);
        return m.find() ? lireDate(m.group()) : null;
    }

    private static Double decimal(String brut, String quoi, List<String> problemes) {
        if (brut == null) return null;
        String nettoye = brut.replaceAll("[^0-9,.-]", "").replace(',', '.');
        if (nettoye.isBlank() || nettoye.equals("-") || nettoye.equals(".")) {
            problemes.add(quoi + " « " + brut + " » illisible");
            return null;
        }
        try {
            return Double.parseDouble(nettoye);
        } catch (NumberFormatException e) {
            problemes.add(quoi + " « " + brut + " » illisible");
            return null;
        }
    }

    /** « H », « Homme », « M », « Masculin » → M ; « F », « Femme », « Féminin » → F. */
    private static String sexe(String brut) {
        if (brut == null) return null;
        return switch (cle(brut)) {
            case "m", "h", "masculin", "homme", "garcon" -> "M";
            case "f", "feminin", "femme", "fille" -> "F";
            default -> null;
        };
    }

    // ══════════════════════════ la facture ══════════════════════════

    /** Facture prête à enregistrer, avec le n° de ligne Excel de chacune de ses lignes. */
    private record Construite(FactureStructure facture, List<Integer> numerosLignes) {
    }

    private Construite construire(Cible cible, String numeroOrigine, List<PrestationLue> prestations,
                                  StructureDto structure, UtilisateurAuthentifie utilisateur) {
        LocalDateTime maintenant = LocalDateTime.now();
        FactureStructure facture = new FactureStructure();
        facture.setRegime(cible.regime());
        facture.setMois(cible.mois());
        facture.setAnnee(cible.annee());
        facture.setStatut(cible.statut());
        facture.setImportee(true);
        facture.setNumeroOrigine(numeroOrigine);
        // Même contexte que ServiceFacturation#appliquerContexteStructure : la facture reste lisible plus tard.
        facture.setStructureSanitaireId(structure.getId());
        facture.setStructureNom(structure.getNom());
        facture.setTypeStructure(structure.getTypeStructure());
        facture.setStructureRattachementId(structure.getStructureRattachementId());
        facture.setDistrictId(structure.getDistrictId());
        facture.setDistrictNom(structure.getDistrictNom());
        facture.setRegionId(structure.getRegionId());
        facture.setLignes(new ArrayList<>());
        facture.setDossiers(new ArrayList<>());
        facture.setHistorique(new ArrayList<>());
        facture.setDocumentsComplementaires(new ArrayList<>());
        facture.setCreatedAt(maintenant);
        facture.setUpdatedAt(maintenant);
        facture.setCreatedBy(utilisateur.getId());

        // Une visite (un dossier) = un même patient à une même date.
        Map<String, List<PrestationLue>> visites = new LinkedHashMap<>();
        prestations.forEach(p -> visites.computeIfAbsent(p.cleVisite(), k -> new ArrayList<>()).add(p));
        List<Integer> numeros = new ArrayList<>();
        for (List<PrestationLue> visite : visites.values()) {
            LigneFactureStructure patient = visite.get(0).ligne();
            DossierPatient dossier = DossierPatient.builder()
                    .id(UUID.randomUUID().toString())
                    // Facture déjà émise : rien n'y est « à compléter », pas même les pièces.
                    .statut(DossierPatient.COMPLET)
                    .prenom(patient.getPatientPrenom())
                    .nom(patient.getPatientNom())
                    .sexe(patient.getPatientSexe())
                    .dateNaissance(patient.getPatientDateNaissance())
                    .telephone(patient.getPatientTelephone())
                    .adresse(patient.getPatientAdresse())
                    .matricule(patient.getPatientMatricule())
                    .numeroCni(patient.getPatientNumeroCni())
                    .numeroRegistre(patient.getNumeroRegistre())
                    .identiteVerrouillee(false)
                    .createdAt(maintenant)
                    .updatedAt(maintenant)
                    .createdBy(utilisateur.getId())
                    .build();
            facture.getDossiers().add(dossier);
            for (PrestationLue p : visite) {
                p.ligne().setDossierId(dossier.getId());
                facture.getLignes().add(p.ligne());
                numeros.add(p.numero());
            }
        }

        facture.tracer(HistoriqueDossier.builder()
                .date(maintenant)
                .statut(cible.statut().name())
                .utilisateurId(utilisateur.getId())
                .utilisateurNom(utilisateur.nomComplet())
                .commentaire("Facture reprise de l'ancienne application"
                        + (numeroOrigine != null ? " (n° " + numeroOrigine + ")" : "")
                        + " — statut d'origine « " + cible.statut().getLibelle() + " »")
                .build());
        return new Construite(facture, numeros);
    }

    /**
     * Montants et parts par les règles de facturation. Le contrôle d'âge est fait ligne à
     * ligne d'abord, pour signaler toutes les lignes fautives avec leur numéro (les règles,
     * elles, s'arrêtent à la première).
     */
    private void calculer(Construite construite, String libelle, ResultatImportFactures resultat) {
        FactureStructure facture = construite.facture();
        boolean ageEnErreur = false;
        for (int i = 0; i < facture.getLignes().size(); i++) {
            try {
                regles.controlerAge(facture.getRegime(), facture.getLignes().get(i));
            } catch (ExceptionMetier e) {
                resultat.erreur(construite.numerosLignes().get(i), e.getMessage());
                ageEnErreur = true;
            }
        }
        if (ageEnErreur || facture.getLignes().isEmpty()) {
            return;
        }
        try {
            regles.appliquer(facture);
        } catch (ExceptionMetier e) {
            resultat.erreur("Facture " + libelle + " : " + e.getMessage());
            return;
        }
        // Matricule calculé par les règles (Plan Sésame) : il remonte dans le dossier.
        for (DossierPatient dossier : facture.getDossiers()) {
            if (dossier.getMatricule() == null || dossier.getMatricule().isBlank()) {
                facture.getLignes().stream()
                        .filter(l -> dossier.getId().equals(l.getDossierId()))
                        .map(LigneFactureStructure::getPatientMatricule)
                        .filter(m -> m != null && !m.isBlank())
                        .findFirst()
                        .ifPresent(dossier::setMatricule);
            }
        }
    }

    private static FactureReconnue reconnue(FactureStructure facture) {
        return new FactureReconnue(facture.getId(), facture.getNumero(), facture.getNumeroOrigine(),
                facture.getRegime(), facture.getRegime().getLibelle(), facture.getMois(), facture.getAnnee(),
                facture.getStatut(), facture.getStatut().getLibelle(), facture.getDossiers().size(),
                facture.getLignes().size(), facture.getMontantTotal());
    }

    // ══════════════════════════ modèle ══════════════════════════

    /**
     * Modèle d'un régime : la facture Excel de ce régime telle que l'application l'exporte
     * (en-tête de la structure, titre du régime, « N° Référence facture », en-têtes du tableau),
     * prérempli de ce qui est déjà choisi, avec des lignes vides à remplir — et une feuille
     * « Aide ». Structure, période et n° d'origine sont facultatifs.
     */
    public byte[] modele(Regime regime, String structureId, Integer mois, Integer annee, String numeroOrigine) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.exigerRole(Role.SERVICE_REGIONAL, Role.ADMIN);
        if (regime == null) throw ExceptionMetier.invalide("Choisissez le régime du modèle.");
        StructureDto structure = null;
        if (nonVide(structureId) != null) {
            structure = clientIdentite.structure(structureId);
            exigerPerimetre(utilisateur, structure);
        }
        boolean periode = mois != null && annee != null && mois >= 1 && mois <= 12;
        List<Colonne> colonnes = ColonnesFactureStructure.pourRegime(regime);
        int nbCol = colonnes.size();
        int dernierCol = Math.max(1, nbCol - 1);

        try (XSSFWorkbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            XSSFColor vert = new XSSFColor(new byte[]{(byte) 47, (byte) 110, (byte) 84}, null);
            XSSFColor gris = new XSSFColor(new byte[]{(byte) 100, (byte) 100, (byte) 100}, null);
            XSSFColor sombre = new XSSFColor(new byte[]{(byte) 51, (byte) 51, (byte) 51}, null);

            CellStyle styleNom = style(classeur, 16, true, vert, false);
            CellStyle styleTitre = style(classeur, 20, true, sombre, false);
            CellStyle styleLibelle = style(classeur, 9, true, gris, false);
            CellStyle styleValeur = style(classeur, 9, true, vert, false);
            CellStyle styleInfo = style(classeur, 9, false, gris, false);
            CellStyle styleClient = style(classeur, 10, true, vert, false);
            styleClient.setAlignment(HorizontalAlignment.RIGHT);
            CellStyle styleRegime = style(classeur, 12, true, sombre, false);
            styleRegime.setAlignment(HorizontalAlignment.CENTER);

            XSSFCellStyle styleEntete = (XSSFCellStyle) style(classeur, 9, true,
                    new XSSFColor(new byte[]{(byte) 255, (byte) 255, (byte) 255}, null), true);
            styleEntete.setFillForegroundColor(vert);
            styleEntete.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            styleEntete.setAlignment(HorizontalAlignment.CENTER);
            styleEntete.setVerticalAlignment(VerticalAlignment.CENTER);
            styleEntete.setWrapText(true);
            CellStyle styleCase = style(classeur, 9, false, sombre, true);
            styleCase.setVerticalAlignment(VerticalAlignment.TOP);
            styleCase.setWrapText(true);

            Sheet feuille = classeur.createSheet(ColonnesFactureStructure.titreCourt(regime));
            int r = 0;
            fusionnee(feuille, r++, dernierCol, structure == null ? "NOM DE LA STRUCTURE" : structure.getNom().toUpperCase(), styleNom);
            r += 2;
            cellule(feuille, r, 0, "Facture", styleTitre);
            r += 2;
            int colClient = Math.max(7, dernierCol);
            meta(feuille, r, "DATE :", periode ? FactureStructure.MOIS[mois] + " " + annee : "", styleLibelle, styleValeur);
            cellule(feuille, r++, colClient, "CLIENT", styleInfo);
            meta(feuille, r, "N° FACTURE :", periode ? String.format("FACT-STRUCT-%d-%02d", annee, mois) : "",
                    styleLibelle, styleValeur);
            cellule(feuille, r++, colClient, "Agence sénégalaise de la couverture sanitaire", styleClient);
            meta(feuille, r, "CODE :", structure == null || structure.getCode() == null ? "" : structure.getCode(),
                    styleLibelle, styleValeur);
            cellule(feuille, r++, colClient, "universelle", styleClient);
            meta(feuille, r++, "MONNAIE :", "FRANCS CFA", styleLibelle, styleValeur);
            r++;
            fusionnee(feuille, r++, dernierCol, ColonnesFactureStructure.titreLong(regime), styleRegime);
            fusionnee(feuille, r++, dernierCol, "N° Référence facture : "
                    + (nonVide(numeroOrigine) == null ? "" : numeroOrigine.trim()), styleInfo);
            r++;

            Row entete = feuille.createRow(r++);
            entete.setHeightInPoints(32);
            for (int i = 0; i < nbCol; i++) {
                cellule(feuille, entete.getRowNum(), i, colonnes.get(i).entete(), styleEntete);
                feuille.setColumnWidth(i, Math.min(40, Math.max(13, colonnes.get(i).entete().length() + 2)) * 256);
            }
            for (int k = 0; k < LIGNES_MODELE; k++) {
                Row ligne = feuille.createRow(r++);
                for (int i = 0; i < nbCol; i++) {
                    ligne.createCell(i).setCellStyle(styleCase);
                }
            }
            feuille.createFreezePane(0, entete.getRowNum() + 1);

            aide(classeur.createSheet("Aide"), regime, style(classeur, 11, true, sombre, false));

            classeur.write(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw ExceptionMetier.invalide("Génération du modèle impossible : " + e.getMessage());
        }
    }

    private static void aide(Sheet aide, Regime regime, CellStyle gras) {
        List<String> textes = new ArrayList<>();
        textes.add("Une ligne par prestation sous les en-têtes verts, sans modifier les en-têtes.");
        textes.add("Plusieurs prestations lors d'une même visite : identité du patient sur la première ligne ; "
                + "sur les suivantes, laissez-la vide ou fusionnez les cellules (comme dans la facture exportée).");
        textes.add("Dates au format jj/mm/aaaa.");
        if (regime.estForfaitaire()) {
            textes.add("Régime au forfait : le forfait s'applique d'office ; les colonnes « Forfait » et « Montant » "
                    + "peuvent rester vides.");
        }
        if (regime == Regime.CESARIENNE) {
            textes.add("Ce modèle n'a pas de colonne de montant : pour reprendre les montants, ajoutez une colonne "
                    + "« Montant (FCFA) » ; sinon ils valent 0 F.");
        }
        if (!attendus(regime).contains(Champ.DATE_SOINS) && regime != Regime.CESARIENNE) {
            textes.add("Pas de colonne de date : les séances sont datées du 1er jour de la période choisie à l'écran.");
        }
        textes.add("Le régime, la période (mois, année), le statut d'origine et le n° de facture d'origine se "
                + "choisissent à l'écran. Le « N° Référence facture » du fichier est repris si aucun n° n'est saisi.");
        textes.add("La facture exportée par l'ancienne application, au même format, peut être importée telle quelle : "
                + "la lecture s'arrête à la ligne « TOTAUX ».");
        textes.add("Si une seule ligne est en erreur, rien n'est importé : corrigez le fichier et réimportez-le.");
        textes.add("Une facture déjà présente pour la même structure, le même régime et le même mois est refusée.");

        int r = 0;
        cellule(aide, r++, 0, "Modèle « " + ColonnesFactureStructure.titreLong(regime) + " » — une facture par fichier", gras);
        r++;
        for (String texte : textes) {
            cellule(aide, r++, 0, "• " + texte, null);
        }
        aide.setColumnWidth(0, 120 * 256);
    }

    private static CellStyle style(Workbook classeur, int taille, boolean gras, XSSFColor couleur, boolean bordure) {
        XSSFFont police = (XSSFFont) classeur.createFont();
        police.setFontHeightInPoints((short) taille);
        police.setBold(gras);
        police.setColor(couleur);
        CellStyle style = classeur.createCellStyle();
        style.setFont(police);
        if (bordure) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
        return style;
    }

    private static void cellule(Sheet feuille, int ligne, int colonne, String valeur, CellStyle style) {
        Row row = feuille.getRow(ligne) != null ? feuille.getRow(ligne) : feuille.createRow(ligne);
        Cell cellule = row.createCell(colonne);
        cellule.setCellValue(valeur);
        if (style != null) cellule.setCellStyle(style);
    }

    private static void fusionnee(Sheet feuille, int ligne, int dernierCol, String valeur, CellStyle style) {
        cellule(feuille, ligne, 0, valeur, style);
        if (dernierCol > 0) feuille.addMergedRegion(new CellRangeAddress(ligne, ligne, 0, dernierCol));
    }

    private static void meta(Sheet feuille, int ligne, String libelle, String valeur, CellStyle styleLibelle,
                             CellStyle styleValeur) {
        cellule(feuille, ligne, 0, libelle, styleLibelle);
        cellule(feuille, ligne, 1, valeur, styleValeur);
    }

    // ══════════════════════════ utilitaires ══════════════════════════

    /** « N° Matricule / CNI » → « nmatriculecni » : comparaison insensible aux accents, à la casse, à la ponctuation. */
    static String cle(String texte) {
        if (texte == null) {
            return "";
        }
        return Normalizer.normalize(texte, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private static String nonVide(String texte) {
        return texte == null || texte.isBlank() ? null : texte.trim();
    }

    private static void champ(Champ champ, String... intitules) {
        for (String intitule : intitules) {
            CHAMPS.putIfAbsent(cle(intitule), champ);
        }
    }
}
