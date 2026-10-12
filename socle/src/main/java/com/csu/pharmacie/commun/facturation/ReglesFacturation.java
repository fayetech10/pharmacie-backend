package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.entity.LigneFactureStructure;

import com.csu.pharmacie.entity.FactureStructure;
import lombok.RequiredArgsConstructor;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.commun.web.ExceptionMetier;

import java.time.LocalDate;
import java.time.Period;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Règles automatiques de facturation (§4 du document « Fonctionnalités SEN-CSU »).
 *
 * <p>Tout ce qui se calcule est calculé ici, et uniquement ici : forfait des enfants de
 * moins de 5 ans, contrôle d'âge, répartition prise en charge SEN-CSU / ticket modérateur,
 * matricule du Plan Sésame. Les services de facturation (poste, centre) appellent
 * {@link #appliquer} avant chaque enregistrement — les montants envoyés par le client sont
 * donc systématiquement écrasés, ce qui rend impossible la facturation d'un montant
 * arbitraire depuis le navigateur.</p>
 */
@RequiredArgsConstructor
public class ReglesFacturation {

    private final ParametresFacturation parametres;

    /**
     * Recalcule intégralement une facture : montants de chaque ligne, répartition,
     * totaux et nombre de bénéficiaires. Les contrôles bloquants (âge) sont exécutés
     * en premier pour ne rien enregistrer d'incohérent.
     */
    public void appliquer(FactureStructure facture) {
        Regime regime = facture.getRegime();
        if (regime == null) {
            throw ExceptionMetier.invalide("Le régime de la facture est obligatoire");
        }
        List<LigneFactureStructure> lignes = facture.getLignes();
        if (lignes == null || lignes.isEmpty()) {
            facture.setMontantTotal(0);
            facture.setMontantTotalBeneficiaire(0);
            facture.setMontantTotalSencsu(0);
            return;
        }

        double total = 0;
        double totalBeneficiaire = 0;
        double totalSencsu = 0;
        Set<String> beneficiaires = new HashSet<>();
        // Suivi du forfait « une fois par enfant » (mode PAR_BENEFICIAIRE).
        Set<String> forfaitDejaApplique = new HashSet<>();

        for (LigneFactureStructure ligne : lignes) {
            controlerAge(regime, ligne);
            appliquerMatricule(regime, ligne, facture.getStructureSanitaireId());

            double montant = montantLigne(regime, ligne, forfaitDejaApplique);
            ligne.setMontant(arrondir(montant));

            double partBeneficiaire = arrondir(montant * regime.tauxBeneficiaire());
            // La part SEN-CSU est le reste (et non un second arrondi) : la somme des deux
            // parts doit tomber exactement sur le montant, sinon les totaux du PDF officiel
            // ne se rapprochent plus au franc près.
            ligne.setMontantBeneficiaire(partBeneficiaire);
            ligne.setMontantSencsu(arrondir(montant - partBeneficiaire));

            total += ligne.getMontant();
            totalBeneficiaire += ligne.getMontantBeneficiaire();
            totalSencsu += ligne.getMontantSencsu();
            beneficiaires.add(ligne.cleBeneficiaire());
        }

        facture.setMontantTotal(arrondir(total));
        facture.setMontantTotalBeneficiaire(arrondir(totalBeneficiaire));
        facture.setMontantTotalSencsu(arrondir(totalSencsu));
    }

    /**
     * Montant d'une ligne : quantité × prix unitaire, sauf régime forfaitaire actif où
     * le forfait paramétré se substitue au prix du référentiel.
     */
    private double montantLigne(Regime regime, LigneFactureStructure ligne, Set<String> forfaitDejaApplique) {
        int quantite = Math.max(1, ligne.getQuantite());
        ligne.setQuantite(quantite);

        // Saisie directe (Espace PS) ou sur lettre de garantie : deux forfaits distincts.
        boolean surLettre = ligne.getLettreGarantieId() != null || ligne.getLettreGarantieNumero() != null;
        double forfait = surLettre ? parametres.getForfaitZeroCinqAns() : parametres.getForfaitEspacePs();
        ParametresFacturation.ModeForfait mode = surLettre ? parametres.getModeForfait() : parametres.getModeForfaitEspacePs();
        if (!regime.estForfaitaire() || forfait <= 0) {
            return quantite * ligne.getPrixUnitaire();
        }

        if (mode == ParametresFacturation.ModeForfait.PAR_LIGNE) {
            ligne.setPrixUnitaire(forfait);
            return quantite * forfait;
        }

        // PAR_BENEFICIAIRE : le forfait couvre l'épisode de soins, une seule fois par enfant.
        // L'épisode, c'est le dossier patient quand il existe ; à défaut, le bénéficiaire. Un
        // enfant revenu plusieurs fois dans le mois partage un seul dossier (les visites y
        // sont réunies à la saisie), donc un seul forfait.
        String episode = ligne.getDossierId() != null ? ligne.getDossierId() : ligne.cleBeneficiaire();
        if (forfaitDejaApplique.add(episode)) {
            ligne.setPrixUnitaire(forfait);
            return forfait;
        }
        ligne.setPrixUnitaire(0);
        return 0;
    }

    /**
     * Contrôle d'âge des régimes qui en imposent un (§4 : « contrôle automatique de l'âge
     * pour le régime Enfants de moins de 5 ans »). L'âge est apprécié à la date de prise en
     * charge, pas à la date de saisie : une facture d'un mois antérieur reste valide.
     */
    public void controlerAge(Regime regime, LigneFactureStructure ligne) {
        Integer ageMax = regime.ageMaximumAnnees();
        Integer ageMin = regime.ageMinimumAnnees();
        if ((ageMax == null && ageMin == null) || !parametres.isControleAgeBloquant()) {
            return;
        }
        LocalDate naissance = ligne.getPatientDateNaissance();
        if (naissance == null) {
            throw ExceptionMetier.invalide("Régime « " + regime.getLibelle()
                    + " » : la date de naissance de " + libelleBeneficiaire(ligne) + " est obligatoire "
                    + "(elle conditionne le contrôle d'âge).");
        }
        LocalDate reference = ligne.getDatePriseEnCharge() == null ? LocalDate.now() : ligne.getDatePriseEnCharge();
        if (naissance.isAfter(reference)) {
            throw ExceptionMetier.invalide("Date de naissance postérieure à la date de prise en charge pour "
                    + libelleBeneficiaire(ligne) + ".");
        }
        int age = Period.between(naissance, reference).getYears();

        if (ageMax != null && age >= ageMax) {
            throw ExceptionMetier.invalide("Régime « " + regime.getLibelle() + " » : "
                    + libelleBeneficiaire(ligne) + " a " + age + " ans à la date de prise en charge ("
                    + reference + "). Ce régime est réservé aux enfants de moins de " + ageMax + " ans.");
        }
        if (ageMin != null && age < ageMin) {
            throw ExceptionMetier.invalide("Régime « " + regime.getLibelle() + " » : "
                    + libelleBeneficiaire(ligne) + " a " + age + " ans à la date de prise en charge ("
                    + reference + "). Ce régime est réservé aux personnes de " + ageMin + " ans et plus.");
        }
    }

    /**
     * Génère le matricule du Plan Sésame quand il manque (§4).
     * Format : {@code SES-<code structure>-<empreinte bénéficiaire>} — stable pour un même
     * bénéficiaire dans une même structure, donc rejouable sans créer de doublon.
     */
    public void appliquerMatricule(Regime regime, LigneFactureStructure ligne, String structureId) {
        if (!regime.exigeMatricule()) {
            return;
        }
        if (ligne.getPatientMatricule() != null && !ligne.getPatientMatricule().isBlank()) {
            return;
        }
        ligne.setPatientMatricule(genererMatriculeSesame(parametres.getPrefixeMatriculeSesame(), structureId, ligne));
    }

    /**
     * Matricule déterministe : deux saisies du même bénéficiaire dans la même structure
     * produisent le même matricule, ce qui évite les doublons quand une facture rejetée
     * est ressaisie.
     */
    public static String genererMatriculeSesame(String prefixe, String structureId, LigneFactureStructure ligne) {
        String empreinte = Integer.toHexString(
                (structureId + "|" + ligne.cleBeneficiaire()).hashCode()).toUpperCase(Locale.ROOT);
        String court = (structureId == null || structureId.length() < 4)
                ? "0000" : structureId.substring(0, 4).toUpperCase(Locale.ROOT);
        return "%s-%s-%8s".formatted(prefixe, court, empreinte).replace(' ', '0');
    }

    /** Le franc CFA n'a pas de subdivision : tous les montants sont arrondis à l'unité. */
    public static double arrondir(double montant) {
        return Math.round(montant);
    }

    private static String libelleBeneficiaire(LigneFactureStructure ligne) {
        String nom = ligne.nomComplet();
        return nom.isBlank() ? "ce bénéficiaire" : "« " + nom + " »";
    }
}
