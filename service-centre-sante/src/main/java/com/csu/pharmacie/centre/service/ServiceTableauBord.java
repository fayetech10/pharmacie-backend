package com.csu.pharmacie.centre.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.commun.client.dto.StructureDto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tableaux de bord (§10) : volumétrie, suivi mensuel et couverture des structures.
 *
 * <p>Construit à partir de la consolidation, donc sur les mêmes chiffres que les documents
 * officiels. Un tableau de bord qui ne tombe pas d'accord avec la facture qu'il résume ne
 * sert qu'à faire douter des deux.</p>
 */
@Service
@RequiredArgsConstructor
public class ServiceTableauBord {

    private final ServiceConsolidation serviceConsolidation;

    /** Tableau de bord d'un district pour une période, filtrable par régime. */
    public TableauBord construire(String districtId, Integer mois, Integer annee, Regime regime) {
        int an = annee == null || annee <= 0 ? LocalDate.now().getYear() : annee;
        ServiceConsolidation.SyntheseDistrict synthese =
                serviceConsolidation.synthese(districtId, mois, an, regime);

        TableauBord tableau = new TableauBord();
        tableau.setDistrictId(synthese.districtId);
        tableau.setDistrictNom(synthese.districtNom);
        tableau.setPeriode(synthese.periode);
        tableau.setNombreStructures(synthese.nombreStructures);
        tableau.setNombreBeneficiaires(synthese.nombreBeneficiaires);
        tableau.setNombrePrestations(synthese.nombrePrestations);
        tableau.setNombreFactures(synthese.nombreFactures);
        tableau.setMontantTotal(synthese.montantTotal);
        tableau.setMontantSencsu(synthese.montantSencsu);
        tableau.setMontantBeneficiaire(synthese.montantBeneficiaire);
        tableau.setMontantSencsuTransmis(synthese.montantSencsuTransmis);
        tableau.setMontantSencsuEnCours(synthese.montantSencsuEnCours);
        tableau.setNombreFacturesTransmises(synthese.nombreFacturesTransmises);
        tableau.setNombreFacturesEnCours(synthese.nombreFacturesEnCours);
        tableau.setTauxCouverture(synthese.getTauxCouverture());
        tableau.setStructuresFacturees(synthese.getStructuresFacturees().stream()
                .map(s -> ligne(s, true, synthese))
                .toList());
        tableau.setStructuresNonFacturees(synthese.getStructuresNonFacturees().stream()
                .map(s -> ligne(s, false, synthese))
                .toList());
        tableau.setParRegime(synthese.parRegime);

        // Suivi mensuel : on interroge l'année entière une seule fois, puis on répartit —
        // douze appels de consolidation coûteraient douze allers-retours vers le service poste.
        List<FactureStructure> annuelles = serviceConsolidation.factures(districtId, null, an, regime);
        Map<String, Double> parMois = new LinkedHashMap<>();
        Map<String, Integer> facturesParMois = new LinkedHashMap<>();
        for (int m = 1; m <= 12; m++) {
            parMois.put(FactureStructure.MOIS[m], 0.0);
            facturesParMois.put(FactureStructure.MOIS[m], 0);
        }
        for (FactureStructure facture : annuelles) {
            int m = facture.getMois();
            if (m < 1 || m > 12) {
                continue;
            }
            String libelle = FactureStructure.MOIS[m];
            parMois.merge(libelle, facture.getMontantTotalSencsu(), Double::sum);
            facturesParMois.merge(libelle, 1, Integer::sum);
        }
        tableau.setMontantParMois(parMois);
        tableau.setFacturesParMois(facturesParMois);

        return tableau;
    }

    private static LigneStructure ligne(StructureDto structure, boolean facturee,
                                        ServiceConsolidation.SyntheseDistrict synthese) {
        ServiceConsolidation.ResumeStructure resume = synthese.parStructure
                .getOrDefault(structure.getId(), ServiceConsolidation.ResumeStructure.VIDE);
        return new LigneStructure(structure.getId(), structure.getNom(),
                structure.getTypeStructure() == null ? null : structure.getTypeStructure().name(), facturee,
                resume.facturesTransmises(), resume.facturesEnCours(), resume.montantSencsu());
    }

    @Data
    public static class TableauBord {
        private String districtId;
        private String districtNom;
        private String periode;
        private int nombreStructures;
        private int nombreFactures;
        private int nombreBeneficiaires;
        private int nombrePrestations;
        private double montantTotal;
        private double montantBeneficiaire;
        private double montantSencsu;
        /** Part SEN-CSU déjà transmise (soumise au centre → payée). */
        private double montantSencsuTransmis;
        /** Part SEN-CSU encore en brouillon ou rejetée : à transmettre ou à corriger. */
        private double montantSencsuEnCours;
        private int nombreFacturesTransmises;
        private int nombreFacturesEnCours;
        /** Part des structures actives ayant facturé sur la période, en pourcentage. */
        private double tauxCouverture;
        private List<LigneStructure> structuresFacturees = new ArrayList<>();
        private List<LigneStructure> structuresNonFacturees = new ArrayList<>();
        private Map<String, Double> parRegime = Map.of();
        private Map<String, Double> montantParMois = Map.of();
        private Map<String, Integer> facturesParMois = Map.of();
    }

    public record LigneStructure(String id, String nom, String type, boolean facturee,
                                 int facturesTransmises, int facturesEnCours, double montantSencsu) {
    }
}
