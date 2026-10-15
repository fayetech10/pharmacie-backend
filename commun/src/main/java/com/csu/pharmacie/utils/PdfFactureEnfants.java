package com.csu.pharmacie.utils;

import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.utils.ColonnesFactureStructure.Colonne;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * « Liste + facture 0-5 ans » d'une structure : une page de garde portrait (la facture : DRS,
 * district, structure, « Facture N°mois/MOIS/année », périodes / patients / prestations /
 * total, montant en lettres, signatures) puis la liste nominative paysage (une ligne par
 * visite, prestations réunies, total patients et prestations).
 *
 * <p>Source unique du document, partagée par le cœur (bouton « Liste + facture (0-5 ans) » d'une
 * facture) et par le service centre (« Situation 0-5 ans » du district, qui enchaîne dans un
 * seul PDF les factures de tous les postes et centres — {@link #plusieurs}). Classe sans état ni
 * composant Spring : {@code commun} n'en contient aucun.</p>
 */
public final class PdfFactureEnfants {

    private PdfFactureEnfants() {
    }

    /** Ce que l'en-tête dit de la structure émettrice. */
    public record Structure(String nom, String region, String lieu) {

        /** Lieu des documents : l'adresse, à défaut la région, à défaut le nom. */
        public static Structure de(String nom, String adresse, String region) {
            String n = nom == null || nom.isBlank() ? "Structure de santé" : nom;
            String r = region == null ? "" : region;
            String lieu = adresse != null && !adresse.isBlank() ? adresse : (!r.isBlank() ? r : n);
            return new Structure(n, r, lieu);
        }
    }

    /** Une facture et sa structure, dans l'ordre d'impression. */
    public record FactureAImprimer(FactureStructure facture, Structure structure) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] MOIS = {"", "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
            "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre"};

    /** Le document d'une facture. */
    public static byte[] une(FactureStructure facture, Structure structure) {
        return plusieurs(List.of(new FactureAImprimer(facture, structure)));
    }

    /**
     * Les documents de plusieurs factures, à la suite, dans un seul PDF : pour chacune sa page de
     * garde puis sa liste nominative. Sans facture, une page le dit.
     */
    public static byte[] plusieurs(List<FactureAImprimer> factures) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();
            String dateJour = LocalDate.now().format(DATE);
            if (factures.isEmpty()) {
                document.add(new Paragraph("Aucune facture « Enfants de moins de 5 ans » sur la période.",
                        new Font(Font.FontFamily.HELVETICA, 11, Font.NORMAL)));
            }
            boolean premiere = true;
            for (FactureAImprimer f : factures) {
                if (!premiere) {
                    document.setPageSize(PageSize.A4);
                    document.newPage();
                }
                premiere = false;
                List<LigneFactureStructure> lignes = f.facture().getLignes() != null ? f.facture().getLignes() : List.of();
                pageDeGarde(document, f.facture(), f.structure(), dateJour,
                        ColonnesFactureStructure.parPatient(lignes).size(), lignes.size());
                document.setPageSize(PageSize.A4.rotate());
                document.newPage();
                listeNominative(document, f.facture(), f.structure(), dateJour, lignes);
            }
            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Génération du PDF 0-5 ans impossible : " + e.getMessage(), e);
        }
    }

    /**
     * Facture globale du district : UNE seule page de garde qui regroupe les totaux de tous les
     * postes et centres — nombre de patients (distincts sur l'ensemble), nombre de
     * prestations/actes et montant cumulés — au lieu d'une facture par structure. Utilisée par
     * la consolidation district « Enfants de moins de 5 ans ».
     */
    public static byte[] factureGlobale(List<FactureStructure> factures, String districtNom,
                                        String regionNom, Integer mois, int annee) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            List<LigneFactureStructure> toutes = new ArrayList<>();
            double montant = 0;
            Regime regime = null;
            for (FactureStructure f : factures) {
                if (f.getLignes() != null) {
                    toutes.addAll(f.getLignes());
                }
                montant += f.getMontantTotalSencsu();
                if (regime == null) {
                    regime = f.getRegime();
                }
            }
            if (regime == null) {
                regime = Regime.ZERO_CINQ_ANS;
            }
            int nbPatients = ColonnesFactureStructure.parPatient(toutes).size();
            int nbPrestations = toutes.size();

            pageDeGardeGlobale(document, districtNom, regionNom, mois, annee, regime,
                    montant, nbPatients, nbPrestations);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Génération de la facture globale 0-5 ans impossible : " + e.getMessage(), e);
        }
    }

    /** Page de garde de la facture globale du district (totaux cumulés de toutes les structures). */
    private static void pageDeGardeGlobale(Document document, String districtNom, String regionNom,
                                           Integer mois, int annee, Regime regime, double montant,
                                           int nbPatients, int nbPrestations) throws DocumentException {
        BaseColor bandeau = new BaseColor(238, 242, 246);
        BaseColor textDark = new BaseColor(51, 51, 51);
        Font fEntete = new Font(Font.FontFamily.HELVETICA, 11, Font.BOLD, textDark);
        Font fNormal = new Font(Font.FontFamily.HELVETICA, 10, Font.NORMAL, textDark);
        Font fTitre = new Font(Font.FontFamily.HELVETICA, 13, Font.BOLD, textDark);
        Font headFont = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, textDark);
        Font cellFont = new Font(Font.FontFamily.HELVETICA, 10, Font.NORMAL, textDark);
        Font cellBold = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, textDark);

        String dateJour = LocalDate.now().format(DATE);
        String region = regionNom == null ? "" : regionNom;
        String lieu = !region.isBlank() ? region : (districtNom == null ? "" : districtNom);

        PdfPTable tEntete = new PdfPTable(2);
        tEntete.setWidthPercentage(100);
        tEntete.setWidths(new float[]{1f, 1f});
        PdfPCell g = new PdfPCell(new Phrase("DRS/" + region.toUpperCase(Locale.FRENCH), fEntete));
        g.setBorder(PdfPCell.NO_BORDER);
        tEntete.addCell(g);
        PdfPCell d = new PdfPCell(new Phrase(lieu + ", le " + dateJour, fNormal));
        d.setBorder(PdfPCell.NO_BORDER);
        d.setHorizontalAlignment(Element.ALIGN_RIGHT);
        tEntete.addCell(d);
        document.add(tEntete);

        String district = districtEnTete(districtNom);
        if (district != null) {
            Paragraph p = new Paragraph(district, fEntete);
            p.setSpacingBefore(4f);
            document.add(p);
        }
        Paragraph ensemble = new Paragraph("ENSEMBLE DES POSTES ET CENTRES DU DISTRICT", fEntete);
        ensemble.setSpacingAfter(18f);
        document.add(ensemble);

        String factureNo = mois != null
                ? "Facture N°" + mois + "/" + mois(mois).toUpperCase(Locale.FRENCH) + "/" + annee
                : "Facture - Année " + annee;
        Paragraph num = new Paragraph(factureNo, fTitre);
        num.setAlignment(Element.ALIGN_CENTER);
        num.setSpacingAfter(16f);
        document.add(num);

        Paragraph doit = new Paragraph("Doit : La SEN-CSU", fNormal);
        doit.setSpacingAfter(4f);
        document.add(doit);
        Paragraph prestation = new Paragraph("Prestation : Prise en charge du "
                + (regime != null ? regime.getLibelle().toUpperCase(Locale.FRENCH) : ""), fNormal);
        prestation.setSpacingAfter(14f);
        document.add(prestation);

        String periode = mois != null ? mois(mois).toLowerCase(Locale.FRENCH) + "-" + annee : "année " + annee;
        PdfPTable t = new PdfPTable(4);
        t.setWidthPercentage(100);
        t.setWidths(new float[]{3f, 3f, 3f, 3f});
        for (String h : new String[]{"Périodes", "Nombre patients", "Nombre Prestations/Actes", "TOTAL"}) {
            PdfPCell c = new PdfPCell(new Phrase(h, headFont));
            c.setBackgroundColor(bandeau);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setVerticalAlignment(Element.ALIGN_MIDDLE);
            c.setPadding(6f);
            t.addCell(c);
        }
        celluleGarde(t, periode, Element.ALIGN_CENTER, cellFont);
        celluleGarde(t, String.valueOf(nbPatients), Element.ALIGN_CENTER, cellFont);
        celluleGarde(t, String.valueOf(nbPrestations), Element.ALIGN_CENTER, cellFont);
        celluleGarde(t, ColonnesFactureStructure.texte(montant), Element.ALIGN_RIGHT, cellFont);
        PdfPCell totLbl = new PdfPCell(new Phrase("TOTAL", cellBold));
        totLbl.setColspan(3);
        totLbl.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totLbl.setPadding(6f);
        totLbl.setBackgroundColor(bandeau);
        t.addCell(totLbl);
        PdfPCell totVal = new PdfPCell(new Phrase(ColonnesFactureStructure.texte(montant), cellBold));
        totVal.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totVal.setPadding(6f);
        totVal.setBackgroundColor(bandeau);
        t.addCell(totVal);
        t.setSpacingBefore(4f);
        document.add(t);

        String enLettres = NombreEnLettres.convertir(Math.round(montant));
        if (!enLettres.isEmpty()) {
            enLettres = Character.toUpperCase(enLettres.charAt(0)) + enLettres.substring(1);
        }
        Paragraph arret = new Paragraph("Arrêtée la présente facture à la somme de : " + enLettres + " francs CFA", fNormal);
        arret.setSpacingBefore(16f);
        document.add(arret);

        document.add(signatures(fNormal, 55f));
    }

    /** Page de garde : facture synthétique (DRS / district / structure, période, forfait, montant en lettres). */
    private static void pageDeGarde(Document document, FactureStructure facture, Structure structure, String dateJour,
                                    int nbPatients, int nbPrestations) throws DocumentException {
        BaseColor bandeau = new BaseColor(238, 242, 246); // #EEF2F6 : en-têtes gris-bleu de la page de garde
        BaseColor textDark = new BaseColor(51, 51, 51);
        Font fEntete = new Font(Font.FontFamily.HELVETICA, 11, Font.BOLD, textDark);
        Font fNormal = new Font(Font.FontFamily.HELVETICA, 10, Font.NORMAL, textDark);
        Font fTitre = new Font(Font.FontFamily.HELVETICA, 13, Font.BOLD, textDark);
        Font headFont = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, textDark);
        Font cellFont = new Font(Font.FontFamily.HELVETICA, 10, Font.NORMAL, textDark);
        Font cellBold = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, textDark);

        PdfPTable tEntete = new PdfPTable(2);
        tEntete.setWidthPercentage(100);
        tEntete.setWidths(new float[]{1f, 1f});
        PdfPCell g = new PdfPCell(new Phrase("DRS/" + structure.region().toUpperCase(Locale.FRENCH), fEntete));
        g.setBorder(PdfPCell.NO_BORDER);
        tEntete.addCell(g);
        PdfPCell d = new PdfPCell(new Phrase(structure.lieu() + ", le " + dateJour, fNormal));
        d.setBorder(PdfPCell.NO_BORDER);
        d.setHorizontalAlignment(Element.ALIGN_RIGHT);
        tEntete.addCell(d);
        document.add(tEntete);

        String district = districtEnTete(facture.getDistrictNom());
        if (district != null) {
            Paragraph p = new Paragraph(district, fEntete);
            p.setSpacingBefore(4f);
            document.add(p);
        }
        Paragraph poste = new Paragraph(structure.nom().toUpperCase(Locale.FRENCH), fEntete);
        poste.setSpacingAfter(18f);
        document.add(poste);

        Paragraph num = new Paragraph("Facture N°" + facture.getMois() + "/"
                + mois(facture.getMois()).toUpperCase(Locale.FRENCH) + "/" + facture.getAnnee(), fTitre);
        num.setAlignment(Element.ALIGN_CENTER);
        num.setSpacingAfter(16f);
        document.add(num);

        Paragraph doit = new Paragraph("Doit : La SEN-CSU", fNormal);
        doit.setSpacingAfter(4f);
        document.add(doit);
        Regime regime = facture.getRegime();
        Paragraph prestation = new Paragraph("Prestation : Prise en charge du "
                + (regime != null ? regime.getLibelle().toUpperCase(Locale.FRENCH) : ""), fNormal);
        prestation.setSpacingAfter(14f);
        document.add(prestation);

        PdfPTable t = new PdfPTable(4);
        t.setWidthPercentage(100);
        t.setWidths(new float[]{3f, 3f, 3f, 3f});
        for (String h : new String[]{"Périodes", "Nombre patients", "Nombre Prestations/Actes", "TOTAL"}) {
            PdfPCell c = new PdfPCell(new Phrase(h, headFont));
            c.setBackgroundColor(bandeau);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setVerticalAlignment(Element.ALIGN_MIDDLE);
            c.setPadding(6f);
            t.addCell(c);
        }
        celluleGarde(t, mois(facture.getMois()).toLowerCase(Locale.FRENCH) + "-" + facture.getAnnee(), Element.ALIGN_CENTER, cellFont);
        celluleGarde(t, String.valueOf(nbPatients), Element.ALIGN_CENTER, cellFont);
        celluleGarde(t, String.valueOf(nbPrestations), Element.ALIGN_CENTER, cellFont);
        celluleGarde(t, ColonnesFactureStructure.texte(facture.getMontantTotalSencsu()), Element.ALIGN_RIGHT, cellFont);
        PdfPCell totLbl = new PdfPCell(new Phrase("TOTAL", cellBold));
        totLbl.setColspan(3);
        totLbl.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totLbl.setPadding(6f);
        totLbl.setBackgroundColor(bandeau);
        t.addCell(totLbl);
        PdfPCell totVal = new PdfPCell(new Phrase(ColonnesFactureStructure.texte(facture.getMontantTotalSencsu()), cellBold));
        totVal.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totVal.setPadding(6f);
        totVal.setBackgroundColor(bandeau);
        t.addCell(totVal);
        t.setSpacingBefore(4f);
        document.add(t);

        String enLettres = NombreEnLettres.convertir(Math.round(facture.getMontantTotalSencsu()));
        if (!enLettres.isEmpty()) {
            enLettres = Character.toUpperCase(enLettres.charAt(0)) + enLettres.substring(1);
        }
        Paragraph arret = new Paragraph("Arrêtée la présente facture à la somme de : " + enLettres + " francs CFA", fNormal);
        arret.setSpacingBefore(16f);
        document.add(arret);

        document.add(signatures(fNormal, 55f));
    }

    /** Liste nominative (paysage) : une ligne par visite, sans montants. */
    private static void listeNominative(Document document, FactureStructure facture, Structure structure,
                                        String dateJour, List<LigneFactureStructure> lignes) throws DocumentException {
        BaseColor csuGreen = new BaseColor(26, 122, 74);   // #1A7A4A : bandeau titre + en-tête du tableau
        BaseColor zebra = new BaseColor(240, 247, 243);    // #F0F7F3 : lignes alternées
        BaseColor textDark = new BaseColor(51, 51, 51);
        BaseColor borderLight = new BaseColor(200, 200, 200);
        Font fNom = new Font(Font.FontFamily.HELVETICA, 13, Font.BOLD, textDark);
        Font fInfo = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL, textDark);
        Font fBanniere = new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, BaseColor.WHITE);
        Font headFont = new Font(Font.FontFamily.HELVETICA, 7, Font.BOLD, BaseColor.WHITE);
        Font cellFont = new Font(Font.FontFamily.HELVETICA, 7, Font.NORMAL, textDark);
        Font totalFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, textDark);

        Paragraph poste = new Paragraph(structure.nom().toUpperCase(Locale.FRENCH), fNom);
        poste.setAlignment(Element.ALIGN_CENTER);
        document.add(poste);
        PdfPTable ligneLieu = new PdfPTable(2);
        ligneLieu.setWidthPercentage(100);
        ligneLieu.setWidths(new float[]{1f, 1f});
        PdfPCell cg = new PdfPCell(new Phrase(structure.lieu(), fInfo));
        cg.setBorder(PdfPCell.NO_BORDER);
        ligneLieu.addCell(cg);
        PdfPCell cd = new PdfPCell(new Phrase("Le " + dateJour, fInfo));
        cd.setBorder(PdfPCell.NO_BORDER);
        cd.setHorizontalAlignment(Element.ALIGN_RIGHT);
        ligneLieu.addCell(cd);
        document.add(ligneLieu);

        PdfPTable banniere = new PdfPTable(1);
        banniere.setWidthPercentage(100);
        banniere.setSpacingBefore(6f);
        banniere.setSpacingAfter(6f);
        PdfPCell bc = new PdfPCell(new Phrase(ColonnesFactureStructure.titreLong(facture.getRegime()), fBanniere));
        bc.setBackgroundColor(csuGreen);
        bc.setHorizontalAlignment(Element.ALIGN_CENTER);
        bc.setPadding(7f);
        bc.setBorder(PdfPCell.NO_BORDER);
        banniere.addCell(bc);
        document.add(banniere);

        document.add(new Paragraph("Mois " + mois(facture.getMois()).toLowerCase(Locale.FRENCH) + " " + facture.getAnnee(), fInfo));
        Paragraph ref = new Paragraph("N° Reference : " + (facture.getNumero() != null ? facture.getNumero() : ""), fInfo);
        ref.setSpacingAfter(8f);
        document.add(ref);

        // Une ligne par visite : identité une fois, prestations réunies (à la ligne dans la
        // cellule), date / service une fois s'ils sont communs à la visite. Hors
        // « Enfants de moins de 5 ans » (forfait), la liste porte le détail chiffré par prestation :
        // prix unitaire, quantité et montant facturé à la SEN-CSU (empilés comme les désignations).
        boolean avecMontants = facture.getRegime() != Regime.ZERO_CINQ_ANS;
        // Les colonnes N° registre / Adresse / N° CNI accompagnant sont propres au
        // modèle officiel « Enfants de moins de 5 ans » ; elles sont retirées des autres régimes
        // (ex. classique), comme dans la facture.
        boolean enfants = facture.getRegime() == Regime.ZERO_CINQ_ANS;
        List<Colonne> colonnes = new ArrayList<>();
        List<Integer> aligns = new ArrayList<>();
        List<Float> larg = new ArrayList<>();
        larg.add(2.2f); // colonne N° (en tête, hors `colonnes`)
        if (enfants) {
            ajouterColonne(colonnes, aligns, larg, Colonne.identite("N° dans le registre", LigneFactureStructure::getNumeroRegistre), Element.ALIGN_CENTER, 3f);
        }
        ajouterColonne(colonnes, aligns, larg, Colonne.identite("Prénom(s)", LigneFactureStructure::getPatientPrenom), Element.ALIGN_LEFT, 5.5f);
        ajouterColonne(colonnes, aligns, larg, Colonne.identite("Nom", LigneFactureStructure::getPatientNom), Element.ALIGN_LEFT, 5f);
        ajouterColonne(colonnes, aligns, larg, Colonne.identite("Date de naissance", ColonnesFactureStructure::dateNaissance), Element.ALIGN_CENTER, 4.5f);
        ajouterColonne(colonnes, aligns, larg, Colonne.identite(enfants ? "Sexe (M/F)" : "Sexe (H/F)", l -> ColonnesFactureStructure.sexe(l, enfants)), Element.ALIGN_CENTER, 2.8f);
        if (enfants) {
            ajouterColonne(colonnes, aligns, larg, Colonne.identite("Adresse", LigneFactureStructure::getPatientAdresse), Element.ALIGN_LEFT, 4.5f);
        }
        ajouterColonne(colonnes, aligns, larg, Colonne.identite("N° Matricule / Extrait naissance", ColonnesFactureStructure::matricule), Element.ALIGN_LEFT, 6f);
        if (enfants) {
            ajouterColonne(colonnes, aligns, larg, Colonne.identite("N° CNI accompagnant père/mère", LigneFactureStructure::getPatientNumeroCni), Element.ALIGN_LEFT, 6f);
        }
        ajouterColonne(colonnes, aligns, larg, Colonne.identite("N° Téléphone", LigneFactureStructure::getPatientTelephone), Element.ALIGN_LEFT, 4.5f);
        ajouterColonne(colonnes, aligns, larg, new Colonne("Date de prise en charge", ColonnesFactureStructure::datePriseEnCharge), Element.ALIGN_CENTER, 4.5f);
        ajouterColonne(colonnes, aligns, larg, new Colonne("Service", LigneFactureStructure::getService), Element.ALIGN_CENTER, 3.2f);
        ajouterColonne(colonnes, aligns, larg, Colonne.liste("Prestations et médicaments", LigneFactureStructure::getDesignation), Element.ALIGN_LEFT, 8f);
        if (avecMontants) {
            ajouterColonne(colonnes, aligns, larg, Colonne.liste("Prix unitaire", LigneFactureStructure::getPrixUnitaire), Element.ALIGN_RIGHT, 3.3f);
            ajouterColonne(colonnes, aligns, larg, Colonne.liste("Quantité", LigneFactureStructure::getQuantite), Element.ALIGN_CENTER, 2.2f);
            ajouterColonne(colonnes, aligns, larg, Colonne.liste("Montant facturé SEN-CSU", LigneFactureStructure::getMontantSencsu), Element.ALIGN_RIGHT, 4f);
        }
        int[] alignements = aligns.stream().mapToInt(Integer::intValue).toArray();
        float[] largeurs = new float[larg.size()];
        for (int k = 0; k < largeurs.length; k++) {
            largeurs[k] = larg.get(k);
        }
        PdfPTable t = new PdfPTable(colonnes.size() + 1);
        t.setWidthPercentage(100);
        t.setWidths(largeurs);
        t.setHeaderRows(1);
        List<String> entetes = new ArrayList<>(List.of("N°"));
        colonnes.forEach(c -> entetes.add(c.entete()));
        for (String h : entetes) {
            PdfPCell c = new PdfPCell(new Phrase(h, headFont));
            c.setBackgroundColor(csuGreen);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setVerticalAlignment(Element.ALIGN_MIDDLE);
            c.setPadding(3f);
            c.setBorderColor(borderLight);
            t.addCell(c);
        }
        int i = 1;
        for (List<LigneFactureStructure> visite : ColonnesFactureStructure.parVisite(lignes)) {
            BaseColor bg = (i % 2 == 1) ? zebra : null; // zébrage : visites impaires teintées
            // Une ligne par prestation (cellules séparées) ; le N° et l'identité du patient sont
            // fusionnés verticalement sur tout le bloc de la visite, sans les dupliquer.
            ColonnesFactureStructure.CelluleFusion[][] grille =
                    ColonnesFactureStructure.grilleVisite(visite, colonnes);
            int n = grille.length;
            for (int rr = 0; rr < n; rr++) {
                if (rr == 0) {
                    celluleListe(t, String.valueOf(i), Element.ALIGN_CENTER, cellFont, borderLight, bg, n);
                }
                for (int k = 0; k < colonnes.size(); k++) {
                    ColonnesFactureStructure.CelluleFusion cf = grille[rr][k];
                    if (cf == null) {
                        continue; // couverte par le rowspan de la cellule du dessus : iText la saute.
                    }
                    celluleListe(t, ColonnesFactureStructure.texte(cf.valeur()), alignements[k],
                            cellFont, borderLight, bg, cf.rowspan());
                }
            }
            i++;
        }
        document.add(t);

        int nbPatients = ColonnesFactureStructure.parPatient(lignes).size();
        String resume = "TOTAL   " + nbPatients + " patient" + (nbPatients > 1 ? "s" : "")
                + "   ·   " + lignes.size() + " prestation" + (lignes.size() > 1 ? "s" : "");
        if (avecMontants) {
            resume += "   ·   Montant facturé SEN-CSU : "
                    + ColonnesFactureStructure.texte(facture.getMontantTotalSencsu()) + " FCFA";
        }
        Paragraph total = new Paragraph(resume, totalFont);
        total.setSpacingBefore(10f);
        document.add(total);

        document.add(signatures(fInfo, 30f));
    }

    private static PdfPTable signatures(Font police, float espaceAvant) {
        PdfPTable sig = new PdfPTable(2);
        sig.setWidthPercentage(100);
        sig.setSpacingBefore(espaceAvant);
        for (String libelle : new String[]{"Le Secrétaire exécutif du CDS", "Le Responsable de la Structure"}) {
            PdfPCell s = new PdfPCell(new Phrase(libelle, police));
            s.setBorder(PdfPCell.NO_BORDER);
            s.setHorizontalAlignment(Element.ALIGN_CENTER);
            sig.addCell(s);
        }
        return sig;
    }

    private static void celluleGarde(PdfPTable t, String texte, int align, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(texte, font));
        c.setHorizontalAlignment(align);
        c.setPadding(6f);
        t.addCell(c);
    }

    /** Ajoute une colonne de la liste nominative avec son alignement et sa largeur (listes parallèles). */
    private static void ajouterColonne(List<Colonne> colonnes, List<Integer> aligns, List<Float> larg,
                                       Colonne colonne, int alignement, float largeur) {
        colonnes.add(colonne);
        aligns.add(alignement);
        larg.add(largeur);
    }

    private static void celluleListe(PdfPTable t, String texte, int align, Font font, BaseColor border, BaseColor bg, int rowspan) {
        PdfPCell c = new PdfPCell(new Phrase(texte, font));
        if (rowspan > 1) c.setRowspan(rowspan);
        c.setHorizontalAlignment(align);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPadding(2.5f);
        c.setBorderColor(border);
        if (bg != null) {
            c.setBackgroundColor(bg);
        }
        t.addCell(c);
    }

    /**
     * « DISTRICT SANITAIRE THIÈS » — sans doubler le mot quand le nom le porte déjà (le document
     * affichait « DISTRICT DISTRICT SANITAIRE THIÈS ») ; null sans district.
     */
    static String districtEnTete(String districtNom) {
        if (districtNom == null || districtNom.isBlank()) return null;
        String nom = districtNom.trim().toUpperCase(Locale.FRENCH);
        return nom.startsWith("DISTRICT") ? nom : "DISTRICT " + nom;
    }

    private static String mois(int mois) {
        return mois >= 1 && mois <= 12 ? MOIS[mois] : "";
    }
}
