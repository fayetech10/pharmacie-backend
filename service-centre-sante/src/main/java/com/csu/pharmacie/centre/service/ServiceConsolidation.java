package com.csu.pharmacie.centre.service;

import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.dto.DistrictDto;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.export.EnteteDocument;
import com.csu.pharmacie.commun.export.GenerateurExcel;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.export.GenerateurPdfEnfantsDistrict;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.utils.PdfFactureEnfants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consolidation au niveau district (§9).
 *
 * <p>Postes et centres écrivent dans la même table de factures : la consolidation les lit
 * donc d'une seule requête, sur la maille district. La liste des structures, elle, vient
 * du service identité — c'est la seule façon de voir celles qui n'ont <em>rien</em> envoyé,
 * qui sont précisément celles que le district doit relancer.</p>
 */
@Service
@RequiredArgsConstructor
public class ServiceConsolidation {

    private final ServiceFactureCentre serviceFacture;
    private final ClientIdentite clientIdentite;
    private final GenerateurExcel generateurExcel;
    private final GenerateurPdf generateurPdf;
    private final GenerateurPdfEnfantsDistrict generateurPdfEnfants;

    /** Factures d'un district sur la période, postes et centres confondus, brouillons compris. */
    public List<FactureStructure> factures(String districtId, Integer mois, Integer annee, Regime regime) {
        return serviceFacture.facturesDuDistrict(districtAutorise(districtId), mois, annee, regimePourAppelant(regime));
    }

    /**
     * Régime effectivement consolidé. Depuis le 09/10/2026, les postes ne transmettent au
     * district que le 0-5 ans : un compte district (ou centre siège) ne consolide donc que ce
     * régime. Un superviseur national (SR / SC / ADMIN) garde la vue tous régimes (ou le régime
     * qu'il a demandé).
     */
    private Regime regimePourAppelant(Regime regimeDemande) {
        Role role = ContexteUtilisateur.courant().getRole();
        boolean superviseurNational = role == Role.ADMIN || role == Role.SERVICE_CENTRAL
                || role == Role.SERVICE_REGIONAL;
        return superviseurNational ? regimeDemande : Regime.ZERO_CINQ_ANS;
    }

    /**
     * Suivi de la couverture : qui a facturé, qui n'a pas facturé (§10).
     *
     * <p>Les totaux couvrent toutes les factures de la période ; leur part « transmise »
     * (soumise au centre, envoyée, validée, payée) et leur part « en cours » (brouillon ou
     * rejetée, donc à transmettre ou à corriger) sont données à côté.</p>
     */
    public SyntheseDistrict synthese(String districtId, Integer mois, Integer annee, Regime regime) {
        String district = districtAutorise(districtId);
        List<StructureDto> structures = clientIdentite.structuresDuDistrict(district);
        List<FactureStructure> factures = serviceFacture.facturesDuDistrict(district, structures, mois, annee,
                regimePourAppelant(regime));

        Set<String> ontFacture = factures.stream()
                .map(FactureStructure::getStructureSanitaireId)
                .collect(Collectors.toSet());

        SyntheseDistrict synthese = new SyntheseDistrict();
        synthese.districtId = district;
        DistrictDto dto = clientIdentite.district(district);
        synthese.districtNom = dto != null ? dto.getNom()
                : factures.stream().map(FactureStructure::getDistrictNom)
                .filter(nom -> nom != null && !nom.isBlank()).findFirst().orElse(null);
        synthese.periode = libellePeriode(mois, annee);
        synthese.nombreStructures = structures.size();
        synthese.nombreFactures = factures.size();
        synthese.nombreBeneficiaires = factures.stream()
                .mapToInt(FactureStructure::getNombreBeneficiaires).sum();
        synthese.nombrePrestations = factures.stream()
                .mapToInt(f -> f.getLignes() == null ? 0 : f.getLignes().size()).sum();
        synthese.montantTotal = factures.stream().mapToDouble(FactureStructure::getMontantTotal).sum();
        synthese.montantBeneficiaire = factures.stream()
                .mapToDouble(FactureStructure::getMontantTotalBeneficiaire).sum();
        synthese.montantSencsu = factures.stream()
                .mapToDouble(FactureStructure::getMontantTotalSencsu).sum();
        for (FactureStructure facture : factures) {
            boolean transmise = facture.getStatut().estComptabilise();
            if (transmise) {
                synthese.nombreFacturesTransmises++;
                synthese.montantSencsuTransmis += facture.getMontantTotalSencsu();
            } else {
                synthese.nombreFacturesEnCours++;
                synthese.montantSencsuEnCours += facture.getMontantTotalSencsu();
            }
            synthese.parStructure.merge(facture.getStructureSanitaireId() + "",
                    new ResumeStructure(transmise ? 1 : 0, transmise ? 0 : 1, facture.getMontantTotalSencsu()),
                    ResumeStructure::plus);
        }

        for (StructureDto structure : structures) {
            if (!structure.isActif()) {
                continue;
            }
            if (ontFacture.contains(structure.getId())) {
                synthese.structuresFacturees.add(structure);
            } else {
                synthese.structuresNonFacturees.add(structure);
            }
        }

        synthese.parRegime = factures.stream()
                .filter(f -> f.getRegime() != null)
                .collect(Collectors.groupingBy(
                        f -> f.getRegime().getLibelle(),
                        java.util.LinkedHashMap::new,
                        Collectors.summingDouble(FactureStructure::getMontantTotalSencsu)));
        return synthese;
    }

    /**
     * Classeur Excel (§9) : synthèse, facturation facture par facture, récapitulatif régimes,
     * puis une feuille par poste / centre portant sa facture.
     */
    public byte[] excel(String districtId, Integer mois, Integer annee, Regime regime) {
        String district = districtAutorise(districtId);
        Regime effectif = regimePourAppelant(regime);
        List<StructureDto> structures = clientIdentite.structuresDuDistrict(district);
        List<FactureStructure> factures = serviceFacture.facturesDuDistrict(district, structures, mois, annee, effectif);
        return generateurExcel.consolidationDistrict(factures, structures,
                entete(district, factures, mois, annee, effectif, "Consolidation des factures du district"));
    }

    /**
     * PDF de situation globale du district (§9) : toutes ses structures — postes, centres de
     * santé et EPS rattachés —, à zéro quand elles n'ont rien facturé.
     */
    public byte[] pdf(String districtId, Integer mois, Integer annee, Regime regime) {
        String district = districtAutorise(districtId);
        Regime effectif = regimePourAppelant(regime);
        List<StructureDto> structures = clientIdentite.structuresDuDistrict(district);
        List<FactureStructure> factures = serviceFacture.facturesDuDistrict(district, structures, mois, annee, effectif);
        return generateurPdf.situationDistrict(factures, structures,
                entete(district, factures, mois, annee, effectif, "Situation globale du district"));
    }

    /**
     * Enfants de moins de 5 ans — situation globale du district (modèle RECAP_ENFANTS_5ANS) :
     * une ligne par structure, nombre de cas au forfait, montant, total en lettres.
     */
    public byte[] situationEnfants(String districtId, Integer mois, Integer annee) {
        DonneesEnfants donnees = donneesEnfants(districtId, mois, annee);
        return generateurPdfEnfants.situationGlobale(donnees.factures(), donnees.structures(), donnees.contexte());
    }

    /**
     * Enfants de moins de 5 ans — liste nominative consolidée du district (modèle
     * LISTE_ENFANTS_5ANS) : chaque enfant et ses visites, puis le récapitulatif par structure.
     */
    public byte[] listeEnfants(String districtId, Integer mois, Integer annee) {
        DonneesEnfants donnees = donneesEnfants(districtId, mois, annee);
        return generateurPdfEnfants.listeNominative(donnees.factures(), donnees.structures(), donnees.contexte());
    }

    /**
     * « Situation 0-5 ans » du district (demande du 07/10/2026), 1/2 : le classeur des factures
     * « Enfants de moins de 5 ans », une feuille par poste / centre — sans les feuilles
     * Synthèse, Facturation et Par régime.
     */
    public byte[] excelEnfants(String districtId, Integer mois, Integer annee) {
        String district = districtAutorise(districtId);
        List<StructureDto> structures = clientIdentite.structuresDuDistrict(district);
        List<FactureStructure> factures = serviceFacture.facturesDuDistrict(district, structures, mois, annee,
                Regime.ZERO_CINQ_ANS);
        return generateurExcel.consolidationDistrict(factures, structures,
                entete(district, factures, mois, annee, Regime.ZERO_CINQ_ANS, "Situation 0-5 ans du district"), false);
    }

    /**
     * « Situation 0-5 ans » du district, 2/2 : un seul PDF qui réunit, poste par poste puis
     * centre par centre, la « Liste + facture 0-5 ans » de chacun — page de garde (la facture)
     * puis liste nominative, le document même que la structure télécharge depuis sa facture.
     */
    public byte[] facturesEnfantsPdf(String districtId, Integer mois, Integer annee) {
        DonneesEnfants donnees = donneesEnfants(districtId, mois, annee);
        GenerateurPdfEnfantsDistrict.Contexte ctx = donnees.contexte();
        // Une seule facture globale du district : totaux (patients, prestations, montant) de
        // tous les postes et centres cumulés, au lieu d'une facture par structure.
        return PdfFactureEnfants.factureGlobale(donnees.factures(), ctx.districtNom(), ctx.regionNom(),
                ctx.mois(), ctx.annee());
    }

    private record DonneesEnfants(List<FactureStructure> factures, Map<String, StructureDto> structures,
                                  GenerateurPdfEnfantsDistrict.Contexte contexte) {}

    /** Même périmètre que la synthèse : factures du district, rattrapées par structure. */
    private DonneesEnfants donneesEnfants(String districtId, Integer mois, Integer annee) {
        String district = districtAutorise(districtId);
        List<StructureDto> structures = clientIdentite.structuresDuDistrict(district);
        List<FactureStructure> factures = serviceFacture.facturesDuDistrict(district, structures, mois, annee,
                Regime.ZERO_CINQ_ANS);
        DistrictDto dto = clientIdentite.district(district);
        String nom = dto != null ? dto.getNom() : factures.stream().map(FactureStructure::getDistrictNom)
                .filter(n -> n != null && !n.isBlank()).findFirst().orElse(null);
        int an = annee == null || annee <= 0 ? LocalDate.now().getYear() : annee;
        Integer moisVise = mois == null || mois < 1 || mois > 12 ? null : mois;
        return new DonneesEnfants(factures,
                structures.stream().filter(s -> s.getId() != null)
                        .collect(Collectors.toMap(StructureDto::getId, s -> s, (a, b) -> a)),
                new GenerateurPdfEnfantsDistrict.Contexte(nom, dto == null ? null : dto.getRegionNom(), moisVise, an));
    }

    // ────────────────────────────── règles ──────────────────────────────

    /**
     * Détermine et contrôle le district à consolider. Un compte de district ne consolide
     * que le sien : accepter un identifiant arbitraire donnerait à chaque district la
     * visibilité sur tous les autres.
     */
    private String districtAutorise(String districtIdDemande) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        Role role = utilisateur.getRole();
        boolean superviseurNational = role == Role.ADMIN || role == Role.SERVICE_CENTRAL
                || role == Role.SERVICE_REGIONAL;

        if (superviseurNational) {
            if (districtIdDemande == null || districtIdDemande.isBlank()) {
                throw ExceptionMetier.invalide("Précisez le district à consolider");
            }
            return districtIdDemande;
        }

        String districtUtilisateur = utilisateur.getDistrictId();
        if (districtUtilisateur == null || districtUtilisateur.isBlank()) {
            throw ExceptionMetier.interdit("Votre compte n'est rattaché à aucun district : "
                    + "la consolidation ne peut pas déterminer son périmètre.");
        }
        if (districtIdDemande != null && !districtIdDemande.isBlank()
                && !districtIdDemande.equals(districtUtilisateur)) {
            throw ExceptionMetier.interdit("Vous ne pouvez consolider que votre propre district.");
        }
        return districtUtilisateur;
    }

    private EnteteDocument entete(String districtId, List<FactureStructure> factures, Integer mois,
                                  Integer annee, Regime regime, String titre) {
        DistrictDto district = clientIdentite.district(districtId);
        return EnteteDocument.builder()
                .titre(titre)
                .districtNom(district != null ? district.getNom()
                        : factures.stream().map(FactureStructure::getDistrictNom)
                        .filter(nom -> nom != null && !nom.isBlank()).findFirst().orElse(null))
                .regionNom(district == null ? null : district.getRegionNom())
                .periode(libellePeriode(mois, annee))
                .regimeLibelle(regime == null ? "Tous régimes" : regime.getLibelle())
                .build();
    }

    private static String libellePeriode(Integer mois, Integer annee) {
        int an = annee == null || annee <= 0 ? LocalDate.now().getYear() : annee;
        if (mois == null || mois < 1 || mois > 12) {
            return "Année " + an;
        }
        return FactureStructure.MOIS[mois] + " " + an;
    }

    /** Synthèse d'un district, telle que l'affiche le tableau de bord. */
    public static class SyntheseDistrict {
        public String districtId;
        public String districtNom;
        public String periode;
        public int nombreStructures;
        public int nombreFactures;
        public int nombreBeneficiaires;
        public int nombrePrestations;
        public double montantTotal;
        public double montantBeneficiaire;
        public double montantSencsu;
        /** Part SEN-CSU des factures transmises (soumise au centre → payée). */
        public double montantSencsuTransmis;
        /** Part SEN-CSU des factures encore en brouillon ou rejetées (à transmettre / corriger). */
        public double montantSencsuEnCours;
        public int nombreFacturesTransmises;
        public int nombreFacturesEnCours;
        /** Par identifiant de structure : factures transmises, en cours et part SEN-CSU. */
        public final Map<String, ResumeStructure> parStructure = new java.util.HashMap<>();
        public final List<StructureDto> structuresFacturees = new ArrayList<>();
        public final List<StructureDto> structuresNonFacturees = new ArrayList<>();
        public Map<String, Double> parRegime = Map.of();

        /** Taux de couverture : part des structures actives ayant facturé sur la période. */
        public double getTauxCouverture() {
            int total = structuresFacturees.size() + structuresNonFacturees.size();
            return total == 0 ? 0 : Math.round(structuresFacturees.size() * 1000.0 / total) / 10.0;
        }

        public List<StructureDto> getStructuresFacturees() {
            return structuresFacturees;
        }

        public List<StructureDto> getStructuresNonFacturees() {
            return structuresNonFacturees;
        }
    }

    /** Facturation d'une structure sur la période. */
    public record ResumeStructure(int facturesTransmises, int facturesEnCours, double montantSencsu) {

        public static final ResumeStructure VIDE = new ResumeStructure(0, 0, 0);

        ResumeStructure plus(ResumeStructure autre) {
            return new ResumeStructure(facturesTransmises + autre.facturesTransmises,
                    facturesEnCours + autre.facturesEnCours, montantSencsu + autre.montantSencsu);
        }
    }
}
