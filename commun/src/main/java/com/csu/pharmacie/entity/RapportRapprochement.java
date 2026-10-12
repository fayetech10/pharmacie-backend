package com.csu.pharmacie.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Rapport de rapprochement de l'import de fin de mois d'un centre ou d'un EPS (spécification
 * « Facturation CS / EPS », §4) : ce que le fichier a apporté aux patients enregistrés par le
 * BCSU, et ce qu'il n'a pas pu leur rattacher.
 *
 * <p>Renvoyé tel quel par l'aperçu (rien n'est enregistré) et par l'import confirmé ; le
 * dernier import confirmé est conservé sur la facture, pour être réaffiché.</p>
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RapportRapprochement {

    private LocalDateTime date;
    private String utilisateurNom;
    private String fichier;
    /** Aperçu : le rapport décrit ce que l'import ferait, rien n'a été enregistré. */
    private boolean simulation;
    /** L'import peut être confirmé (facture modifiable, au moins une visite rapprochée). */
    private boolean applicable;

    private Regime regime;
    private int mois;
    private int annee;
    /** Facture du mois ; nuls si aucun patient n'a encore été enregistré par le BCSU. */
    private String factureId;
    private String factureNumero;

    /** Lignes non vides du fichier. */
    private int lignesLues;
    /** Prestations rattachées à un patient du BCSU. */
    private int prestationsImportees;
    /** Somme des quantités × prix du fichier, pour ces prestations. */
    private double montantImporte;
    /** Patients déjà importés, absents de ce fichier : leurs prestations sont conservées. */
    private int dossiersConserves;

    private List<Visite> rapprochees = new ArrayList<>();
    private List<SansCorrespondance> sansCorrespondance = new ArrayList<>();
    private List<PatientEnAttente> sansPrestations = new ArrayList<>();
    private List<Anomalie> anomalies = new ArrayList<>();
    /** Erreurs qui empêchent toute lecture du fichier (colonne absente, mauvais modèle…). */
    private List<String> erreurs = new ArrayList<>();
    private List<String> avertissements = new ArrayList<>();

    /** Une visite du BCSU complétée par le fichier, et les lignes du fichier qui la complètent. */
    public record Visite(String dossierId, String patient, String cle, LocalDate date, String lettreGarantieNumero,
                         int prestations, double montant, List<Integer> lignes) {
    }

    /**
     * Lignes du fichier qu'aucun patient du BCSU n'attend. {@code cleConnue} : l'identifiant
     * existe, mais à d'autres dates ({@code datesConnues}) — souvent une erreur de date.
     */
    public record SansCorrespondance(List<Integer> lignes, String patient, String cle, LocalDate date,
                                     int prestations, boolean cleConnue, List<LocalDate> datesConnues) {
    }

    /** Patient enregistré par le BCSU qui reste sans prestation : son dossier reste « à compléter ». */
    public record PatientEnAttente(String dossierId, String patient, String cle, LocalDate date,
                                   String lettreGarantieNumero) {
    }

    /** Ligne du fichier non importée : format de clé, date hors du mois, doublon, prix manquant… */
    public record Anomalie(int ligne, String motif) {
    }
}
