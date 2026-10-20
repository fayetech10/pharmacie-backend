package com.csu.pharmacie.commun.export;

import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.TypeStructure;
import com.csu.pharmacie.utils.ColonnesFactureStructure;
import com.csu.pharmacie.utils.NombreEnLettres;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;
import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Documents du district pour le régime « Enfants de moins de 5 ans », sur les modèles du
 * district (RECAP_ENFANTS_5ANS et LISTE_ENFANTS_5ANS consolidée). Toutes les structures
 * actives du district y figurent (postes, centres, EPS), à zéro quand elles n'ont rien facturé :
 * <ul>
 *   <li>{@link #situationGlobale} — une ligne par structure : nombre de cas (forfaits
 *       facturés), tarif unitaire, montant ; sous-total par type de structure, total général,
 *       somme en lettres, visa du Médecin chef de District ;</li>
 *   <li>{@link #listeNominative} — toutes les prises en charge d'enfants du district, ligne
 *       par ligne : une ligne par prestation (cellules séparées), l'identité de l'enfant
 *       fusionnée verticalement sur sa visite, puis un récapitulatif par structure.</li>
 * </ul>
 *
 * <p>Les montants sont ceux des factures (le serveur les a calculés au forfait) : la
 * situation et la liste disent toujours la même chose que les factures elles-mêmes.</p>
 */
public class GenerateurPdfEnfantsDistrict {

    private static final BaseColor TEXTE = new BaseColor(33, 37, 41);
    private static final BaseColor BORDURE = new BaseColor(140, 140, 140);
    private static final BaseColor BORDURE_CLAIRE = new BaseColor(200, 200, 200);
    // Situation globale : en-têtes gris-bleu, sous-total gris clair, total plus soutenu.
    private static final BaseColor ENTETE_SITUATION = new BaseColor(220, 230, 240);
    private static final BaseColor SOUS_TOTAL = new BaseColor(238, 242, 246);
    private static final BaseColor TOTAL_GENERAL = new BaseColor(200, 214, 230);
    // Liste nominative : vert CSU (bandeau, en-têtes), lignes alternées, récapitulatif bleu nuit.
    private static final BaseColor VERT_CSU = new BaseColor(26, 122, 74);
    private static final BaseColor ZEBRE = new BaseColor(240, 247, 243);
    private static final BaseColor GROUPE = new BaseColor(220, 238, 227);
    private static final BaseColor BLEU_NUIT = new BaseColor(0, 0, 128);
    private static final BaseColor TOTAL_RECAP = new BaseColor(219, 234, 224);

    private static final Font F_REGION = police(FontFactory.HELVETICA_BOLDOBLIQUE, 11, TEXTE);
    private static final Font F_FAIT_A = police(FontFactory.HELVETICA_OBLIQUE, 10, TEXTE);
    private static final Font F_NORMAL = police(FontFactory.HELVETICA, 10, TEXTE);
    private static final Font F_GRAS = police(FontFactory.HELVETICA_BOLD, 10, TEXTE);
    private static final Font F_TITRE = police(FontFactory.HELVETICA_BOLD, 13, TEXTE);
    private static final Font F_TABLE = police(FontFactory.HELVETICA, 9.5f, TEXTE);
    private static final Font F_TABLE_GRAS = police(FontFactory.HELVETICA_BOLD, 9.5f, TEXTE);
    private static final Font F_BANDEAU = police(FontFactory.HELVETICA_BOLD, 12, BaseColor.WHITE);
    private static final Font F_INFO = police(FontFactory.HELVETICA, 9, TEXTE);
    private static final Font F_INFO_GRAS = police(FontFactory.HELVETICA_BOLD, 9, TEXTE);
    private static final Font F_ENTETE_LISTE = police(FontFactory.HELVETICA_BOLD, 7, BaseColor.WHITE);
    private static final Font F_LISTE = police(FontFactory.HELVETICA, 7, TEXTE);
    private static final Font F_LISTE_GRAS = police(FontFactory.HELVETICA_BOLD, 7.5f, TEXTE);
    private static final Font F_RECAP = police(FontFactory.HELVETICA, 9, TEXTE);
    private static final Font F_RECAP_GRAS = police(FontFactory.HELVETICA_BOLD, 9, TEXTE);
    private static final Font F_RECAP_ENTETE = police(FontFactory.HELVETICA_BOLD, 9, BaseColor.WHITE);

    private static final String[] MOIS = {"", "Janvier", "Février", "Mars", "Avril", "Mai", "Juin", "Juillet",
            "Août", "Septembre", "Octobre", "Novembre", "Décembre"};

    /** Contexte commun aux deux documents. */
    public record Contexte(String districtNom, String regionNom, Integer mois, int annee) {}

    /** Situation globale (A4 portrait) : une ligne par structure, sous-totaux par type. */
    public byte[] situationGlobale(List<FactureStructure> factures, Map<String, StructureDto> structures, Contexte ctx) {
        List<Bilan> bilans = bilans(factures, structures);
        return produire(PageSize.A4, 42, document -> {
            PdfPTable entete = sansBordure(new float[]{1f, 1f});
            entete.addCell(texte("DRS/" + majuscules(ctx.regionNom()), F_REGION, Element.ALIGN_LEFT));
            entete.addCell(texte("Fait à ..............., le " + Documents.DATE.format(LocalDate.now()), F_FAIT_A,
                    Element.ALIGN_RIGHT));
            document.add(entete);
            document.add(new Paragraph(districtEnTete(ctx.districtNom()), F_NORMAL));

            Paragraph titre = new Paragraph("SITUATION GLOBALE — ENFANTS DE MOINS DE 5 ANS", F_TITRE);
            titre.setAlignment(Element.ALIGN_CENTER);
            titre.setSpacingBefore(24f);
            document.add(titre);
            Paragraph sousTitre = new Paragraph("DISTRICT SANITAIRE DE " + majuscules(nomCourtDistrict(ctx.districtNom())), F_GRAS);
            sousTitre.setAlignment(Element.ALIGN_CENTER);
            sousTitre.setSpacingAfter(22f);
            document.add(sousTitre);

            PdfPTable periode = sansBordure(new float[]{1f, 1f});
            PdfPCell mois = texte("Mois : " + majuscules(libelleMois(ctx.mois())), F_GRAS, Element.ALIGN_LEFT);
            mois.setPaddingLeft(40f);
            periode.addCell(mois);
            PdfPCell annee = texte("Année : " + ctx.annee(), F_GRAS, Element.ALIGN_LEFT);
            annee.setPaddingLeft(80f);
            periode.addCell(annee);
            periode.setSpacingAfter(14f);
            document.add(periode);

            PdfPTable table = new PdfPTable(new float[]{0.7f, 4.6f, 1.4f, 1.6f, 2.1f});
            table.setWidthPercentage(100);
            table.setHeaderRows(2);
            PdfPCell numero = enteteSituation("N°");
            numero.setRowspan(2);
            table.addCell(numero);
            PdfPCell structuresEntete = enteteSituation("Structures");
            structuresEntete.setRowspan(2);
            table.addCell(structuresEntete);
            PdfPCell prestations = enteteSituation("PRESTATIONS — Prise en charge");
            prestations.setColspan(3);
            table.addCell(prestations);
            table.addCell(enteteSituation("Nb cas"));
            table.addCell(enteteSituation("Tarif Unit."));
            table.addCell(enteteSituation("Montant"));

            int rang = 1;
            int casTotal = 0;
            double montantTotal = 0;
            for (Map.Entry<String, List<Bilan>> groupe : parType(bilans).entrySet()) {
                int cas = 0;
                double montant = 0;
                for (Bilan b : groupe.getValue()) {
                    table.addCell(cellule(String.valueOf(rang++), F_TABLE, Element.ALIGN_CENTER, null));
                    table.addCell(cellule(libelleStructure(b), F_TABLE, Element.ALIGN_LEFT, null));
                    table.addCell(cellule(String.valueOf(b.cas), F_TABLE, Element.ALIGN_CENTER, null));
                    table.addCell(cellule(tarifUnitaire(b.cas, b.montant), F_TABLE, Element.ALIGN_CENTER, null));
                    table.addCell(cellule(Documents.montant(b.montant), F_TABLE, Element.ALIGN_RIGHT, null));
                    cas += b.cas;
                    montant += b.montant;
                }
                ligneTotal(table, "Sous total (" + groupe.getKey() + ")", cas, montant, SOUS_TOTAL);
                casTotal += cas;
                montantTotal += montant;
            }
            if (bilans.isEmpty()) {
                PdfPCell vide = cellule("Aucune structure ni facture « Enfants de moins de 5 ans » sur la période.", F_TABLE,
                        Element.ALIGN_CENTER, null);
                vide.setColspan(5);
                vide.setPadding(8f);
                table.addCell(vide);
            }
            ligneTotal(table, "TOTAL GÉNÉRAL", casTotal, montantTotal, TOTAL_GENERAL);
            document.add(table);

            Paragraph arret = new Paragraph("Arrêtée la présente situation à la somme de : "
                    + enLettres(montantTotal) + " francs CFA", F_GRAS);
            arret.setSpacingBefore(26f);
            document.add(arret);

            PdfPTable visa = sansBordure(new float[]{1.2f, 1f});
            visa.setSpacingBefore(46f);
            visa.addCell(texte("", F_GRAS, Element.ALIGN_LEFT));
            visa.addCell(texte("Le Médecin chef de District", F_GRAS, Element.ALIGN_CENTER));
            document.add(visa);
        });
    }

    /** Liste nominative consolidée (A4 paysage) : un enfant par ligne, puis le récapitulatif. */
    public byte[] listeNominative(List<FactureStructure> factures, Map<String, StructureDto> structures, Contexte ctx) {
        List<Bilan> bilans = bilans(factures, structures);
        boolean postesSeulement = bilans.stream().allMatch(b -> b.type == TypeStructure.PS);
        return produire(PageSize.A4.rotate(), 30, document -> {
            Paragraph district = new Paragraph(districtEnTete(ctx.districtNom()), F_TITRE);
            district.setAlignment(Element.ALIGN_CENTER);
            document.add(district);
            PdfPTable lieu = sansBordure(new float[]{1f, 1f});
            lieu.addCell(texte("....................", F_INFO, Element.ALIGN_LEFT));
            lieu.addCell(texte("Le " + Documents.DATE.format(LocalDate.now()), F_INFO, Element.ALIGN_RIGHT));
            document.add(lieu);
            document.add(bandeau("LISTE NOMINATIVE DES ENFANTS DE MOINS DE 5 ANS", 6f, 10f));

            PdfPTable infos = sansBordure(new float[]{0.5f, 2.2f, 1.1f, 6f});
            infos.addCell(texte("Mois", F_INFO_GRAS, Element.ALIGN_LEFT));
            infos.addCell(texte(libelleMois(ctx.mois()).toLowerCase(Locale.FRENCH) + " " + ctx.annee(), F_INFO, Element.ALIGN_LEFT));
            infos.addCell(texte("N° Reference :", F_INFO_GRAS, Element.ALIGN_LEFT));
            infos.addCell(texte(reference(ctx), F_INFO, Element.ALIGN_LEFT));
            infos.setSpacingAfter(10f);
            document.add(infos);

            String[] entetes = {"N°", "N° dans le registre", "Prénom(s)", "Nom", "Date de naissance", "Sexe (M/F)",
                    "Adresse", "N° Matricule / Extrait naissance", "N° CNI accompagnant père/mère", "N° Téléphone",
                    "Date de prise en charge", "Service", "Prestations et médicaments"};
            PdfPTable table = new PdfPTable(new float[]{2.2f, 3.2f, 5.5f, 4.8f, 4.3f, 2.6f, 4.8f, 5.6f, 5.2f, 4.3f,
                    4.4f, 4.6f, 9f});
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            for (String e : entetes) {
                PdfPCell c = new PdfPCell(new Phrase(e, F_ENTETE_LISTE));
                c.setBackgroundColor(VERT_CSU);
                c.setHorizontalAlignment(Element.ALIGN_CENTER);
                c.setVerticalAlignment(Element.ALIGN_MIDDLE);
                c.setPadding(3f);
                c.setBorderColor(BORDURE_CLAIRE);
                table.addCell(c);
            }

            int rang = 1;
            for (Bilan b : bilans) {
                // Une structure sans prise en charge n'a pas de bloc dans la liste : elle figure,
                // à zéro, dans le récapitulatif qui suit.
                if (b.lignes.isEmpty()) continue;
                // Repère de la structure : la liste réunit plusieurs postes, chaque enfant y est situé.
                PdfPCell structure = new PdfPCell(new Phrase(libelleStructure(b), F_LISTE_GRAS));
                structure.setColspan(entetes.length);
                structure.setBackgroundColor(GROUPE);
                structure.setBorderColor(BORDURE_CLAIRE);
                structure.setPadding(3f);
                table.addCell(structure);
                // Une ligne par prestation (cellules séparées) ; le N° et l'identité de l'enfant
                // sont fusionnés verticalement sur tout le bloc de la visite, sans les dupliquer.
                for (List<LigneFactureStructure> visite : ColonnesFactureStructure.parVisite(b.lignes)) {
                    ecrireVisite(table, rang++, visite);
                }
            }
            PdfPCell total = cellule("TOTAL", F_LISTE_GRAS, Element.ALIGN_LEFT, null);
            total.setColspan(2);
            total.setBorderColor(BORDURE_CLAIRE);
            table.addCell(total);
            // Prises en charge = prestations (et non lignes du tableau, désormais une par visite).
            int prises = bilans.stream().mapToInt(b -> b.lignes.size()).sum();
            int patients = bilans.stream().mapToInt(b -> b.patients).sum();
            PdfPCell nombre = cellule(prises + " prise" + (prises > 1 ? "s" : "") + " en charge · "
                    + patients + " patient" + (patients > 1 ? "s" : ""), F_LISTE_GRAS, Element.ALIGN_LEFT, null);
            nombre.setColspan(4);
            nombre.setBorderColor(BORDURE_CLAIRE);
            table.addCell(nombre);
            PdfPCell reste = cellule("", F_LISTE, Element.ALIGN_LEFT, null);
            reste.setColspan(entetes.length - 6);
            reste.setBorderColor(BORDURE_CLAIRE);
            table.addCell(reste);
            document.add(table);

            document.add(bandeau(postesSeulement ? "RÉCAPITULATIF PAR POSTE DE SANTÉ" : "RÉCAPITULATIF PAR STRUCTURE", 18f, 0f));
            PdfPTable recap = new PdfPTable(new float[]{4f, 2f, 2f, 2f});
            recap.setWidthPercentage(100);
            for (String e : new String[]{postesSeulement ? "Poste de santé" : "Structure", "Patients", "Prestations", "Montant (FCFA)"}) {
                PdfPCell c = new PdfPCell(new Phrase(e, F_RECAP_ENTETE));
                c.setBackgroundColor(BLEU_NUIT);
                c.setHorizontalAlignment(Element.ALIGN_CENTER);
                c.setBorder(Rectangle.NO_BORDER);
                c.setPadding(3f);
                recap.addCell(c);
            }
            int totalPatients = 0;
            int totalPrestations = 0;
            double totalMontant = 0;
            int i = 0;
            for (Bilan b : bilans) {
                BaseColor fond = i++ % 2 == 1 ? ZEBRE : null;
                recap.addCell(recap(postesSeulement ? b.nom : libelleStructure(b), F_RECAP, Element.ALIGN_LEFT, fond));
                recap.addCell(recap(String.valueOf(b.patients), F_RECAP, Element.ALIGN_CENTER, fond));
                recap.addCell(recap(String.valueOf(b.lignes.size()), F_RECAP, Element.ALIGN_CENTER, fond));
                recap.addCell(recap(Documents.montant(b.montant), F_RECAP, Element.ALIGN_RIGHT, fond));
                totalPatients += b.patients;
                totalPrestations += b.lignes.size();
                totalMontant += b.montant;
            }
            recap.addCell(recap("TOTAL", F_RECAP_GRAS, Element.ALIGN_LEFT, TOTAL_RECAP));
            recap.addCell(recap(String.valueOf(totalPatients), F_RECAP_GRAS, Element.ALIGN_CENTER, TOTAL_RECAP));
            recap.addCell(recap(String.valueOf(totalPrestations), F_RECAP_GRAS, Element.ALIGN_CENTER, TOTAL_RECAP));
            recap.addCell(recap(Documents.montant(totalMontant), F_RECAP_GRAS, Element.ALIGN_RIGHT, TOTAL_RECAP));
            recap.setKeepTogether(true);
            document.add(recap);

            PdfPTable signatures = sansBordure(new float[]{1f, 1f});
            signatures.setSpacingBefore(40f);
            signatures.addCell(texte("Le Secrétaire exécutif du CDS", F_INFO_GRAS, Element.ALIGN_CENTER));
            signatures.addCell(texte("Le Responsable de la Structure", F_INFO_GRAS, Element.ALIGN_CENTER));
            document.add(signatures);
        });
    }

    // ────────────────────────────── liste : une ligne ──────────────────────────────

    /**
     * Colonnes « prestation » de la liste (hors N°) : l'identité une fois par visite, la date et
     * le service communs, et les prestations réunies (une par ligne de la cellule).
     */
    private static final List<ColonnesFactureStructure.Colonne> COLONNES_LISTE = List.of(
            ColonnesFactureStructure.Colonne.identite("N° dans le registre", LigneFactureStructure::getNumeroRegistre),
            ColonnesFactureStructure.Colonne.identite("Prénom(s)", LigneFactureStructure::getPatientPrenom),
            ColonnesFactureStructure.Colonne.identite("Nom", LigneFactureStructure::getPatientNom),
            ColonnesFactureStructure.Colonne.identite("Date de naissance", ColonnesFactureStructure::dateNaissance),
            ColonnesFactureStructure.Colonne.identite("Sexe (M/F)", l -> ColonnesFactureStructure.sexe(l, true)),
            ColonnesFactureStructure.Colonne.identite("Adresse", LigneFactureStructure::getPatientAdresse),
            ColonnesFactureStructure.Colonne.identite("N° Matricule / Extrait naissance", LigneFactureStructure::getPatientMatricule),
            ColonnesFactureStructure.Colonne.identite("N° CNI accompagnant père/mère", LigneFactureStructure::getPatientNumeroCni),
            ColonnesFactureStructure.Colonne.identite("N° Téléphone", LigneFactureStructure::getPatientTelephone),
            new ColonnesFactureStructure.Colonne("Date de prise en charge", ColonnesFactureStructure::datePriseEnCharge),
            new ColonnesFactureStructure.Colonne("Service", LigneFactureStructure::getService),
            ColonnesFactureStructure.Colonne.liste("Prestations et médicaments", LigneFactureStructure::getDesignation));
    private static final int[] ALIGN_LISTE = {Element.ALIGN_CENTER, Element.ALIGN_LEFT, Element.ALIGN_LEFT,
            Element.ALIGN_CENTER, Element.ALIGN_CENTER, Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_LEFT,
            Element.ALIGN_LEFT, Element.ALIGN_CENTER, Element.ALIGN_LEFT, Element.ALIGN_LEFT};

    /**
     * Une visite : une ligne par prestation (cellules séparées) ; le N° et l'identité de l'enfant
     * sont fusionnés verticalement sur tout le bloc, sans les dupliquer.
     */
    private void ecrireVisite(PdfPTable table, int rang, List<LigneFactureStructure> visite) {
        BaseColor fond = rang % 2 == 0 ? ZEBRE : null;
        ColonnesFactureStructure.CelluleFusion[][] grille =
                ColonnesFactureStructure.grilleVisite(visite, COLONNES_LISTE);
        int n = grille.length;
        for (int rr = 0; rr < n; rr++) {
            if (rr == 0) {
                table.addCell(celluleListe(String.valueOf(rang), Element.ALIGN_CENTER, fond, n));
            }
            for (int c = 0; c < COLONNES_LISTE.size(); c++) {
                ColonnesFactureStructure.CelluleFusion cf = grille[rr][c];
                if (cf == null) {
                    continue; // couverte par le rowspan de la cellule du dessus : iText la saute.
                }
                table.addCell(celluleListe(ColonnesFactureStructure.texte(cf.valeur()), ALIGN_LISTE[c], fond, cf.rowspan()));
            }
        }
    }

    // ────────────────────────────── bilans par structure ──────────────────────────────

    /** Ce que chaque structure a facturé au titre des enfants de moins de 5 ans. */
    private static final class Bilan {
        final String nom;
        final TypeStructure type;
        final List<LigneFactureStructure> lignes = new ArrayList<>();
        /** Forfaits facturés : les lignes portant un montant (une par enfant et par mois au poste). */
        int cas;
        double montant;
        int patients;

        Bilan(String nom, TypeStructure type) {
            this.nom = nom;
            this.type = type;
        }
    }

    /**
     * Une entrée par structure ACTIVE du district — postes, centres et EPS — même sans facture
     * « 0-5 ans » sur la période (à zéro), puis ce que chacune a facturé. Demande du 07/10/2026 :
     * le centre de santé n'apparaissait pas tant qu'il n'avait rien facturé dans ce régime, et
     * la situation globale du district semblait ne couvrir que les postes.
     */
    private List<Bilan> bilans(List<FactureStructure> factures, Map<String, StructureDto> structures) {
        Map<String, Bilan> parStructure = new LinkedHashMap<>();
        structures.values().stream()
                .filter(s -> s.getId() != null && s.isActif())
                .forEach(s -> parStructure.putIfAbsent(s.getId(),
                        new Bilan(Documents.ouVide(s.getNom()), s.getTypeStructure())));
        // Factures d'une même structure mises bout à bout par période croissante : la liste
        // nominative inverse l'ordre d'enregistrement, le mois le plus récent passe donc en tête.
        List<FactureStructure> parPeriode = new ArrayList<>(factures);
        parPeriode.sort(Comparator.comparingInt(FactureStructure::getAnnee)
                .thenComparingInt(FactureStructure::getMois));
        for (FactureStructure f : parPeriode) {
            String id = f.getStructureSanitaireId() == null ? "" : f.getStructureSanitaireId();
            StructureDto dto = structures.get(id);
            Bilan b = parStructure.computeIfAbsent(id, cle -> new Bilan(
                    dto != null && dto.getNom() != null ? dto.getNom() : Documents.ouVide(f.getStructureNom()),
                    dto != null ? dto.getTypeStructure() : null));
            List<LigneFactureStructure> lignes = f.getLignes() == null ? List.of() : f.getLignes();
            b.lignes.addAll(lignes);
            b.cas += (int) lignes.stream().filter(l -> l.getMontantSencsu() > 0).count();
            b.montant += f.getMontantTotalSencsu();
        }
        Collator collator = Collator.getInstance(Locale.FRENCH);
        collator.setStrength(Collator.PRIMARY);
        List<Bilan> bilans = new ArrayList<>(parStructure.values());
        bilans.forEach(b -> b.patients = ColonnesFactureStructure.parPatient(b.lignes).size());
        bilans.sort(Comparator.comparing((Bilan b) -> b.type == null ? Integer.MAX_VALUE : b.type.ordinal())
                .thenComparing(b -> b.nom, collator));
        return bilans;
    }

    /** Bilans regroupés par type de structure (PS, CS, EPS), dans cet ordre. */
    private static Map<String, List<Bilan>> parType(List<Bilan> bilans) {
        Map<String, List<Bilan>> groupes = new LinkedHashMap<>();
        for (Bilan b : bilans) {
            groupes.computeIfAbsent(b.type == null ? "Autres" : b.type.name(), cle -> new ArrayList<>()).add(b);
        }
        return groupes;
    }

    private static String libelleStructure(Bilan b) {
        return (b.type == null ? "" : b.type.name() + " ") + b.nom;
    }

    /** Tarif par cas : le forfait (1 400 F au poste) ; « — » si les montants ne sont pas homogènes. */
    private static String tarifUnitaire(int cas, double montant) {
        if (cas <= 0) return "—";
        long total = Math.round(montant);
        return total % cas == 0 ? Documents.montant((double) total / cas) : "—";
    }

    // ────────────────────────────── libellés ──────────────────────────────

    /** « DISTRICT DE TIVAOUANE » — sans doubler « DISTRICT » quand le nom le porte déjà. */
    private static String districtEnTete(String nom) {
        String n = majuscules(nom);
        if (n.isBlank()) return "DISTRICT";
        return n.startsWith("DISTRICT") ? n : "DISTRICT DE " + n;
    }

    /** « District de Tivaouane » → « Tivaouane ». */
    private static String nomCourtDistrict(String nom) {
        return Documents.ouVide(nom).trim().replaceFirst("(?iu)^district\\s+(sanitaire\\s+)?(de\\s+|d'|du\\s+)?", "");
    }

    private static String libelleMois(Integer mois) {
        return mois == null || mois < 1 || mois > 12 ? "Année entière" : MOIS[mois];
    }

    private static String reference(Contexte ctx) {
        return "ENFANTS_5ANS-" + ctx.annee() + (ctx.mois() == null || ctx.mois() < 1 ? "" : String.format("-%02d", ctx.mois()));
    }

    private static String majuscules(String texte) {
        return Documents.ouVide(texte).toUpperCase(Locale.FRENCH);
    }

    private static String enLettres(double montant) {
        String lettres = NombreEnLettres.convertir(Math.round(montant));
        return lettres.isEmpty() ? lettres : Character.toUpperCase(lettres.charAt(0)) + lettres.substring(1);
    }

    // ────────────────────────────── briques ──────────────────────────────

    private static Font police(String famille, float taille, BaseColor couleur) {
        return FontFactory.getFont(famille, "Cp1252", taille, Font.NORMAL, couleur);
    }

    private static PdfPTable sansBordure(float[] largeurs) {
        PdfPTable table = new PdfPTable(largeurs);
        table.setWidthPercentage(100);
        return table;
    }

    private static PdfPCell texte(String texte, Font police, int alignement) {
        PdfPCell c = new PdfPCell(new Phrase(texte, police));
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(alignement);
        c.setPadding(1f);
        return c;
    }

    private static PdfPCell enteteSituation(String texte) {
        PdfPCell c = new PdfPCell(new Phrase(texte, F_TABLE_GRAS));
        c.setBackgroundColor(ENTETE_SITUATION);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setBorderColor(BORDURE);
        c.setPadding(4f);
        return c;
    }

    private static PdfPCell cellule(String texte, Font police, int alignement, BaseColor fond) {
        PdfPCell c = new PdfPCell(new Phrase(texte, police));
        c.setHorizontalAlignment(alignement);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setBorderColor(BORDURE);
        c.setPadding(3.5f);
        if (fond != null) c.setBackgroundColor(fond);
        return c;
    }

    private static void ligneTotal(PdfPTable table, String libelle, int cas, double montant, BaseColor fond) {
        PdfPCell l = cellule(libelle, F_TABLE_GRAS, Element.ALIGN_CENTER, fond);
        l.setColspan(2);
        table.addCell(l);
        table.addCell(cellule(String.valueOf(cas), F_TABLE_GRAS, Element.ALIGN_CENTER, fond));
        table.addCell(cellule("", F_TABLE_GRAS, Element.ALIGN_CENTER, fond));
        table.addCell(cellule(Documents.montant(montant), F_TABLE_GRAS, Element.ALIGN_RIGHT, fond));
    }

    private static PdfPCell celluleListe(String texte, int alignement, BaseColor fond, int rowspan) {
        PdfPCell c = new PdfPCell(new Phrase(texte, F_LISTE));
        if (rowspan > 1) c.setRowspan(rowspan);
        c.setHorizontalAlignment(alignement);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setBorderColor(BORDURE_CLAIRE);
        c.setPadding(2.5f);
        if (fond != null) c.setBackgroundColor(fond);
        return c;
    }

    private static PdfPCell recap(String texte, Font police, int alignement, BaseColor fond) {
        PdfPCell c = new PdfPCell(new Phrase(texte, police));
        c.setHorizontalAlignment(alignement);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(3f);
        if (fond != null) c.setBackgroundColor(fond);
        return c;
    }

    private static PdfPTable bandeau(String titre, float avant, float apres) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(avant);
        table.setSpacingAfter(apres);
        PdfPCell c = new PdfPCell(new Phrase(titre, F_BANDEAU));
        c.setBackgroundColor(VERT_CSU);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(7f);
        table.addCell(c);
        return table;
    }

    private static byte[] produire(Rectangle format, float marge, Contenu contenu) {
        try (ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Document document = new Document(format, marge, marge, marge, marge);
            PdfWriter.getInstance(document, sortie);
            document.open();
            contenu.ecrire(document);
            document.close();
            return sortie.toByteArray();
        } catch (Exception e) {
            throw ExceptionMetier.invalide("Génération du PDF impossible : " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface Contenu {
        void ecrire(Document document) throws DocumentException;
    }
}
