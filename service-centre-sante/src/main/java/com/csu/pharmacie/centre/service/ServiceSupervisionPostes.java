package com.csu.pharmacie.centre.service;

import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.TypeStructure;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contrôle des factures des postes de santé — par le DISTRICT (08/10/2026).
 *
 * <p>Avant, c'était le centre de rattachement qui validait ou rejetait les factures des
 * postes qu'il polarise. Ce contrôle est désormais réservé au district : il lit et tranche
 * sur les factures des postes de SON district, revérifié à chaque transition
 * ({@code ServiceFacturation.chargerPourDistrict}).</p>
 */
@Service
@RequiredArgsConstructor
public class ServiceSupervisionPostes {

    /** Statut d'une facture en attente d'une décision du district. */
    private static final StatutFacture A_TRAITER = StatutFacture.SOUMISE_CS;

    private final ServiceFactureCentre serviceFacture;
    private final ClientIdentite clientIdentite;

    /** District de l'appelant — seul un compte district contrôle les factures des postes. */
    private static String districtCourant() {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        // Compte district, ou centre de santé siège du district (double casquette).
        if (!utilisateur.aCapaciteDistrict()) {
            throw ExceptionMetier.interdit("Le contrôle des factures des postes de santé est réservé au district.");
        }
        if (utilisateur.getDistrictId() == null || utilisateur.getDistrictId().isBlank()) {
            throw ExceptionMetier.interdit("Votre compte district n'est rattaché à aucun district.");
        }
        return utilisateur.getDistrictId();
    }

    /**
     * Structures dont le district contrôle les factures 0-5 ans : postes et centres de santé
     * (siège compris), pour le filtre de l'écran — pas les EPS, qui transmettent directement.
     */
    public List<StructureDto> mesPostes() {
        return clientIdentite.structuresDuDistrict(districtCourant()).stream()
                .filter(s -> s.getTypeStructure() != null && s.getTypeStructure().passeParLeDistrict(Regime.ZERO_CINQ_ANS))
                .toList();
    }

    /** Factures transmises par les postes du district. */
    public List<FactureStructure> facturesDesPostes(Integer mois, Integer annee, Regime regime,
                                                    StatutFacture statut) {
        return serviceFacture.facturesDesPostesDuDistrict(districtCourant(), mois, annee, regime, statut);
    }

    /**
     * Vue repliée « un bloc par poste » : c'est ce que l'écran affiche par défaut.
     * Un centre peut polariser des dizaines de postes qui émettent chacun une facture par
     * régime et par mois — la liste à plat devient vite illisible.
     */
    public List<BlocPoste> facturesGroupees(Integer mois, Integer annee, Regime regime, StatutFacture statut) {
        Map<String, BlocPoste> parPoste = new LinkedHashMap<>();

        for (FactureStructure facture : facturesDesPostes(mois, annee, regime, statut)) {
            BlocPoste bloc = parPoste.computeIfAbsent(facture.getStructureSanitaireId(),
                    id -> new BlocPoste(id, facture.getStructureNom()));
            bloc.factures.add(facture);
            bloc.montantSencsu += facture.getMontantTotalSencsu();
            bloc.nombreBeneficiaires += facture.getNombreBeneficiaires();
            if (facture.getStatut() == A_TRAITER) {
                bloc.nombreATraiter++;
            }
        }

        List<BlocPoste> blocs = new ArrayList<>(parPoste.values());
        // Les postes qui attendent une décision passent devant : c'est le travail à faire.
        blocs.sort(Comparator.comparing((BlocPoste b) -> b.nombreATraiter > 0 ? 0 : 1)
                .thenComparing(b -> b.posteNom == null ? "" : b.posteNom));
        return blocs;
    }

    public FactureStructure valider(String factureId) {
        return serviceFacture.validerParDistrict(factureId);
    }

    public FactureStructure rejeter(String factureId, String motif) {
        if (motif == null || motif.isBlank()) {
            throw ExceptionMetier.invalide("Le motif du rejet est obligatoire");
        }
        return serviceFacture.rejeterParDistrict(factureId, motif);
    }

    /**
     * Validation en lot. Chaque facture est traitée indépendamment : une erreur sur l'une
     * ne doit pas annuler les autres, sans quoi un lot de trente factures devient
     * ingérable dès qu'une seule pose problème.
     */
    public ResultatLot validerLot(List<String> factureIds) {
        ResultatLot resultat = new ResultatLot();
        for (String id : factureIds) {
            try {
                valider(id);
                resultat.valides++;
            } catch (RuntimeException e) {
                resultat.echecs++;
                resultat.messages.add(id + " : " + e.getMessage());
            }
        }
        return resultat;
    }

    /** Un poste et ses factures, tel que l'écran de supervision l'affiche. */
    public static class BlocPoste {
        public final String posteId;
        public final String posteNom;
        public final List<FactureStructure> factures = new ArrayList<>();
        public int nombreATraiter;
        public int nombreBeneficiaires;
        public double montantSencsu;

        BlocPoste(String posteId, String posteNom) {
            this.posteId = posteId;
            this.posteNom = posteNom;
        }

        public int getNombreFactures() {
            return factures.size();
        }
    }

    public static class ResultatLot {
        public int valides;
        public int echecs;
        public final List<String> messages = new ArrayList<>();

        public String resume() {
            return echecs == 0
                    ? valides + " facture(s) validée(s) par le district et transmise(s) au service régional"
                    : valides + " facture(s) validée(s), " + echecs + " en échec";
        }
    }
}
