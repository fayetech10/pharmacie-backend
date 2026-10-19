package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.entity.DocumentComplementaire;
import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.LigneFactureStructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import com.csu.pharmacie.entity.PieceDossier;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.VerificationCarte;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saisie d'un patient et de ses prestations — miroir de {@code FactureStructureRequest}
 * côté dashboard.
 *
 * <p>C'est la façon dont l'écran de facturation travaille réellement : on saisit
 * <em>un patient à la fois</em> et ses actes sont ajoutés à la facture du mois pour son
 * régime. La facture mensuelle se construit ainsi par accumulation, ce qui correspond au
 * rythme du terrain (on facture au fil de l'eau, on transmet en fin de mois).</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RequeteFacturation {

    /** Régime du bénéficiaire — détermine la facture mensuelle qui reçoit les lignes. */
    private Regime regime;

    /** Période visée ; à défaut, le mois en cours. */
    private Integer mois;
    private Integer annee;

    /**
     * Date de prise en charge de la visite, choisie en tête de l'écran (le jour même par
     * défaut). Gardée par le dossier et reprise par ses prestations sans date ; elle ne
     * change plus quand le dossier est complété ensuite.
     */
    private LocalDate datePriseEnCharge;

    // ----- Bénéficiaire -----
    private String patientId;
    private String patientNom;
    private String patientPrenom;
    private String patientSexe;
    private LocalDate patientDateNaissance;
    private String patientTelephone;
    private String patientAdresse;
    private String patientNumeroCni;
    private String patientMatricule;
    /** Issue de la vérification de la carte CSU (cf. VerificationCarte). */
    private String verificationCarte;

    // ----- Rattachement au circuit BCSU (facultatif) -----
    private String lettreGarantieId;
    private String lettreGarantieNumero;
    private String feuilleSoinsId;
    private String feuilleSoinsNumero;

    // ----- Contexte médical -----
    private String service;

    // ----- Champs propres à certains régimes -----
    private String numeroRegistre;
    private String ircIra;
    private String motifCesarienne;
    private String indicationCbt;
    private String numeroRegistreBloc;
    private String dateHeureIntervention;
    private Integer dureeHospitalisationJours;

    /** Prestations saisies pour ce patient. */
    private List<LigneFactureStructure> lignes = new ArrayList<>();

    // ----- Pièces justificatives -----
    private String ticketCaisse;
    private List<DocumentComplementaire> documentsComplementaires = new ArrayList<>();

    /**
     * Photos du dossier patient, par type de {@link PieceDossier} (data URL). Une valeur
     * vide retire la pièce ; un type absent la laisse telle quelle.
     */
    private Map<String, String> pieces = new LinkedHashMap<>();

    /**
     * Validation partielle : le patient est enregistré, ses prestations ou ses pièces
     * suivront. Le dossier reste « à compléter » et la facture ne part pas sans lui.
     */
    private Boolean partiel;

    /**
     * Identifiant unique de la saisie, fixé par l'écran au moment de l'enregistrement. Une
     * saisie faite hors ligne est renvoyée tant que l'écran n'a pas reçu de réponse : si la
     * première tentative est arrivée mais que sa réponse s'est perdue, la seconde ne doit pas
     * ajouter le patient une deuxième fois. Le serveur reconnaît la clé et renvoie la facture.
     */
    private String cleSynchronisation;

    public boolean estPartiel() {
        return Boolean.TRUE.equals(partiel);
    }

    /** Dossier patient tel que saisi — le contrôle d'éligibilité peut encore le réécrire. */
    public DossierPatient versDossier() {
        return DossierPatient.builder()
                .nom(nettoyer(patientNom))
                .prenom(nettoyer(patientPrenom))
                .sexe(nettoyer(patientSexe))
                .dateNaissance(patientDateNaissance)
                .telephone(nettoyer(patientTelephone))
                .adresse(nettoyer(patientAdresse))
                .matricule(nettoyer(patientMatricule))
                .numeroCni(nettoyer(patientNumeroCni))
                .numeroRegistre(nettoyer(numeroRegistre))
                .verificationCarte(VerificationCarte.normaliser(verificationCarte))
                .pieces(new ArrayList<>())
                .build();
    }

    /**
     * Reporte sur la requête l'identité du dossier : après le contrôle d'éligibilité, c'est
     * elle qui fait foi, et {@link #lignesCompletees()} la recopie sur chaque ligne.
     */
    public void appliquerIdentite(DossierPatient dossier) {
        patientNom = dossier.getNom();
        patientPrenom = dossier.getPrenom();
        patientSexe = dossier.getSexe();
        patientDateNaissance = dossier.getDateNaissance();
        patientTelephone = dossier.getTelephone();
        patientAdresse = dossier.getAdresse();
        patientMatricule = dossier.getMatricule();
        patientNumeroCni = dossier.getNumeroCni();
        numeroRegistre = dossier.getNumeroRegistre();
        verificationCarte = dossier.getVerificationCarte();
    }

    private static String nettoyer(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }

    /**
     * Recopie l'identité et le contexte du patient sur chaque ligne saisie.
     *
     * <p>Les lignes portent l'identité en double, volontairement : une facture mensuelle
     * mélange des dizaines de patients, et chaque ligne doit rester lisible seule dans la
     * liste nominative comme dans le classeur officiel.</p>
     */
    public List<LigneFactureStructure> lignesCompletees() {
        List<LigneFactureStructure> completees = new ArrayList<>();
        for (LigneFactureStructure ligne : lignes == null ? List.<LigneFactureStructure>of() : lignes) {
            ligne.setPatientId(patientId);
            ligne.setPatientNom(patientNom);
            ligne.setPatientPrenom(patientPrenom);
            ligne.setPatientSexe(patientSexe);
            ligne.setPatientDateNaissance(patientDateNaissance);
            ligne.setPatientTelephone(patientTelephone);
            ligne.setPatientAdresse(patientAdresse);
            ligne.setPatientNumeroCni(patientNumeroCni);
            if (ligne.getPatientMatricule() == null || ligne.getPatientMatricule().isBlank()) {
                ligne.setPatientMatricule(patientMatricule);
            }
            ligne.setVerificationCarte(VerificationCarte.normaliser(verificationCarte));
            ligne.setLettreGarantieId(lettreGarantieId);
            ligne.setLettreGarantieNumero(lettreGarantieNumero);
            ligne.setFeuilleSoinsId(feuilleSoinsId);
            ligne.setFeuilleSoinsNumero(feuilleSoinsNumero);
            if (ligne.getService() == null) {
                ligne.setService(service);
            }
            if (ligne.getNumeroRegistre() == null) {
                ligne.setNumeroRegistre(numeroRegistre);
            }
            if (ligne.getIrcIra() == null) {
                ligne.setIrcIra(ircIra);
            }
            if (ligne.getMotifCesarienne() == null) {
                ligne.setMotifCesarienne(motifCesarienne);
            }
            if (ligne.getIndicationCbt() == null) {
                ligne.setIndicationCbt(indicationCbt);
            }
            if (ligne.getNumeroRegistreBloc() == null) {
                ligne.setNumeroRegistreBloc(numeroRegistreBloc);
            }
            if (ligne.getDateHeureIntervention() == null) {
                ligne.setDateHeureIntervention(dateHeureIntervention);
            }
            if (ligne.getDureeHospitalisationJours() == null) {
                ligne.setDureeHospitalisationJours(dureeHospitalisationJours);
            }
            if (ligne.getDatePriseEnCharge() == null) {
                ligne.setDatePriseEnCharge(datePriseEnCharge != null ? datePriseEnCharge : LocalDate.now());
            }
            completees.add(ligne);
        }
        return completees;
    }
}
