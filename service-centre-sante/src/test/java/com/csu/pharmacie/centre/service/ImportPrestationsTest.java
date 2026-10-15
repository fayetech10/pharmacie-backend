package com.csu.pharmacie.centre.service;

import com.csu.pharmacie.commun.export.LecteurExcel;
import com.csu.pharmacie.commun.facturation.DepotFactureStructure;
import com.csu.pharmacie.commun.facturation.ParametresFacturation;
import com.csu.pharmacie.commun.facturation.ReglesFacturation;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.RapportRapprochement;
import com.csu.pharmacie.entity.RapportRapprochement.Anomalie;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Étape 2 de la facturation CS / EPS : l'import de fin de mois rapproche le fichier de la
 * structure des patients enregistrés par le BCSU (clé du régime + date + enfant), rapporte
 * tout ce qu'il ne rattache pas, et n'écrit rien avant la confirmation.
 */
class ImportPrestationsTest {

    private static final String CENTRE = "CS-1";
    // Ancien modèle, avec la colonne « Diagnostic » retirée depuis : elle doit être ignorée.
    private static final String ENTETE_SESAME = "N°;N° CNI;Date de prise en charge;Prénom(s);Nom;Service;Diagnostic;"
            + "Prestation;Code acte;Quantité;Prix unitaire (FCFA);Montant (FCFA)";

    private final DepotFactureStructure depot = mock(DepotFactureStructure.class);
    private final ServiceFactureCentre serviceFacture = mock(ServiceFactureCentre.class);
    private final ServiceImportPrestations service = new ServiceImportPrestations(depot, serviceFacture,
            new ReglesFacturation(new ParametresFacturation()));
    private FactureStructure facture;

    @BeforeEach
    void preparer() {
        facture = facture(Regime.SESAME,
                dossier("D1", "Ousmane", "Ndiaye", "1751199001234", LocalDate.of(2026, 10, 5), "G-1"),
                dossier("D2", "Awa", "Diop", "2751199005678", LocalDate.of(2026, 10, 6), "G-2"),
                dossier("D3", "Moussa", "Fall", "1751199009999", LocalDate.of(2026, 10, 7), "G-3"));
        when(depot.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(eq(CENTRE), any(), anyInt(), anyInt()))
                .thenAnswer(appel -> appel.getArgument(1) == facture.getRegime() ? Optional.of(facture) : Optional.empty());
        UtilisateurAuthentifie utilisateur = UtilisateurAuthentifie.builder()
                .id("agent-1").prenom("Agent").nom("Facturation").role(Role.CENTRE_SANTE).structureId(CENTRE).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(utilisateur, null, List.of()));
    }

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void lAperculRapprocheEtRapporteSansRienEnregistrer() {
        RapportRapprochement rapport = importer(Regime.SESAME, true, ENTETE_SESAME,
                "1;1 751 1990 01234;05/10/2026;Ousmane;Ndiaye;Médecine;Paludisme;Consultation;C1;1;2000;",
                ";;;;;;;Goutte épaisse;;1;1500;",                                      // prolonge la visite du dessus
                "2;2751199005678;07/10/2026;Awa;Diop;;;Consultation;;1;2000;",          // date différente
                "3;9999999999999;05/10/2026;Inconnu;Patient;;;Consultation;;1;2000;",   // clé inconnue
                "4;12AB;05/10/2026;Format;Invalide;;;Consultation;;1;2000;",            // CNI avec lettres
                "5;1751199001234;05/11/2026;Ousmane;Ndiaye;;;Consultation;;1;2000;",    // hors du mois
                "1;1751199001234;05/10/2026;Ousmane;Ndiaye;;;Consultation;C1;1;2000;",  // doublon de la ligne 2
                "6;1751199001234;05/10/2026;Ousmane;Ndiaye;;;Radio;;1;");                // prix manquant

        assertTrue(rapport.isSimulation());
        assertTrue(rapport.isApplicable());
        assertTrue(rapport.getErreurs().isEmpty(), () -> rapport.getErreurs().toString());
        assertEquals(8, rapport.getLignesLues());

        assertEquals(1, rapport.getRapprochees().size());
        RapportRapprochement.Visite visite = rapport.getRapprochees().get(0);
        assertEquals("D1", visite.dossierId());
        assertEquals(2, visite.prestations(), "la ligne sans identifiant prolonge la visite");
        assertEquals(3500, visite.montant());
        assertEquals(List.of(2, 3), visite.lignes());

        assertEquals(2, rapport.getSansCorrespondance().size());
        RapportRapprochement.SansCorrespondance autreDate = rapport.getSansCorrespondance().get(0);
        assertTrue(autreDate.cleConnue(), "Awa Diop est connue, à une autre date");
        assertEquals(List.of(LocalDate.of(2026, 10, 6)), autreDate.datesConnues());
        assertFalse(rapport.getSansCorrespondance().get(1).cleConnue());

        assertEquals(List.of(6, 7, 8, 9), rapport.getAnomalies().stream().map(Anomalie::ligne).toList());
        assertTrue(rapport.getAnomalies().get(0).motif().contains("format invalide"));
        assertTrue(rapport.getAnomalies().get(1).motif().contains("hors du mois"));
        assertTrue(rapport.getAnomalies().get(2).motif().contains("identique à la ligne 2"));
        assertTrue(rapport.getAnomalies().get(3).motif().contains("prix unitaire manquant"));

        assertEquals(List.of("D2", "D3"), rapport.getSansPrestations().stream()
                .map(RapportRapprochement.PatientEnAttente::dossierId).toList());
        verify(serviceFacture, never()).appliquerImport(anyString(), anyMap(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void laConfirmationTransmetLesPrestationsRapprocheesParDossier() {
        RapportRapprochement rapport = importer(Regime.SESAME, false, ENTETE_SESAME,
                "1;1751199001234;05/10/2026;Ousmane;Ndiaye;Médecine;Paludisme;Consultation;C1;1;2000;",
                ";;;;;;;Goutte épaisse;;2;1500;");

        assertFalse(rapport.isSimulation());
        ArgumentCaptor<Map<String, List<LigneFactureStructure>>> lignes = ArgumentCaptor.forClass(Map.class);
        verify(serviceFacture).appliquerImport(eq(facture.getId()), lignes.capture(), eq(rapport));
        List<LigneFactureStructure> d1 = lignes.getValue().get("D1");
        assertEquals(2, d1.size());
        assertEquals("Consultation", d1.get(0).getDesignation());
        assertEquals(2, d1.get(1).getQuantite());
        assertEquals(1500, d1.get(1).getPrixUnitaire());
        assertEquals("Médecine", d1.get(1).getService(), "la ligne de suite hérite du service de la visite");
    }

    @Test
    void uneFactureDejaTransmiseNeSeCompletePlus() {
        facture.setStatut(StatutFacture.ENVOYEE);
        RapportRapprochement rapport = importer(Regime.SESAME, false, ENTETE_SESAME,
                "1;1751199001234;05/10/2026;Ousmane;Ndiaye;;;Consultation;;1;2000;");

        assertFalse(rapport.isApplicable());
        assertTrue(rapport.isSimulation(), "rien n'a été enregistré");
        assertTrue(rapport.getErreurs().get(0).contains("ne peut plus être complétée"));
        verify(serviceFacture, never()).appliquerImport(anyString(), anyMap(), any());
    }

    @Test
    void leModeleDUnAutreRegimeEstRefuse() {
        RapportRapprochement rapport = importer(Regime.SESAME, true,
                "N°;N° d'immatriculation;Date de prise en charge;Prénom(s);Nom;Prestation;Quantité;Prix unitaire (FCFA)",
                "1;V8658Y2605;05/10/2026;Ousmane;Ndiaye;Consultation;1;2000");

        assertFalse(rapport.isApplicable());
        assertTrue(rapport.getErreurs().get(0).contains("autre régime"));
    }

    @Test
    void uneColonneIndispensableManquanteEstSignalee() {
        RapportRapprochement rapport = importer(Regime.SESAME, true,
                "N° CNI;Date de prise en charge;Prestation;Quantité",
                "1751199001234;05/10/2026;Consultation;1");

        assertFalse(rapport.isApplicable());
        assertTrue(rapport.getErreurs().get(0).contains("Prix unitaire (FCFA)"));
    }

    @Test
    void lesEnfantsDuMemeAccompagnantSontDistinguesParLeurExtrait() {
        facture = facture(Regime.ZERO_CINQ_ANS,
                enfant("E1", "Fatou", "EXT-1"), enfant("E2", "Moussa", "EXT-2"));
        String entete = "N° CNI de l'accompagnant;Date de prise en charge;Prénom(s);Nom;N° extrait de naissance;"
                + "Prestation;Quantité;Prix unitaire (FCFA)";

        RapportRapprochement rapport = importer(Regime.ZERO_CINQ_ANS, true, entete,
                "1751199001234;05/10/2026;Fatou;Sow;EXT 1;Consultation;1;1400",
                "1751199001234;05/10/2026;Moussa;Sow;EXT-2;Consultation;1;1400",
                "1751199001234;05/10/2026;;Sow;;Vaccination;1;0");                    // aucun indice de l'enfant

        assertEquals(List.of("E1", "E2"), rapport.getRapprochees().stream()
                .map(RapportRapprochement.Visite::dossierId).toList());
        assertEquals(1, rapport.getAnomalies().size());
        assertTrue(rapport.getAnomalies().get(0).motif().contains("plusieurs patients"));
    }

    @Test
    void leModeleSeRemplitEtSeReimporteSansPerte() throws Exception {
        byte[] modele = service.modele(Regime.SESAME, 10, 2026);

        // Tel quel : trois patients du BCSU listés, aucune prestation, rien d'autre.
        RapportRapprochement vierge = service.analyser(LecteurExcel.lire(new ByteArrayInputStream(modele)),
                Regime.SESAME, 10, 2026, facture).rapport();
        assertEquals(3, vierge.getLignesLues(), "les lignes libres du modèle ne comptent pas");
        assertTrue(vierge.getErreurs().isEmpty(), () -> vierge.getErreurs().toString());
        assertTrue(vierge.getAnomalies().isEmpty(), () -> vierge.getAnomalies().toString());
        assertEquals(3, vierge.getSansPrestations().size());

        // Rempli comme le ferait la structure : une prestation par patient.
        byte[] rempli;
        try (XSSFWorkbook classeur = new XSSFWorkbook(new ByteArrayInputStream(modele));
             ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Sheet feuille = classeur.getSheetAt(0);
            List<String> entetes = new ArrayList<>();
            feuille.getRow(0).forEach(c -> entetes.add(c.getStringCellValue()));
            for (int r = 1; r <= 3; r++) {
                Row ligne = feuille.getRow(r);
                ligne.getCell(entetes.indexOf("Prestation")).setCellValue("Consultation");
                ligne.getCell(entetes.indexOf("Prix unitaire (FCFA)")).setCellValue(2000);
            }
            classeur.write(sortie);
            rempli = sortie.toByteArray();
        }
        RapportRapprochement complet = service.analyser(LecteurExcel.lire(new ByteArrayInputStream(rempli)),
                Regime.SESAME, 10, 2026, facture).rapport();
        assertEquals(3, complet.getRapprochees().size(), () -> complet.getAnomalies().toString());
        assertTrue(complet.getSansPrestations().isEmpty());
        assertEquals(6000, complet.getMontantImporte());
    }

    // ══════════════════════════ jeux d'essai ══════════════════════════

    private RapportRapprochement importer(Regime regime, boolean simulation, String... lignes) {
        byte[] csv = String.join("\n", lignes).getBytes(StandardCharsets.UTF_8);
        return service.importer(new MockMultipartFile("fichier", "octobre.csv", "text/csv", csv),
                regime, 10, 2026, simulation);
    }

    private static FactureStructure facture(Regime regime, DossierPatient... dossiers) {
        return FactureStructure.builder()
                .id("F-1")
                .numero("FS-202610-00001")
                .structureSanitaireId(CENTRE)
                .regime(regime)
                .mois(10)
                .annee(2026)
                .statut(StatutFacture.BROUILLON)
                .dossiers(new ArrayList<>(List.of(dossiers)))
                .lignes(new ArrayList<>())
                .build();
    }

    private static DossierPatient dossier(String id, String prenom, String nom, String cni, LocalDate date, String lettre) {
        return DossierPatient.builder()
                .id(id)
                .prenom(prenom)
                .nom(nom)
                .numeroCni(cni)
                .cleRapprochement(cni)
                .dateNaissance(LocalDate.of(1950, 1, 1))
                .datePriseEnCharge(date)
                .lettreGarantieId("L-" + id)
                .lettreGarantieNumero(lettre)
                .statut(DossierPatient.A_COMPLETER)
                .build();
    }

    private static DossierPatient enfant(String id, String prenom, String extrait) {
        return DossierPatient.builder()
                .id(id)
                .prenom(prenom)
                .nom("Sow")
                .matricule(extrait)
                .cniAccompagnant("1751199001234")
                .cleRapprochement("1751199001234")
                .dateNaissance(LocalDate.of(2024, 3, 1))
                .datePriseEnCharge(LocalDate.of(2026, 10, 5))
                .lettreGarantieId("L-" + id)
                .lettreGarantieNumero("G-" + id)
                .statut(DossierPatient.A_COMPLETER)
                .build();
    }
}
