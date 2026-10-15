package com.csu.pharmacie.centre.service;

import com.csu.pharmacie.centre.dto.ResultatImportFactures;
import com.csu.pharmacie.centre.service.ServiceImportFactures.Cible;
import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.facturation.DepotFactureStructure;
import com.csu.pharmacie.commun.facturation.ParametresFacturation;
import com.csu.pharmacie.commun.facturation.ReglesFacturation;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.TypeStructure;
import com.csu.pharmacie.utils.ColonnesFactureStructure;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reprise des factures de l'ancienne application (09/10/2026) : un fichier = une facture, au
 * format de la facture Excel du régime (cellules d'identité fusionnées, lignes de suite sans
 * identité, arrêt aux totaux), statut d'origine, tout ou rien, périmètre du Service Régional.
 */
class ImportFacturesTest {

    private static final String POSTE = "poste-1";
    /** Première ligne de données dans les feuilles de test (comme dans le modèle). */
    private static final int DONNEES = 14;

    private final DepotFactureStructure depot = mock(DepotFactureStructure.class);
    private final ClientIdentite clientIdentite = mock(ClientIdentite.class);
    private final AtomicInteger compteur = new AtomicInteger();
    private final ServiceImportFactures service = new ServiceImportFactures(depot,
            new ReglesFacturation(new ParametresFacturation()), clientIdentite,
            (prefixe, annee, mois) -> "%s-%d%02d-%04d".formatted(prefixe, annee, mois, compteur.incrementAndGet()));
    private final List<FactureStructure> enregistrees = new ArrayList<>();

    @BeforeEach
    void preparer() {
        when(clientIdentite.structure(POSTE)).thenReturn(StructureDto.builder()
                .id(POSTE).code("PS-KM").nom("Poste de Keur Mbaye").typeStructure(TypeStructure.PS)
                .districtId("district-1").districtNom("Thiès").regionId("region-thies").build());
        when(depot.save(any(FactureStructure.class))).thenAnswer(appel -> {
            FactureStructure facture = appel.getArgument(0);
            facture.setId(UUID.randomUUID().toString());
            enregistrees.add(facture);
            return facture;
        });
        connecter(Role.SERVICE_REGIONAL, "region-thies");
    }

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    private static void connecter(Role role, String regionId) {
        UtilisateurAuthentifie utilisateur = UtilisateurAuthentifie.builder()
                .id("sr-1").prenom("Service").nom("Régional").role(role).regionId(regionId).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(utilisateur, null, List.of()));
    }

    private static Cible cible(Regime regime, StatutFacture statut) {
        return new Cible(POSTE, regime, 9, 2026, statut, null);
    }

    // ─────────────────────── fabrication des classeurs ───────────────────────

    private static List<String> entetes(Regime regime) {
        return ColonnesFactureStructure.pourRegime(regime).stream().map(c -> c.entete()).toList();
    }

    /** Valeurs d'une ligne, rangées sous leurs en-têtes : (« Nom », « DIOP », « P.U », 1000, …). */
    private static Object[] ligne(List<String> entetes, Object... paires) {
        Object[] valeurs = new Object[entetes.size()];
        for (int i = 0; i < paires.length; i += 2) {
            int colonne = entetes.indexOf((String) paires[i]);
            if (colonne < 0) throw new IllegalArgumentException("en-tête inconnu : " + paires[i]);
            valeurs[colonne] = paires[i + 1];
        }
        return valeurs;
    }

    /** Une feuille au format de la facture exportée : en-tête, titre du régime, référence, tableau. */
    private static Sheet feuille(Workbook wb, String nom, Regime regime, List<String> entetes, String reference,
                                 Object[]... lignes) {
        Sheet s = wb.createSheet(nom);
        s.createRow(0).createCell(0).setCellValue("POSTE DE KEUR MBAYE");
        s.createRow(3).createCell(0).setCellValue("Facture");
        Row date = s.createRow(5);
        date.createCell(0).setCellValue("DATE :");
        date.createCell(1).setCellValue("Septembre 2026");
        s.createRow(10).createCell(0).setCellValue(ColonnesFactureStructure.titreLong(regime));
        s.createRow(11).createCell(0).setCellValue("N° Référence facture : " + (reference == null ? "" : reference));
        Row entete = s.createRow(13);
        for (int c = 0; c < entetes.size(); c++) entete.createCell(c).setCellValue(entetes.get(c));
        for (int i = 0; i < lignes.length; i++) ecrire(s, DONNEES + i, lignes[i]);
        return s;
    }

    private static void ecrire(Sheet s, int ligne, Object[] valeurs) {
        Row row = s.getRow(ligne) != null ? s.getRow(ligne) : s.createRow(ligne);
        for (int c = 0; c < valeurs.length; c++) {
            Object v = valeurs[c];
            if (v == null) continue;
            Cell cell = row.getCell(c) != null ? row.getCell(c) : row.createCell(c);
            if (v instanceof Number n) cell.setCellValue(n.doubleValue());
            else cell.setCellValue(v.toString());
        }
    }

    private static void texte(Sheet s, int ligne, int colonne, String valeur) {
        Row row = s.getRow(ligne) != null ? s.getRow(ligne) : s.createRow(ligne);
        row.createCell(colonne).setCellValue(valeur);
    }

    private static MockMultipartFile fichier(Workbook wb) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return new MockMultipartFile("fichier", "facture.xlsx", null, out.toByteArray());
        }
    }

    private Object[] awa(List<String> e, String date, String prestation, int quantite, int pu) {
        return ligne(e, "Prénom(s)", "Awa", "Nom", "DIOP", "Date de naissance", "12/03/1985", "Sexe (H/F)", "F",
                "N° Tél", "771234567", "N° Matricule / Code Bénéficiaire", "V8658Y2605",
                "Date de prise en charge", date, "Service", "Consultation", "Prestation(s)", prestation,
                "Quantité", quantite, "P.U", pu, "Montant Total", quantite * pu);
    }

    // ─────────────────────── import ───────────────────────

    @Test
    void factureClassiqueAuFormatExporteIdentiteFusionneeArretAuxTotaux() throws Exception {
        List<String> e = entetes(Regime.CONTRIBUTIF);
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet s = feuille(wb, "Classique", Regime.CONTRIBUTIF, e, "F-0912",
                    awa(e, "15/09/2026", "Consultation générale", 1, 1000),
                    // 2e prestation de la même visite : identité, date et service fusionnés (cellules vides).
                    ligne(e, "Prestation(s)", "Goutte épaisse", "Quantité", 2, "P.U", 500, "Montant Total", 1000),
                    // Awa revient le 20 : seconde visite.
                    awa(e, "20/09/2026", "Consultation générale", 1, 1000));
            for (int c = 0; c <= 7; c++) s.addMergedRegion(new CellRangeAddress(DONNEES, DONNEES + 1, c, c));
            texte(s, DONNEES + 3, 0, "TOTAUX");
            texte(s, DONNEES + 5, 9, "MONTANT À RÉGLER PAR LA SEN-CSU");
            texte(s, DONNEES + 7, 0, "Arrêtée la présente facture, pour la part SEN-CSU, à la somme de trois mille francs CFA.");

            ResultatImportFactures r = service.importer(fichier(wb), cible(Regime.CONTRIBUTIF, StatutFacture.PAYEE), false);

            assertTrue(r.getErreurs().isEmpty(), () -> "erreurs inattendues : " + r.getErreurs());
            assertEquals(3, r.getLignesLues(), "la lecture s'arrête aux totaux");
            assertEquals(1, r.getFacturesCreees());
            FactureStructure f = enregistrees.get(0);
            assertEquals(Regime.CONTRIBUTIF, f.getRegime());
            assertEquals(StatutFacture.PAYEE, f.getStatut(), "statut d'origine choisi à l'écran");
            assertEquals(Boolean.TRUE, f.getImportee());
            assertEquals("F-0912", f.getNumeroOrigine(), "repris de « N° Référence facture »");
            assertEquals(9, f.getMois());
            assertEquals(2026, f.getAnnee());
            assertTrue(f.getNumero().startsWith("FS-202609-"), f.getNumero());
            assertEquals("region-thies", f.getRegionId());
            assertEquals(3, f.getLignes().size());
            assertEquals(2, f.getDossiers().size(), "deux dates = deux visites");
            LigneFactureStructure goutte = f.getLignes().get(1);
            assertEquals("Goutte épaisse", goutte.getDesignation());
            assertEquals("DIOP", goutte.getPatientNom(), "identité reprise de la cellule fusionnée");
            assertEquals(LocalDate.of(2026, 9, 15), goutte.getDatePriseEnCharge());
            assertEquals(f.getLignes().get(0).getDossierId(), goutte.getDossierId(), "même visite");
            assertTrue(f.getDossiers().stream().allMatch(d -> DossierPatient.COMPLET.equals(d.getStatut())));
            assertEquals(3000, f.getMontantTotal());
            assertEquals(600, f.getMontantTotalBeneficiaire(), "20 % patient");
            assertEquals(2400, f.getMontantTotalSencsu());
            assertTrue(f.getHistorique().get(0).getCommentaire().contains("F-0912"));
        }
    }

    @Test
    void enfantsLigneDeSuiteSansIdentiteEtForfaitParVisite() throws Exception {
        // Facture exportée avant le retrait du diagnostic (15/10/2026) : la colonne est ignorée sans avertissement.
        List<String> e = new java.util.ArrayList<>(entetes(Regime.ZERO_CINQ_ANS));
        e.add(e.indexOf("Prestations et médicaments") + 1, "Diagnostic / Motif de consultation");
        try (Workbook wb = new XSSFWorkbook()) {
            feuille(wb, "Enfants 0-5 ans", Regime.ZERO_CINQ_ANS, e, null,
                    ligne(e, "N° dans le registre", "12", "Prénom(s)", "Moussa", "Nom", "FALL",
                            "Date de naissance", "05/06/2023", "Sexe (M/F)", "M", "Adresse", "Keur Mbaye",
                            "N° Matricule / N° Extrait de naissance / N° accompagnant", "EXT-145",
                            "N° Téléphone", "776543210", "Date de prise en Charge", "18/09/2026", "Service", "Pédiatrie",
                            "Prestations et médicaments", "Consultation enfant",
                            "Diagnostic / Motif de consultation", "Fièvre", "Forfait", 1400, "Montant Total", 1400),
                    ligne(e, "Prestations et médicaments", "Paracétamol sirop", "Forfait", 0),
                    ligne(e, "N° dans le registre", "13", "Prénom(s)", "Fatou", "Nom", "SECK",
                            "Date de naissance", "01/01/2024", "Sexe (M/F)", "F",
                            "N° Matricule / N° Extrait de naissance / N° accompagnant", "EXT-146",
                            "Date de prise en Charge", "19/09/2026", "Service", "Pédiatrie",
                            "Prestations et médicaments", "Consultation enfant",
                            "Diagnostic / Motif de consultation", "Toux", "Forfait", 1400, "Montant Total", 1400));

            ResultatImportFactures r = service.importer(fichier(wb),
                    cible(Regime.ZERO_CINQ_ANS, StatutFacture.VALIDEE_SR), false);

            assertTrue(r.getErreurs().isEmpty(), () -> "erreurs : " + r.getErreurs());
            assertTrue(r.getAvertissements().stream().noneMatch(a -> a.contains("Diagnostic")),
                    () -> "avertissements : " + r.getAvertissements());
            FactureStructure f = enregistrees.get(0);
            assertEquals(3, f.getLignes().size());
            assertEquals(2, f.getDossiers().size());
            assertEquals(f.getLignes().get(0).getDossierId(), f.getLignes().get(1).getDossierId(),
                    "la ligne sans identité prolonge la visite du dessus");
            DossierPatient moussa = f.getDossiers().get(0);
            assertEquals("Keur Mbaye", moussa.getAdresse());
            assertEquals("12", moussa.getNumeroRegistre());
            assertEquals("EXT-145", moussa.getMatricule());
            assertEquals(2800, f.getMontantTotal(), "forfait de 1 400 F par visite");
            assertNull(f.getNumeroOrigine());
        }
    }

    @Test
    void leTableauDUnAutreRegimeEstRefuse() throws Exception {
        List<String> e = entetes(Regime.ZERO_CINQ_ANS);
        try (Workbook wb = new XSSFWorkbook()) {
            feuille(wb, "Enfants", Regime.ZERO_CINQ_ANS, e, null,
                    ligne(e, "Prénom(s)", "Moussa", "Nom", "FALL", "Date de prise en Charge", "18/09/2026",
                            "Prestations et médicaments", "Consultation enfant"));

            ResultatImportFactures r = service.importer(fichier(wb), cible(Regime.CONTRIBUTIF, StatutFacture.PAYEE), false);

            assertEquals(0, r.getFacturesCreees());
            verify(depot, never()).save(any());
            assertTrue(r.getErreurs().stream().anyMatch(m -> m.contains("ENFANTS DE MOINS DE 5 ANS")), r.getErreurs().toString());
        }
    }

    @Test
    void uneColonneObligatoireAbsenteEstSignalee() throws Exception {
        List<String> e = new ArrayList<>(entetes(Regime.CONTRIBUTIF));
        e.remove("Prestation(s)");
        try (Workbook wb = new XSSFWorkbook()) {
            feuille(wb, "Classique", Regime.CONTRIBUTIF, e, null,
                    ligne(e, "Prénom(s)", "Awa", "Nom", "DIOP", "Date de prise en charge", "15/09/2026"));

            ResultatImportFactures r = service.importer(fichier(wb), cible(Regime.CONTRIBUTIF, StatutFacture.PAYEE), false);

            assertEquals(0, r.getFacturesCreees());
            assertTrue(r.getErreurs().stream().anyMatch(m -> m.contains("« Prestation(s) »")), r.getErreurs().toString());
        }
    }

    @Test
    void desLignesFautivesEtRienNEstEnregistre() throws Exception {
        List<String> e = entetes(Regime.CONTRIBUTIF);
        try (Workbook wb = new XSSFWorkbook()) {
            feuille(wb, "Classique", Regime.CONTRIBUTIF, e, null,
                    awa(e, "15/09/2026", "Consultation générale", 1, 1000),
                    ligne(e, "Prénom(s)", "Binta", "Nom", "SALL", "Date de prise en charge", "32/13/2026",
                            "Prestation(s)", "Consultation générale", "P.U", 1000),
                    ligne(e, "Prénom(s)", "Coumba", "Nom", "BA", "Date de prise en charge", "16/09/2026"));

            ResultatImportFactures r = service.importer(fichier(wb), cible(Regime.CONTRIBUTIF, StatutFacture.PAYEE), false);

            assertEquals(0, r.getFacturesCreees());
            verify(depot, never()).save(any());
            // Données à partir de la ligne Excel 15.
            assertTrue(r.getErreurs().stream().anyMatch(m -> m.startsWith("Ligne 16 :") && m.contains("32/13/2026")),
                    r.getErreurs().toString());
            assertTrue(r.getErreurs().stream().anyMatch(m -> m.startsWith("Ligne 17 :") && m.contains("prestation manquante")),
                    r.getErreurs().toString());
            assertEquals(1, r.getFactures().size(), "l'aperçu montre quand même la facture");
            assertNull(r.getFactures().get(0).numero());
        }
    }

    @Test
    void uneFactureDejaPresentePourLeMemeMoisEstRefusee() throws Exception {
        when(depot.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(eq(POSTE), eq(Regime.CONTRIBUTIF), eq(9), eq(2026)))
                .thenReturn(Optional.of(new FactureStructure()));
        List<String> e = entetes(Regime.CONTRIBUTIF);
        try (Workbook wb = new XSSFWorkbook()) {
            feuille(wb, "Classique", Regime.CONTRIBUTIF, e, null, awa(e, "15/09/2026", "Consultation générale", 1, 1000));

            ResultatImportFactures r = service.importer(fichier(wb), cible(Regime.CONTRIBUTIF, StatutFacture.PAYEE), false);

            assertEquals(0, r.getFacturesCreees());
            assertTrue(r.getErreurs().stream().anyMatch(m -> m.contains("déjà une facture")), r.getErreurs().toString());
        }
    }

    @Test
    void laSimulationNEnregistreRienMaisDonneLApercu() throws Exception {
        List<String> e = entetes(Regime.CONTRIBUTIF);
        try (Workbook wb = new XSSFWorkbook()) {
            feuille(wb, "Classique", Regime.CONTRIBUTIF, e, "F-0912", awa(e, "15/09/2026", "Consultation générale", 2, 1000));

            ResultatImportFactures r = service.importer(fichier(wb), cible(Regime.CONTRIBUTIF, StatutFacture.PAYEE), true);

            assertTrue(r.getErreurs().isEmpty(), () -> "erreurs : " + r.getErreurs());
            verify(depot, never()).save(any());
            assertEquals(0, compteur.get(), "aucun numéro consommé");
            var apercu = r.getFactures().get(0);
            assertNull(apercu.numero());
            assertEquals("F-0912", apercu.numeroOrigine());
            assertEquals(2000, apercu.montantTotal());
            assertEquals(1, apercu.patients());
        }
    }

    @Test
    void leNumeroDOrigineSaisiALEcranPrimeSurCeluiDuFichier() throws Exception {
        List<String> e = entetes(Regime.CONTRIBUTIF);
        try (Workbook wb = new XSSFWorkbook()) {
            feuille(wb, "Classique", Regime.CONTRIBUTIF, e, "F-FICHIER", awa(e, "15/09/2026", "Consultation générale", 1, 1000));

            service.importer(fichier(wb),
                    new Cible(POSTE, Regime.CONTRIBUTIF, 9, 2026, StatutFacture.PAYEE, "F-ECRAN"), false);

            assertEquals("F-ECRAN", enregistrees.get(0).getNumeroOrigine());
        }
    }

    @Test
    void hemodialyseSansColonneDeDateNiDePrestation() throws Exception {
        List<String> e = entetes(Regime.HEMODIALYSE);
        try (Workbook wb = new XSSFWorkbook()) {
            feuille(wb, "Hémodialyse", Regime.HEMODIALYSE, e, null,
                    ligne(e, "Prénom(s)", "Ibrahima", "Nom", "KA", "Date de naissance", "01/01/1970", "Sexe (H/F)", "H",
                            "N° Matricule / N° CNI Patient / N° accompagnant", "V123", "IRC/IRA", "IRC",
                            "Nbre de Séances", 12, "Prix Unitaire", 50000, "Prix Total", 600000));

            ResultatImportFactures r = service.importer(fichier(wb), cible(Regime.HEMODIALYSE, StatutFacture.PAYEE), false);

            assertTrue(r.getErreurs().isEmpty(), () -> "erreurs : " + r.getErreurs());
            LigneFactureStructure l = enregistrees.get(0).getLignes().get(0);
            assertEquals(LocalDate.of(2026, 9, 1), l.getDatePriseEnCharge(), "1er jour de la période");
            assertEquals("Séance d'hémodialyse", l.getDesignation());
            assertEquals(12, l.getQuantite());
            assertEquals("IRC", l.getIrcIra());
            assertEquals("M", l.getPatientSexe(), "H → M");
            assertEquals(600000, enregistrees.get(0).getMontantTotal());
        }
    }

    @Test
    void cesarienneAvecColonneMontantAjoutee() throws Exception {
        List<String> e = new ArrayList<>(entetes(Regime.CESARIENNE));
        e.add("Montant (FCFA)");
        try (Workbook wb = new XSSFWorkbook()) {
            feuille(wb, "Césariennes", Regime.CESARIENNE, e, null,
                    ligne(e, "Prénom(s)", "Aminata", "Nom", "NDIAYE", "Date de naissance", "01/01/1995", "Sexe (H/F)", "F",
                            "Indication / Motif de CBT", "Dystocie", "Date et Heure Intervention", "15/09/2026 10h30",
                            "Durée Hospitalisation (jours)", 4, "Montant (FCFA)", 150000));

            ResultatImportFactures r = service.importer(fichier(wb), cible(Regime.CESARIENNE, StatutFacture.PAYEE), false);

            assertTrue(r.getErreurs().isEmpty(), () -> "erreurs : " + r.getErreurs());
            LigneFactureStructure l = enregistrees.get(0).getLignes().get(0);
            assertEquals("Césarienne", l.getDesignation());
            assertEquals(LocalDate.of(2026, 9, 15), l.getDatePriseEnCharge(), "date tirée de l'intervention");
            assertEquals("Dystocie", l.getIndicationCbt());
            assertEquals(4, l.getDureeHospitalisationJours());
            assertEquals(150000, enregistrees.get(0).getMontantTotal());
        }
    }

    @Test
    void leClasseurOfficielAPlusieursOngletsEstLuSurLOngletDuRegime() throws Exception {
        List<String> classique = entetes(Regime.CONTRIBUTIF);
        List<String> enfants = entetes(Regime.ZERO_CINQ_ANS);
        try (Workbook wb = new XSSFWorkbook()) {
            feuille(wb, "Classique", Regime.CONTRIBUTIF, classique, null,
                    awa(classique, "15/09/2026", "Consultation générale", 1, 1000));
            feuille(wb, "Enfants 0-5 ans", Regime.ZERO_CINQ_ANS, enfants, null,
                    ligne(enfants, "Prénom(s)", "Moussa", "Nom", "FALL", "Date de naissance", "05/06/2023",
                            "Date de prise en Charge", "18/09/2026", "Prestations et médicaments", "Consultation enfant"));

            ResultatImportFactures r = service.importer(fichier(wb), cible(Regime.ZERO_CINQ_ANS, StatutFacture.PAYEE), false);

            assertTrue(r.getErreurs().isEmpty(), () -> "erreurs : " + r.getErreurs());
            FactureStructure f = enregistrees.get(0);
            assertEquals(1, f.getLignes().size());
            assertEquals("FALL", f.getLignes().get(0).getPatientNom());
        }
    }

    @Test
    void unEnfantDePlusDeCinqAnsEstSignaleAvecSaLigne() throws Exception {
        List<String> e = entetes(Regime.ZERO_CINQ_ANS);
        try (Workbook wb = new XSSFWorkbook()) {
            feuille(wb, "Enfants", Regime.ZERO_CINQ_ANS, e, null,
                    ligne(e, "Prénom(s)", "Moussa", "Nom", "FALL", "Date de naissance", "05/06/2015",
                            "Date de prise en Charge", "18/09/2026", "Prestations et médicaments", "Consultation enfant"));

            ResultatImportFactures r = service.importer(fichier(wb), cible(Regime.ZERO_CINQ_ANS, StatutFacture.PAYEE), false);

            assertEquals(0, r.getFacturesCreees());
            assertTrue(r.getErreurs().stream().anyMatch(m -> m.startsWith("Ligne 15 :") && m.contains("11 ans")),
                    r.getErreurs().toString());
        }
    }

    @Test
    void leServiceRegionalNImportePasHorsDeSaRegionEtUnPosteJamais() throws Exception {
        List<String> e = entetes(Regime.CONTRIBUTIF);
        try (Workbook wb = new XSSFWorkbook()) {
            feuille(wb, "Classique", Regime.CONTRIBUTIF, e, null, awa(e, "15/09/2026", "Consultation générale", 1, 1000));
            MockMultipartFile f = fichier(wb);

            connecter(Role.SERVICE_REGIONAL, "region-dakar");
            ExceptionMetier refus = assertThrows(ExceptionMetier.class,
                    () -> service.importer(f, cible(Regime.CONTRIBUTIF, StatutFacture.PAYEE), false));
            assertTrue(refus.getMessage().contains("votre région"));

            connecter(Role.POSTE_SANTE, "region-thies");
            assertThrows(ExceptionMetier.class,
                    () -> service.importer(f, cible(Regime.CONTRIBUTIF, StatutFacture.PAYEE), false));
            verify(depot, never()).save(any());
        }
    }

    // ─────────────────────── modèle ───────────────────────

    @Test
    void chaqueColonneDuModeleDeChaqueRegimeEstReconnue() {
        for (Regime regime : Regime.values()) {
            assertEquals(ColonnesFactureStructure.pourRegime(regime).size(), ServiceImportFactures.attendus(regime).size(),
                    "une colonne du modèle « " + regime + " » n'est pas reconnue à l'import");
        }
    }

    @Test
    void leModeleEstLaFactureDuRegimeEtSImporteUneFoisRempli() throws Exception {
        byte[] modele = service.modele(Regime.CONTRIBUTIF, POSTE, 9, 2026, "F-1");
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(modele))) {
            Sheet s = wb.getSheetAt(0);
            assertEquals("Classique", s.getSheetName());
            assertEquals("POSTE DE KEUR MBAYE", s.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Septembre 2026", s.getRow(5).getCell(1).getStringCellValue());
            assertEquals(ColonnesFactureStructure.titreLong(Regime.CONTRIBUTIF), s.getRow(10).getCell(0).getStringCellValue());
            List<String> e = entetes(Regime.CONTRIBUTIF);
            for (int c = 0; c < e.size(); c++) {
                assertEquals(e.get(c), s.getRow(13).getCell(c).getStringCellValue());
            }
            assertNotNull(wb.getSheet("Aide"));

            ecrire(s, DONNEES, awa(e, "15/09/2026", "Consultation générale", 1, 1000));
            ecrire(s, DONNEES + 1, ligne(e, "Prestation(s)", "Goutte épaisse", "Quantité", 1, "P.U", 500));

            ResultatImportFactures r = service.importer(fichier(wb), cible(Regime.CONTRIBUTIF, StatutFacture.PAYEE), false);

            assertTrue(r.getErreurs().isEmpty(), () -> "erreurs : " + r.getErreurs());
            FactureStructure f = enregistrees.get(0);
            assertEquals("F-1", f.getNumeroOrigine());
            assertEquals(1, f.getDossiers().size());
            assertEquals(1500, f.getMontantTotal());
        }
    }

    @Test
    void lesModelesDeTousLesRegimesPortentLeursEnTetes() throws Exception {
        for (Regime regime : Regime.values()) {
            try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(service.modele(regime, null, null, null, null)))) {
                Row entete = wb.getSheetAt(0).getRow(13);
                List<String> e = entetes(regime);
                for (int c = 0; c < e.size(); c++) {
                    assertEquals(e.get(c), entete.getCell(c).getStringCellValue(), regime + " colonne " + c);
                }
            }
        }
    }
}
