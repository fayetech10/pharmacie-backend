package com.csu.pharmacie.commun.export;

import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.VerificationCarte;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.Image;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import com.itextpdf.text.pdf.PdfTemplate;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.BarcodeQRCode;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.FactureHorsCsu;
import com.csu.pharmacie.entity.LigneHorsCsu;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.entity.TypeStructure;
import com.csu.pharmacie.utils.ColonnesFactureStructure;
import com.csu.pharmacie.utils.NombreEnLettres;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Documents PDF officiels SEN-CSU (§7) : facture récapitulative, liste nominative,
 * situation globale d'un district.
 *
 * <p>Générateur unique pour tous les services : un poste de santé, un centre et un
 * district produisent des documents strictement identiques en présentation, ce qui est
 * précisément l'exigence du modèle officiel.</p>
 */
public class GenerateurPdf {

    private static final BaseColor VERT = new BaseColor(47, 110, 84);
    private static final BaseColor GRIS_ENTETE = new BaseColor(240, 245, 241);
    private static final BaseColor GRIS_LIGNE = new BaseColor(250, 250, 250);

    private static final Font F_TITRE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, "Cp1252", 15, Font.NORMAL, VERT);
    private static final Font F_SOUS_TITRE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, "Cp1252", 10);
    private static final Font F_ENTETE_TABLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, "Cp1252", 8, Font.NORMAL, BaseColor.WHITE);
    private static final Font F_CELLULE = FontFactory.getFont(FontFactory.HELVETICA, "Cp1252", 8);
    private static final Font F_CELLULE_GRAS = FontFactory.getFont(FontFactory.HELVETICA_BOLD, "Cp1252", 8);
    private static final Font F_PETIT = FontFactory.getFont(FontFactory.HELVETICA, "Cp1252", 7, Font.ITALIC, BaseColor.DARK_GRAY);

    /**
     * Facture récapitulative : une ligne par prestation, avec le détail des parts.
     * C'est le document que la structure transmet à l'échelon supérieur.
     */
    public byte[] factureRecapitulative(FactureStructure facture, EnteteDocument entete) {
        return produire(PageSize.A4, document -> {
            enTete(document, entete);

            PdfPTable table = table(new float[]{4f, 12f, 5f, 6f, 3f, 6f, 7f, 7f, 7f});
            enTeteTable(table, "N°", "Bénéficiaire", "Matricule", "Prestation", "Qté",
                    "Prix unit.", "Montant", "Part bénéf.", "Part SEN-CSU");

            // Derniers enregistrés en tête, comme l'écran et les autres documents de la facture.
            List<LigneFactureStructure> lignes = ColonnesFactureStructure.recentesEnTete(facture.getLignes());
            int index = 1;
            for (LigneFactureStructure ligne : lignes) {
                boolean paire = index % 2 == 0;
                cellule(table, String.valueOf(index++), Element.ALIGN_CENTER, paire);
                cellule(table, ligne.nomComplet(), Element.ALIGN_LEFT, paire);
                cellule(table, Documents.ouVide(ligne.getPatientMatricule()), Element.ALIGN_LEFT, paire);
                cellule(table, Documents.ouVide(ligne.getDesignation()), Element.ALIGN_LEFT, paire);
                cellule(table, String.valueOf(ligne.getQuantite()), Element.ALIGN_CENTER, paire);
                cellule(table, Documents.montant(ligne.getPrixUnitaire()), Element.ALIGN_RIGHT, paire);
                cellule(table, Documents.montant(ligne.getMontant()), Element.ALIGN_RIGHT, paire);
                cellule(table, Documents.montant(ligne.getMontantBeneficiaire()), Element.ALIGN_RIGHT, paire);
                cellule(table, Documents.montant(ligne.getMontantSencsu()), Element.ALIGN_RIGHT, paire);
            }
            if (lignes.isEmpty()) {
                PdfPCell vide = new PdfPCell(new Phrase("Aucune prestation facturée sur la période.", F_CELLULE));
                vide.setColspan(9);
                vide.setPadding(10);
                vide.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(vide);
            }

            totalTable(table, 6, "TOTAL GÉNÉRAL",
                    facture.getMontantTotal(), facture.getMontantTotalBeneficiaire(), facture.getMontantTotalSencsu());
            document.add(table);

            document.add(syntheseParts(facture));
            piedDePage(document);
        });
    }

    /**
     * Liste nominative : un bénéficiaire par ligne, prestations agrégées.
     * C'est la pièce de contrôle jointe à la facture récapitulative.
     */
    public byte[] listeNominative(FactureStructure facture, EnteteDocument entete) {
        return produire(PageSize.A4, document -> {
            enTete(document, entete);

            PdfPTable table = table(new float[]{4f, 14f, 6f, 4f, 7f, 8f, 5f, 8f, 8f});
            enTeteTable(table, "N°", "Nom et prénom", "Matricule", "Sexe", "Date naiss.",
                    "Téléphone", "Actes", "Montant", "Part SEN-CSU");

            // Derniers enregistrés en tête : un bénéficiaire prend la place de sa visite la plus récente.
            List<LigneFactureStructure> lignes = ColonnesFactureStructure.recentesEnTete(facture.getLignes());
            // Une ligne par bénéficiaire : on agrège les prestations d'un même patient,
            // c'est ce que le modèle officiel attend (liste de personnes, pas d'actes).
            Map<String, Agregat> parBeneficiaire = new LinkedHashMap<>();
            for (LigneFactureStructure ligne : lignes) {
                parBeneficiaire.computeIfAbsent(ligne.cleBeneficiaire(), c -> new Agregat(ligne)).ajouter(ligne);
            }

            int index = 1;
            for (Agregat agregat : parBeneficiaire.values()) {
                boolean paire = index % 2 == 0;
                cellule(table, String.valueOf(index++), Element.ALIGN_CENTER, paire);
                cellule(table, agregat.reference.nomComplet(), Element.ALIGN_LEFT, paire);
                cellule(table, Documents.ouVide(agregat.reference.getPatientMatricule()), Element.ALIGN_LEFT, paire);
                cellule(table, Documents.ouVide(agregat.reference.getPatientSexe()), Element.ALIGN_CENTER, paire);
                cellule(table, agregat.reference.getPatientDateNaissance() == null ? ""
                        : agregat.reference.getPatientDateNaissance().format(Documents.DATE), Element.ALIGN_CENTER, paire);
                cellule(table, Documents.ouVide(agregat.reference.getPatientTelephone()), Element.ALIGN_LEFT, paire);
                cellule(table, String.valueOf(agregat.nombreActes), Element.ALIGN_CENTER, paire);
                cellule(table, Documents.montant(agregat.montant), Element.ALIGN_RIGHT, paire);
                cellule(table, Documents.montant(agregat.montantSencsu), Element.ALIGN_RIGHT, paire);
            }
            if (parBeneficiaire.isEmpty()) {
                PdfPCell vide = new PdfPCell(new Phrase("Aucun bénéficiaire sur la période.", F_CELLULE));
                vide.setColspan(9);
                vide.setPadding(10);
                vide.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(vide);
            }

            PdfPCell libelle = new PdfPCell(new Phrase(
                    "TOTAL — " + parBeneficiaire.size() + " bénéficiaire(s)", F_CELLULE_GRAS));
            libelle.setColspan(7);
            libelle.setHorizontalAlignment(Element.ALIGN_RIGHT);
            libelle.setPadding(5);
            libelle.setBackgroundColor(GRIS_ENTETE);
            table.addCell(libelle);
            celluleTotal(table, Documents.montant(facture.getMontantTotal()));
            celluleTotal(table, Documents.montant(facture.getMontantTotalSencsu()));

            document.add(table);
            piedDePage(document);
        });
    }

    /**
     * Facture d'un patient payant (§8). Format court : c'est un reçu de caisse remis en
     * main propre, pas une pièce de facturation transmise à un échelon supérieur.
     */
    public byte[] factureHorsCsu(FactureHorsCsu facture, EnteteDocument entete) {
        return produire(PageSize.A5, document -> {
            enTete(document, entete);

            PdfPTable patient = new PdfPTable(1);
            patient.setWidthPercentage(100);
            patient.setSpacingAfter(8);
            PdfPCell cellule = new PdfPCell(new Phrase("Patient : " + facture.patientNomComplet()
                    + (facture.getPatientTelephone() == null || facture.getPatientTelephone().isBlank()
                    ? "" : "  ·  Tél. " + facture.getPatientTelephone()), F_CELLULE_GRAS));
            cellule.setBorder(Rectangle.NO_BORDER);
            cellule.setPadding(2);
            patient.addCell(cellule);
            document.add(patient);

            PdfPTable table = table(new float[]{3f, 14f, 3f, 6f, 7f});
            enTeteTable(table, "N°", "Prestation", "Qté", "Prix unit.", "Montant");

            List<LigneHorsCsu> lignes = facture.getLignes() == null ? List.of() : facture.getLignes();
            int index = 1;
            for (LigneHorsCsu ligne : lignes) {
                boolean paire = index % 2 == 0;
                cellule(table, String.valueOf(index++), Element.ALIGN_CENTER, paire);
                cellule(table, Documents.ouVide(ligne.getDesignation()), Element.ALIGN_LEFT, paire);
                cellule(table, String.valueOf(ligne.getQuantite()), Element.ALIGN_CENTER, paire);
                cellule(table, Documents.montant(ligne.getPrixUnitaire()), Element.ALIGN_RIGHT, paire);
                cellule(table, Documents.montant(ligne.getMontant()), Element.ALIGN_RIGHT, paire);
            }

            PdfPCell libelle = new PdfPCell(new Phrase("TOTAL", F_CELLULE_GRAS));
            libelle.setColspan(4);
            libelle.setHorizontalAlignment(Element.ALIGN_RIGHT);
            libelle.setPadding(5);
            libelle.setBackgroundColor(GRIS_ENTETE);
            table.addCell(libelle);
            celluleTotal(table, Documents.montantAvecDevise(facture.getMontantTotal()));

            PdfPCell encaisse = new PdfPCell(new Phrase("Encaissé"
                    + (facture.getModePaiement() == null || facture.getModePaiement().isBlank()
                    ? "" : " (" + facture.getModePaiement() + ")"), F_CELLULE_GRAS));
            encaisse.setColspan(4);
            encaisse.setHorizontalAlignment(Element.ALIGN_RIGHT);
            encaisse.setPadding(5);
            encaisse.setBackgroundColor(GRIS_ENTETE);
            table.addCell(encaisse);
            celluleTotal(table, Documents.montantAvecDevise(facture.getMontantEncaisse()));

            document.add(table);

            if (facture.isAnnulee()) {
                Paragraph annulation = new Paragraph("FACTURE ANNULÉE — "
                        + Documents.ouVide(facture.getMotifAnnulation()),
                        FontFactory.getFont(FontFactory.HELVETICA_BOLD, "Cp1252", 11, Font.NORMAL, BaseColor.RED));
                annulation.setAlignment(Element.ALIGN_CENTER);
                annulation.setSpacingBefore(12);
                document.add(annulation);
            }

            piedDePage(document);
        });
    }

    /**
     * Situation globale d'un district (§9) : une ligne par structure, sous-totaux par
     * type de structure et récapitulatif par régime.
     *
     * <p>Toutes les factures de la période sont comptées ; la part SEN-CSU encore « en
     * cours » (brouillon ou rejetée) est isolée dans sa colonne, pour que le montant
     * transmis reste lisible.</p>
     */
    public byte[] situationDistrict(List<FactureStructure> factures, EnteteDocument entete) {
        return situationDistrict(factures, List.of(), entete);
    }

    /**
     * Situation globale avec l'annuaire du district : toutes ses structures actives y figurent
     * — postes, centres, EPS —, à zéro quand elles n'ont rien facturé sur la période (le centre
     * de santé disparaissait sinon d'une situation filtrée sur un régime qu'il n'a pas facturé).
     */
    public byte[] situationDistrict(List<FactureStructure> factures, List<StructureDto> structures,
                                    EnteteDocument entete) {
        return produire(PageSize.A4.rotate(), document -> {
            enTete(document, entete);
            double enCours = factures.stream()
                    .filter(f -> f.getStatut() == null || !f.getStatut().estComptabilise())
                    .mapToDouble(FactureStructure::getMontantTotalSencsu).sum();
            if (enCours > 0) {
                Paragraph note = new Paragraph("Toutes les factures de la période sont comptées, dont "
                        + Documents.montantAvecDevise(enCours) + " de part SEN-CSU encore en cours "
                        + "(brouillon ou facture rejetée, à transmettre ou à corriger).", F_PETIT);
                note.setSpacingAfter(6);
                document.add(note);
            }

            // ----- Tableau 1 : par structure -----
            document.add(titreSection("Détail par structure"));
            Map<String, Agregat> parStructure = new LinkedHashMap<>();
            for (StructureDto structure : structures == null ? List.<StructureDto>of() : structures) {
                if (structure.getId() != null && structure.isActif()) {
                    parStructure.putIfAbsent(structure.getId(), new Agregat(structure));
                }
            }
            factures.forEach(f -> parStructure
                    .computeIfAbsent(f.getStructureSanitaireId() + "", c -> new Agregat(f))
                    .ajouter(f));
            // Postes, puis centres et EPS ; ordre alphabétique dans chaque type.
            List<Agregat> lignesStructures = new java.util.ArrayList<>(parStructure.values());
            lignesStructures.sort(Comparator.comparing((Agregat a) -> rangType(a.type))
                    .thenComparing(a -> Documents.ouVide(a.libelle), String.CASE_INSENSITIVE_ORDER));

            PdfPTable table = table(new float[]{4f, 16f, 6f, 6f, 6f, 8f, 8f, 8f, 8f});
            enTeteTable(table, "N°", "Structure", "Type", "Factures", "Bénéf.",
                    "Montant", "Part bénéf.", "Part SEN-CSU", "dont en cours");
            int index = 1;
            double total = 0;
            double totalBeneficiaire = 0;
            double totalSencsu = 0;
            for (Agregat agregat : lignesStructures) {
                boolean paire = index % 2 == 0;
                cellule(table, String.valueOf(index++), Element.ALIGN_CENTER, paire);
                cellule(table, Documents.ouVide(agregat.libelle), Element.ALIGN_LEFT, paire);
                cellule(table, Documents.ouVide(agregat.type), Element.ALIGN_CENTER, paire);
                cellule(table, String.valueOf(agregat.nombreFactures), Element.ALIGN_CENTER, paire);
                cellule(table, String.valueOf(agregat.nombreBeneficiaires), Element.ALIGN_CENTER, paire);
                cellule(table, Documents.montant(agregat.montant), Element.ALIGN_RIGHT, paire);
                cellule(table, Documents.montant(agregat.montantBeneficiaire), Element.ALIGN_RIGHT, paire);
                cellule(table, Documents.montant(agregat.montantSencsu), Element.ALIGN_RIGHT, paire);
                cellule(table, Documents.montant(agregat.montantSencsuEnCours), Element.ALIGN_RIGHT, paire);
                total += agregat.montant;
                totalBeneficiaire += agregat.montantBeneficiaire;
                totalSencsu += agregat.montantSencsu;
            }
            totalTable(table, 5, "TOTAL GÉNÉRAL DU DISTRICT", total, totalBeneficiaire, totalSencsu, enCours);
            document.add(table);

            // ----- Tableau 2 : par régime -----
            document.add(titreSection("Récapitulatif par régime"));
            Map<Regime, Agregat> parRegime = new LinkedHashMap<>();
            for (FactureStructure facture : factures) {
                if (facture.getRegime() == null) {
                    continue;
                }
                parRegime.computeIfAbsent(facture.getRegime(), r -> new Agregat(facture)).ajouter(facture);
            }
            PdfPTable regimes = table(new float[]{18f, 6f, 6f, 9f, 9f, 9f});
            enTeteTable(regimes, "Régime", "Factures", "Bénéf.", "Montant", "Part bénéf.", "Part SEN-CSU");
            int i = 1;
            for (Map.Entry<Regime, Agregat> entree : parRegime.entrySet()) {
                boolean paire = i++ % 2 == 0;
                cellule(regimes, entree.getKey().getLibelle(), Element.ALIGN_LEFT, paire);
                cellule(regimes, String.valueOf(entree.getValue().nombreFactures), Element.ALIGN_CENTER, paire);
                cellule(regimes, String.valueOf(entree.getValue().nombreBeneficiaires), Element.ALIGN_CENTER, paire);
                cellule(regimes, Documents.montant(entree.getValue().montant), Element.ALIGN_RIGHT, paire);
                cellule(regimes, Documents.montant(entree.getValue().montantBeneficiaire), Element.ALIGN_RIGHT, paire);
                cellule(regimes, Documents.montant(entree.getValue().montantSencsu), Element.ALIGN_RIGHT, paire);
            }
            totalTable(regimes, 3, "TOTAL", total, totalBeneficiaire, totalSencsu);
            document.add(regimes);

            piedDePage(document);
        });
    }

    // ══════════════════════════════ dossier patient ══════════════════════════════

    /**
     * Attestation de soins d'un patient : son identité, les prestations reçues et la
     * répartition de la prise en charge. Pour un régime forfaitaire (enfants de moins de
     * 5 ans), les actes sont listés sans prix : seul le forfait est facturé.
     */
    public byte[] attestationSoins(FactureStructure facture, DossierPatient dossier,
                                   List<LigneFactureStructure> lignes, EnteteDocument entete) {
        return produire(PageSize.A4, document -> {
            attestationExemplaire(document, facture, dossier, lignes, entete);
            piedDePage(document);
        });
    }

    /** Un exemplaire de l'attestation, au format officiel du poste de santé. */
    private void attestationExemplaire(Document document, FactureStructure facture, DossierPatient dossier,
                                       List<LigneFactureStructure> lignes, EnteteDocument entete) throws DocumentException {
        Paragraph titre = new Paragraph("ATTESTATION DE SOINS POSTE DE SANTE",
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, "Cp1252", 12));
        titre.setAlignment(Element.ALIGN_CENTER);
        titre.setSpacingAfter(8);
        document.add(titre);

        String assureur = dossier.getMutuelle() != null && !dossier.getMutuelle().isBlank()
                ? dossier.getMutuelle()
                : (entete.getOrganisme() != null && !entete.getOrganisme().isBlank() ? entete.getOrganisme() : "SEN-CSU");

        // En-tête d'identité : Assureur / N° ; Prénom / Nom ; Code assuré / Âge / Sexe.
        PdfPTable idHaut = new PdfPTable(new float[]{3f, 2f});
        idHaut.setWidthPercentage(100);
        infoAttestation(idHaut, "Assureur", assureur);
        infoAttestation(idHaut, "N°", facture.getNumero());
        infoAttestation(idHaut, "Prénom(s)", dossier.getPrenom());
        infoAttestation(idHaut, "Nom", dossier.getNom());
        document.add(idHaut);

        PdfPTable idBas = new PdfPTable(new float[]{3f, 1.2f, 1.2f});
        idBas.setWidthPercentage(100);
        idBas.setSpacingAfter(8);
        infoAttestation(idBas, "Code assuré / Num Immatriculation", dossier.getMatricule());
        infoAttestation(idBas, "Âge", ageLibelle(dossier.getDateNaissance()));
        infoAttestation(idBas, "Sexe", libelleSexe(dossier.getSexe()));
        document.add(idBas);

        // Tableau des prestations : Date / Nature / Montant payé par le bénéficiaire / Montant facturé.
        PdfPTable table = table(new float[]{4f, 12f, 6f, 6f});
        PdfPCell bandeau = new PdfPCell(new Phrase("PRESTATIONS DE LA FORMATION SANITAIRE", F_ENTETE_TABLE));
        bandeau.setColspan(4);
        bandeau.setBackgroundColor(VERT);
        bandeau.setPadding(5);
        bandeau.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(bandeau);
        enTeteTable(table, "Date", "Nature prestations", "Montant payé par\nle bénéficiaire", "Montant facturé");

        int index = 1;
        double totalPaye = 0;
        double totalFacture = 0;
        for (LigneFactureStructure ligne : lignes) {
            boolean paire = index++ % 2 == 0;
            cellule(table, ligne.getDatePriseEnCharge() == null ? ""
                    : ligne.getDatePriseEnCharge().format(Documents.DATE), Element.ALIGN_CENTER, paire);
            cellule(table, natureLigne(ligne), Element.ALIGN_LEFT, paire);
            cellule(table, Documents.montant(ligne.getMontantBeneficiaire()), Element.ALIGN_RIGHT, paire);
            cellule(table, Documents.montant(ligne.getMontantSencsu()), Element.ALIGN_RIGHT, paire);
            totalPaye += ligne.getMontantBeneficiaire();
            totalFacture += ligne.getMontantSencsu();
        }
        if (lignes.isEmpty()) {
            PdfPCell vide = new PdfPCell(new Phrase("Aucune prestation enregistrée pour ce patient.", F_CELLULE));
            vide.setColspan(4);
            vide.setPadding(10);
            vide.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(vide);
        }
        PdfPCell libelleTotal = new PdfPCell(new Phrase("TOTAL", F_CELLULE_GRAS));
        libelleTotal.setColspan(2);
        libelleTotal.setHorizontalAlignment(Element.ALIGN_RIGHT);
        libelleTotal.setPadding(5);
        libelleTotal.setBackgroundColor(GRIS_ENTETE);
        table.addCell(libelleTotal);
        celluleTotal(table, Documents.montant(totalPaye));
        celluleTotal(table, Documents.montant(totalFacture));
        document.add(table);

        String enLettres = NombreEnLettres.convertir(Math.round(totalFacture));
        enLettres = enLettres.isEmpty() ? "" : Character.toUpperCase(enLettres.charAt(0)) + enLettres.substring(1);
        Paragraph lettres = new Paragraph("Total facturé (en lettres) : " + enLettres + " francs CFA.",
                FontFactory.getFont(FontFactory.HELVETICA, "Cp1252", 9, Font.ITALIC));
        lettres.setSpacingBefore(6);
        document.add(lettres);
    }

    /** Cellule « Libellé : valeur » sans bordure (en-tête d'identité de l'attestation). */
    private void infoAttestation(PdfPTable table, String libelle, String valeur) {
        Phrase phrase = new Phrase();
        phrase.add(new com.itextpdf.text.Chunk(libelle + " : ", F_CELLULE_GRAS));
        phrase.add(new com.itextpdf.text.Chunk(Documents.ouVide(valeur), F_CELLULE));
        PdfPCell cellule = new PdfPCell(phrase);
        cellule.setBorder(Rectangle.NO_BORDER);
        cellule.setPadding(3);
        table.addCell(cellule);
    }

    /** Nature de la prestation : désignation, avec la quantité quand elle est > 1. */
    private static String natureLigne(LigneFactureStructure ligne) {
        String nature = ligne.getDesignation() != null && !ligne.getDesignation().isBlank()
                ? ligne.getDesignation() : Documents.ouVide(ligne.getService());
        return ligne.getQuantite() > 1 ? nature + " (x" + ligne.getQuantite() + ")" : nature;
    }

    /** Âge exprimé en années, ou en mois pour les nourrissons. */
    private static String ageLibelle(java.time.LocalDate naissance) {
        if (naissance == null) {
            return "";
        }
        java.time.LocalDate aujourdHui = java.time.LocalDate.now();
        int ans = java.time.Period.between(naissance, aujourdHui).getYears();
        if (ans >= 1) {
            return ans + " an" + (ans > 1 ? "s" : "");
        }
        long mois = java.time.temporal.ChronoUnit.MONTHS.between(naissance, aujourdHui);
        return mois <= 0 ? "< 1 mois" : mois + " mois";
    }

    /**
     * Fiche de l'assuré : identité et éligibilité telles que le système de gestion des
     * assurés les a retournées au moment du contrôle, avec la photo du porteur quand la
     * carte en fournit une.
     *
     * @param photo photo du porteur (data URL), ou {@code null}
     */
    public byte[] ficheAssure(FactureStructure facture, DossierPatient dossier, String photo, EnteteDocument entete) {
        return produire(PageSize.A4, (document, ecrivain) -> {
            enTete(document, entete);

            // La carte d'assuré, au format de la carte officielle — la même que dans le dossier
            // du patient au centre de santé (demande du 07/10/2026). Elle porte la photo.
            document.add(titreSection("Carte d'assuré"));
            Image carte = carteAssure(ecrivain, dossier, imageDepuisDataUrl(photo));
            carte.scaleToFit(380, 218);
            carte.setAlignment(Image.ALIGN_CENTER);
            carte.setSpacingAfter(6);
            document.add(carte);

            document.add(titreSection("Identité de l'assuré"));
            PdfPTable identite = tableFiche();
            ligneFiche(identite, "Code d'immatriculation", dossier.getMatricule());
            ligneFiche(identite, "Prénom(s)", dossier.getPrenom());
            ligneFiche(identite, "Nom", dossier.getNom());
            ligneFiche(identite, "Sexe", libelleSexe(dossier.getSexe()));
            ligneFiche(identite, "Date de naissance", dossier.getDateNaissance() == null ? null
                    : dossier.getDateNaissance().format(Documents.DATE));
            ligneFiche(identite, "N° de pièce d'identité", dossier.getNumeroCni());
            ligneFiche(identite, "Téléphone", dossier.getTelephone());
            document.add(identite);

            document.add(titreSection("Éligibilité"));
            PdfPTable eligibilite = tableFiche();
            ligneFiche(eligibilite, "Régime", facture.getRegime() == null ? null : facture.getRegime().getLibelle());
            ligneFiche(eligibilite, "Catégorie de la carte", dossier.getCategorieCarte());
            ligneFiche(eligibilite, "État de la carte", dossier.getEtatCarte());
            ligneFiche(eligibilite, "Début de validité", dossier.getDateDebutValidite() == null ? null
                    : dossier.getDateDebutValidite().format(Documents.DATE));
            ligneFiche(eligibilite, "Date d'expiration", dossier.getDateFinValidite() == null ? null
                    : dossier.getDateFinValidite().format(Documents.DATE));
            ligneFiche(eligibilite, "Taux de couverture", dossier.getTauxCouverture() == null ? null
                    : dossier.getTauxCouverture() + " %");
            ligneFiche(eligibilite, "Résultat du contrôle", VerificationCarte.VERIFIEE.equals(dossier.getVerificationCarte())
                    ? "Carte valide — éligible" : "Carte non vérifiée (système de vérification indisponible)");
            ligneFiche(eligibilite, "Contrôle effectué le", dossier.getDateVerification() == null ? null
                    : dossier.getDateVerification().format(Documents.DATE_HEURE));
            document.add(eligibilite);

            Paragraph mention = new Paragraph(dossier.isIdentiteVerrouillee()
                    ? "Informations issues du système de gestion des assurés de la SEN-CSU. Elles font référence "
                    + "et ne peuvent pas être modifiées sur la plateforme."
                    : "Le contrôle d'éligibilité n'a pas pu être effectué en ligne : les informations ci-dessus ont "
                    + "été saisies au poste.", F_PETIT);
            mention.setSpacingBefore(10);
            document.add(mention);
            piedDePage(document);
        });
    }

    // ══════════════ carte d'assuré (format de la carte officielle) ══════════════

    /** Dimensions de dessin de la carte (unités du canvas du tableau de bord, y vers le bas). */
    private static final float CARTE_L = 700f;
    private static final float CARTE_H = 400f;
    private static final BaseColor CARTE_MARINE = new BaseColor(0x16, 0x35, 0x7E);
    private static final BaseColor CARTE_ENCRE = new BaseColor(0x1F, 0x29, 0x37);
    private static final BaseColor CARTE_GRIS = new BaseColor(0x9C, 0xA3, 0xAF);
    private static final BaseColor CARTE_FILET = new BaseColor(0xCB, 0xD5, 0xE1);
    private static final BaseColor DRAPEAU_VERT = new BaseColor(0x00, 0x85, 0x3F);
    private static final BaseColor DRAPEAU_JAUNE = new BaseColor(0xFD, 0xEF, 0x42);
    private static final BaseColor DRAPEAU_ROUGE = new BaseColor(0xE3, 0x1B, 0x23);
    private static final BaseColor LOGO_VERT = new BaseColor(0x3C, 0x8C, 0x4E);
    /** Logo de l'Agence (même fichier que le tableau de bord), lu une fois. */
    private static volatile byte[] logoCsu;

    /**
     * Carte d'assuré au format de la carte officielle de couverture santé — même dessin que la
     * carte de la liasse du centre de santé ({@code frontend/src/app/core/utils/carte-assure-image.ts}) :
     * drapeau, « CARTE DE COUVERTURE SANTE », photo, nom, prénom(s), n° d'immatriculation, QR
     * code vers la fiche officielle, logo de l'Agence, bandeau aux couleurs nationales.
     * Dessinée en vectoriel dans les coordonnées du canvas (700 × 400, y vers le bas), donc
     * nette à toute échelle d'impression.
     */
    private Image carteAssure(PdfWriter ecrivain, DossierPatient dossier, Image photo) throws DocumentException {
        BaseFont normal;
        BaseFont gras;
        try {
            normal = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, false);
            gras = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, false);
        } catch (java.io.IOException e) {
            throw new DocumentException(e);
        }
        PdfTemplate t = PdfTemplate.createTemplate(ecrivain, CARTE_L, CARTE_H);
        float m = 8;

        // Fond, dans le contour arrondi de la carte (tout ce qui suit y est découpé).
        t.saveState();
        t.roundRectangle(m, m, CARTE_L - 2 * m, CARTE_H - 2 * m, 18);
        t.clip();
        t.newPath();
        t.setColorFill(BaseColor.WHITE);
        t.rectangle(0, 0, CARTE_L, CARTE_H);
        t.fill();

        // En-tête : drapeau centré puis titre.
        drapeau(t, CARTE_L / 2 - 23, 22, 46, 30);
        String titre = "CARTE DE COUVERTURE SANTE";
        float espacement = 2f;
        float largeurTitre = gras.getWidthPoint(titre, 23) + espacement * (titre.length() - 1);
        t.beginText();
        t.setFontAndSize(gras, 23);
        t.setColorFill(CARTE_MARINE);
        t.setCharacterSpacing(espacement);
        t.setTextMatrix(CARTE_L / 2 - largeurTitre / 2, CARTE_H - 86);
        t.showText(titre);
        t.setCharacterSpacing(0);
        t.endText();

        // Photo du porteur (recadrée dans un cadre arrondi), ou emplacement en pointillés.
        float px = 34, py = 108, pw = 120, ph = 150;
        if (photo != null) {
            t.saveState();
            t.roundRectangle(px, CARTE_H - py - ph, pw, ph, 10);
            t.clip();
            t.newPath();
            float echelle = Math.max(pw / photo.getWidth(), ph / photo.getHeight());
            float dw = photo.getWidth() * echelle, dh = photo.getHeight() * echelle;
            t.addImage(photo, dw, 0, 0, dh, px + (pw - dw) / 2, CARTE_H - (py + (ph - dh) / 2) - dh);
            t.restoreState();
            t.setColorStroke(CARTE_FILET);
            t.setLineWidth(1);
            t.roundRectangle(px, CARTE_H - py - ph, pw, ph, 10);
            t.stroke();
        } else {
            t.setColorStroke(CARTE_FILET);
            t.setLineWidth(1.5f);
            t.setLineDash(6, 5, 0);
            t.roundRectangle(px, CARTE_H - py - ph, pw, ph, 10);
            t.stroke();
            t.setLineDash(0);
            texteCentre(t, normal, 12, CARTE_GRIS, "Photo", px + pw / 2, py + ph / 2 + 30);
        }

        // Identité ; la colonne de droite reste au QR code.
        float fx = 182, largeurMax = CARTE_L - fx - 190;
        ligneCarte(t, gras, normal, fx, 135, largeurMax, "Nom",
                dossier.getNom() == null ? "" : dossier.getNom().toUpperCase(java.util.Locale.FRENCH));
        ligneCarte(t, gras, normal, fx, 178, largeurMax, "Prénom(s)", dossier.getPrenom());
        ligneCarte(t, gras, normal, fx, 221, largeurMax, "N° Immatriculation", dossier.getMatricule());

        // QR code vers la fiche officielle de la carte.
        String code = dossier.getMatricule() == null ? "" : dossier.getMatricule().replaceAll("\\s+", "");
        try {
            Image qr = new BarcodeQRCode("https://carte.sencsu.sn/?code="
                    + java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8), 1, 1, null).getImage();
            float qs = 118;
            t.addImage(qr, qs, 0, 0, qs, CARTE_L - 34 - qs, CARTE_H - 104 - qs);
        } catch (Exception e) {
            // Sans QR, la carte reste lisible : on ne bloque pas la fiche pour autant.
        }

        // Logo de l'Agence, en bas à droite au-dessus du bandeau.
        float bandeau = 22;
        float ly = CARTE_H - bandeau - 78;
        Image logo = logoAgence();
        if (logo != null) {
            float ls = 58, lx = CARTE_L - 34 - 216;
            t.addImage(logo, ls, 0, 0, ls, lx, CARTE_H - ly - ls);
            float tx = lx + ls + 8, ty = ly + 18;
            for (String ligne : new String[]{"AGENCE SENEGALAISE DE LA", "COUVERTURE SANITAIRE", "UNIVERSELLE"}) {
                t.beginText();
                t.setFontAndSize(gras, 9.5f);
                t.setColorFill(LOGO_VERT);
                t.setTextMatrix(tx, CARTE_H - ty);
                t.showText(ligne);
                t.endText();
                ty += 14;
            }
        }

        // Bandeau aux couleurs nationales, étoile verte au centre.
        float bw = (CARTE_L - 2 * m) / 3;
        float yBandeau = m; // bas de la carte en coordonnées PDF
        t.setColorFill(DRAPEAU_VERT);
        t.rectangle(m, yBandeau, bw, bandeau);
        t.fill();
        t.setColorFill(DRAPEAU_JAUNE);
        t.rectangle(m + bw, yBandeau, bw, bandeau);
        t.fill();
        t.setColorFill(DRAPEAU_ROUGE);
        t.rectangle(m + 2 * bw, yBandeau, bw, bandeau);
        t.fill();
        etoile(t, CARTE_L / 2, CARTE_H - m - bandeau / 2, bandeau * 0.42f, DRAPEAU_VERT);

        t.restoreState();
        t.setColorStroke(CARTE_MARINE);
        t.setLineWidth(2);
        t.roundRectangle(m, m, CARTE_L - 2 * m, CARTE_H - 2 * m, 18);
        t.stroke();
        return Image.getInstance(t);
    }

    /** Drapeau du Sénégal (x, y du coin haut-gauche dans les coordonnées du canvas). */
    private static void drapeau(PdfTemplate t, float x, float y, float w, float h) {
        float b = w / 3;
        float bas = CARTE_H - y - h;
        BaseColor[] couleurs = {DRAPEAU_VERT, DRAPEAU_JAUNE, DRAPEAU_ROUGE};
        for (int i = 0; i < 3; i++) {
            t.setColorFill(couleurs[i]);
            t.rectangle(x + i * b, bas, b, h);
            t.fill();
        }
        etoile(t, x + w / 2, y + h / 2, h * 0.3f, DRAPEAU_VERT);
        t.setColorStroke(CARTE_FILET);
        t.setLineWidth(0.5f);
        t.rectangle(x, bas, w, h);
        t.stroke();
    }

    /** Étoile à cinq branches, pointe en haut (centre dans les coordonnées du canvas). */
    private static void etoile(PdfTemplate t, float cx, float cy, float rayon, BaseColor couleur) {
        float interieur = rayon * 0.4f;
        for (int i = 0; i < 10; i++) {
            double r = i % 2 == 0 ? rayon : interieur;
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float px = (float) (cx + r * Math.cos(a));
            float py = CARTE_H - (float) (cy + r * Math.sin(a));
            if (i == 0) t.moveTo(px, py);
            else t.lineTo(px, py);
        }
        t.closePath();
        t.setColorFill(couleur);
        t.fill();
    }

    /** « Libellé : valeur » sur la carte ; la valeur est raccourcie (…) si elle déborde. */
    private static void ligneCarte(PdfTemplate t, BaseFont gras, BaseFont normal, float x, float yCanvas,
                                   float largeurMax, String libelle, String valeur) {
        String lib = libelle + " : ";
        float lw = gras.getWidthPoint(lib, 15);
        String v = valeur == null || valeur.isBlank() ? "—" : valeur;
        while (v.length() > 1 && normal.getWidthPoint(v, 15) > largeurMax - lw) {
            v = v.substring(0, v.length() - 2) + "…";
        }
        t.beginText();
        t.setFontAndSize(gras, 15);
        t.setColorFill(CARTE_MARINE);
        t.setTextMatrix(x, CARTE_H - yCanvas);
        t.showText(lib);
        t.setFontAndSize(normal, 15);
        t.setColorFill(CARTE_ENCRE);
        t.setTextMatrix(x + lw, CARTE_H - yCanvas);
        t.showText(v);
        t.endText();
    }

    private static void texteCentre(PdfTemplate t, BaseFont police, float taille, BaseColor couleur, String texte,
                                    float cx, float yCanvas) {
        t.beginText();
        t.setFontAndSize(police, taille);
        t.setColorFill(couleur);
        t.setTextMatrix(cx - police.getWidthPoint(texte, taille) / 2, CARTE_H - yCanvas);
        t.showText(texte);
        t.endText();
    }

    /** Logo de l'Agence, depuis les ressources ; absent, la carte s'en passe. */
    private static Image logoAgence() {
        try {
            if (logoCsu == null) {
                try (java.io.InputStream flux = GenerateurPdf.class.getResourceAsStream("/documents/logo-csu.png")) {
                    if (flux == null) return null;
                    logoCsu = flux.readAllBytes();
                }
            }
            return Image.getInstance(logoCsu);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Image d'une data URL ({@code data:image/jpeg;base64,…}). Une URL distante n'est pas
     * téléchargée — le serveur n'a pas à aller chercher d'images sur le réseau pour un PDF —
     * et une image illisible est simplement omise : la fiche reste utilisable sans photo.
     */
    private static Image imageDepuisDataUrl(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:image/") || !dataUrl.contains(",")) {
            return null;
        }
        try {
            return Image.getInstance(Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1)));
        } catch (Exception e) {
            return null;
        }
    }

    /** Tableau libellé / valeur, sans ligne d'en-tête répétée en cas de saut de page. */
    private PdfPTable tableFiche() {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[]{8f, 14f});
        } catch (DocumentException e) {
            throw new IllegalStateException(e);
        }
        return table;
    }

    /** Ligne libellé / valeur d'une fiche ; les valeurs absentes ne sont pas imprimées. */
    private void ligneFiche(PdfPTable table, String libelle, String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return;
        }
        PdfPCell gauche = new PdfPCell(new Phrase(libelle, F_CELLULE_GRAS));
        gauche.setBackgroundColor(GRIS_ENTETE);
        gauche.setPadding(5);
        table.addCell(gauche);
        PdfPCell droite = new PdfPCell(new Phrase(valeur, F_CELLULE));
        droite.setPadding(5);
        table.addCell(droite);
    }

    private static String libelleSexe(String sexe) {
        if (sexe == null) {
            return null;
        }
        return switch (sexe.toUpperCase(java.util.Locale.ROOT)) {
            case "M", "H" -> "Masculin";
            case "F" -> "Féminin";
            default -> sexe;
        };
    }

    // ══════════════════════════════ mise en page ══════════════════════════════

    private void enTete(Document document, EnteteDocument entete) throws DocumentException {
        Paragraph republique = new Paragraph(Documents.ouVide(entete.getRepublique()), F_SOUS_TITRE);
        republique.setAlignment(Element.ALIGN_CENTER);
        document.add(republique);

        Paragraph organisme = new Paragraph(Documents.ouVide(entete.getOrganisme()),
                FontFactory.getFont(FontFactory.HELVETICA, "Cp1252", 9, Font.NORMAL, BaseColor.DARK_GRAY));
        organisme.setAlignment(Element.ALIGN_CENTER);
        organisme.setSpacingAfter(10);
        document.add(organisme);

        Paragraph titre = new Paragraph(Documents.ouVide(entete.getTitre()).toUpperCase(), F_TITRE);
        titre.setAlignment(Element.ALIGN_CENTER);
        titre.setSpacingAfter(8);
        document.add(titre);

        PdfPTable identite = new PdfPTable(2);
        identite.setWidthPercentage(100);
        identite.setSpacingAfter(10);
        try {
            identite.setWidths(new float[]{1f, 1f});
        } catch (DocumentException e) {
            throw new IllegalStateException(e);
        }
        infos(identite, "Structure", entete.getStructureNom());
        infos(identite, "Période", entete.getPeriode());
        infos(identite, "Type", entete.getTypeStructureLibelle());
        infos(identite, "Régime", entete.getRegimeLibelle());
        infos(identite, "District", entete.getDistrictNom());
        infos(identite, "Région", entete.getRegionNom());
        if (entete.getNumero() != null && !entete.getNumero().isBlank()) {
            infos(identite, "N° facture", entete.getNumero());
            infos(identite, "Édité le", LocalDateTime.now().format(Documents.DATE_HEURE));
        }
        document.add(identite);
    }

    private void infos(PdfPTable table, String libelle, String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return;
        }
        PdfPCell cellule = new PdfPCell(new Phrase(libelle + " : " + valeur, F_CELLULE));
        cellule.setBorder(Rectangle.NO_BORDER);
        cellule.setPadding(2);
        table.addCell(cellule);
    }

    private Paragraph titreSection(String texte) {
        Paragraph paragraphe = new Paragraph(texte,
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, "Cp1252", 10, Font.NORMAL, VERT));
        paragraphe.setSpacingBefore(14);
        paragraphe.setSpacingAfter(6);
        return paragraphe;
    }

    private PdfPTable syntheseParts(FactureStructure facture) {
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(60);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingBefore(14);
        String taux = facture.getRegime() == null ? "" : " (" + facture.getRegime().getTauxBeneficiaireLibelle() + ")";
        entete(table, "Montant total");
        entete(table, "Ticket modérateur" + taux);
        entete(table, "Prise en charge SEN-CSU");
        celluleTotal(table, Documents.montantAvecDevise(facture.getMontantTotal()));
        celluleTotal(table, Documents.montantAvecDevise(facture.getMontantTotalBeneficiaire()));
        celluleTotal(table, Documents.montantAvecDevise(facture.getMontantTotalSencsu()));
        return table;
    }

    private void piedDePage(Document document) throws DocumentException {
        Paragraph pied = new Paragraph(
                "Document généré par la plateforme SEN-CSU le " + LocalDateTime.now().format(Documents.DATE_HEURE),
                F_PETIT);
        pied.setAlignment(Element.ALIGN_CENTER);
        pied.setSpacingBefore(20);
        document.add(pied);
    }

    private PdfPTable table(float[] largeurs) {
        PdfPTable table = new PdfPTable(largeurs.length);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        try {
            table.setWidths(largeurs);
        } catch (DocumentException e) {
            throw new IllegalStateException(e);
        }
        return table;
    }

    private void enTeteTable(PdfPTable table, String... libelles) {
        for (String libelle : libelles) {
            entete(table, libelle);
        }
    }

    private void entete(PdfPTable table, String libelle) {
        PdfPCell cellule = new PdfPCell(new Phrase(libelle, F_ENTETE_TABLE));
        cellule.setBackgroundColor(VERT);
        cellule.setPadding(5);
        cellule.setHorizontalAlignment(Element.ALIGN_CENTER);
        cellule.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(cellule);
    }

    private void cellule(PdfPTable table, String texte, int alignement, boolean fondAlterne) {
        PdfPCell cellule = new PdfPCell(new Phrase(Documents.ouVide(texte), F_CELLULE));
        cellule.setPadding(4);
        cellule.setHorizontalAlignment(alignement);
        if (fondAlterne) {
            cellule.setBackgroundColor(GRIS_LIGNE);
        }
        table.addCell(cellule);
    }

    private void celluleTotal(PdfPTable table, String texte) {
        PdfPCell cellule = new PdfPCell(new Phrase(texte, F_CELLULE_GRAS));
        cellule.setPadding(5);
        cellule.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cellule.setBackgroundColor(GRIS_ENTETE);
        table.addCell(cellule);
    }

    /** Ligne de total : libellé fusionné sur {@code colonnesLibelle} colonnes, puis trois montants. */
    private void totalTable(PdfPTable table, int colonnesLibelle, String libelle, double... totaux) {
        PdfPCell cellule = new PdfPCell(new Phrase(libelle, F_CELLULE_GRAS));
        cellule.setColspan(colonnesLibelle);
        cellule.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cellule.setPadding(5);
        cellule.setBackgroundColor(GRIS_ENTETE);
        table.addCell(cellule);
        for (double total : totaux) {
            celluleTotal(table, Documents.montant(total));
        }
    }

    private byte[] produire(Rectangle format, Contenu contenu) {
        try (ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Document document = new Document(format, 28, 28, 30, 30);
            PdfWriter.getInstance(document, sortie);
            document.open();
            contenu.ecrire(document);
            document.close();
            return sortie.toByteArray();
        } catch (Exception e) {
            throw ExceptionMetier.invalide("Génération du PDF impossible : " + e.getMessage());
        }
    }

    /** Variante qui donne aussi l'écrivain PDF au contenu (dessins vectoriels, ex. la carte d'assuré). */
    private byte[] produire(Rectangle format, ContenuAvecEcrivain contenu) {
        try (ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Document document = new Document(format, 28, 28, 30, 30);
            PdfWriter ecrivain = PdfWriter.getInstance(document, sortie);
            document.open();
            contenu.ecrire(document, ecrivain);
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

    @FunctionalInterface
    private interface ContenuAvecEcrivain {
        void ecrire(Document document, PdfWriter ecrivain) throws DocumentException;
    }

    /** Accumulateur générique : sert aussi bien par bénéficiaire que par structure ou régime. */
    /** Rang d'un type de structure (PS, CS, EPS… dans l'ordre de l'énumération) ; inconnu en dernier. */
    private static int rangType(String type) {
        for (TypeStructure t : TypeStructure.values()) {
            if (t.name().equals(type)) return t.ordinal();
        }
        return TypeStructure.values().length;
    }

    private static final class Agregat {
        private final LigneFactureStructure reference;
        private String libelle;
        private String type;
        private int nombreActes;
        private int nombreFactures;
        private int nombreBeneficiaires;
        private double montant;
        private double montantBeneficiaire;
        private double montantSencsu;
        /** Part SEN-CSU des factures encore en brouillon ou rejetées. */
        private double montantSencsuEnCours;

        Agregat(LigneFactureStructure reference) {
            this.reference = reference;
        }

        Agregat(FactureStructure facture) {
            this.reference = null;
            this.libelle = facture.getStructureNom();
            this.type = facture.getTypeStructure() == null ? "" : facture.getTypeStructure().name();
        }

        /** Structure de l'annuaire du district, à zéro tant qu'aucune facture ne s'y ajoute. */
        Agregat(StructureDto structure) {
            this.reference = null;
            this.libelle = structure.getNom();
            this.type = structure.getTypeStructure() == null ? "" : structure.getTypeStructure().name();
        }

        void ajouter(LigneFactureStructure ligne) {
            nombreActes++;
            montant += ligne.getMontant();
            montantBeneficiaire += ligne.getMontantBeneficiaire();
            montantSencsu += ligne.getMontantSencsu();
        }

        void ajouter(FactureStructure facture) {
            nombreFactures++;
            nombreBeneficiaires += facture.getNombreBeneficiaires();
            montant += facture.getMontantTotal();
            montantBeneficiaire += facture.getMontantTotalBeneficiaire();
            montantSencsu += facture.getMontantTotalSencsu();
            if (facture.getStatut() == null || !facture.getStatut().estComptabilise()) {
                montantSencsuEnCours += facture.getMontantTotalSencsu();
            }
        }
    }
}
