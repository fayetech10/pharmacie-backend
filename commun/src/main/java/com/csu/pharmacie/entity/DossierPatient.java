package com.csu.pharmacie.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Dossier d'un patient dans la facture mensuelle d'un point de soins : son identité, le
 * résultat du contrôle d'éligibilité et ses pièces justificatives.
 *
 * <p>Stocké dans le jsonb de la facture, comme les lignes : aucune table, aucune
 * migration. Les lignes y renvoient par {@link LigneFactureStructure#getDossierId()} et
 * gardent leur copie de l'identité — la liste nominative, l'export Excel et la
 * consolidation continuent de lire les lignes seules.</p>
 *
 * <p>Le dossier existe indépendamment de ses lignes : un patient peut être enregistré
 * avant que toutes ses prestations soient connues (validation partielle). Il reste alors
 * {@link #A_COMPLETER} et bloque l'envoi de la facture tant qu'il n'est pas complété.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DossierPatient {

    /** Patient enregistré, prestations ou pièces encore attendues. */
    public static final String A_COMPLETER = "A_COMPLETER";
    /** Dossier facturable. */
    public static final String COMPLET = "COMPLET";

    private String id;
    private String statut;

    // ─── Identité ───
    private String nom;
    private String prenom;
    private String sexe;
    private LocalDate dateNaissance;
    private String telephone;
    private String adresse;
    /**
     * Identifiant du bénéficiaire dans son régime : code d'immatriculation (régimes à
     * carte), matricule généré (Plan Sésame), n° d'extrait de naissance (enfants 0-5 ans).
     */
    private String matricule;
    private String numeroCni;
    /** Enfants 0-5 ans : n° dans le registre de consultation. */
    private String numeroRegistre;
    /** Enfants 0-5 ans : l'adulte qui accompagne l'enfant — sa CNI sert de clé de rapprochement. */
    private String nomAccompagnant;
    private String cniAccompagnant;

    // ─── Rapprochement avec l'import de la structure (centres et EPS) ───
    /**
     * Date de prise en charge de la visite saisie par le BCSU : avec la clé, elle identifie
     * la ligne de facture que le fichier de fin de mois viendra compléter.
     */
    private LocalDate datePriseEnCharge;
    /** Identifiant du régime, normalisé (cf. {@code CleRapprochement}). */
    private String cleRapprochement;

    // ─── Éligibilité ───
    /**
     * Identité confirmée par le contrôle d'éligibilité (ou par la lettre de garantie) : le
     * nom et le prénom, et l'identifiant qui a servi au contrôle, ne se modifient plus à la
     * main. Le reste (sexe, date de naissance, pièce, coordonnées) reste corrigeable —
     * cahier « Espace PS », §5.2 et §6.3.
     */
    private boolean identiteVerrouillee;
    /** Issue du contrôle (cf. {@link VerificationCarte}). */
    private String verificationCarte;
    private String categorieCarte;
    private String etatCarte;
    private Integer tauxCouverture;
    private LocalDate dateDebutValidite;
    private LocalDate dateFinValidite;
    private LocalDateTime dateVerification;
    /** Mutuelle de rattachement, quand le système de gestion des assurés la fournit. */
    private String mutuelle;
    private String typeAdhesion;
    /**
     * Saisie d'un centre depuis une lettre de garantie : l'agent BCSU a déjà contrôlé
     * l'éligibilité et l'identité, la lettre fait foi.
     */
    private String lettreGarantieNumero;
    /**
     * Lettre à l'origine du dossier, par son identifiant : le dossier d'un centre ou d'un EPS
     * est créé par la saisie du BCSU elle-même, et la suit (modification, annulation).
     */
    private String lettreGarantieId;

    // ─── Informations médicales ───
    private String diagnostic;

    /**
     * Pièces jointes au dossier, par type (cf. {@link PieceDossier}). Seul le type est
     * ici : les images vivent dans leur table, pour que la facture — relue à chaque
     * écran — ne transporte pas des centaines de photos.
     */
    @Builder.Default
    private List<String> pieces = new ArrayList<>();

    /**
     * Saisies déjà reçues pour ce dossier (cf. {@code RequeteFacturation#cleSynchronisation}) :
     * une saisie hors ligne renvoyée après une réponse perdue n'est pas comptée deux fois.
     */
    @Builder.Default
    private List<String> clesSynchronisation = new ArrayList<>();

    // ─── Pointage pendant le contrôle de la facture ───
    // Une facture peut compter cent patients et plus : chaque échelon de contrôle les coche
    // un à un, « vérifié », à mesure qu'il les juge conformes. Un pointage par échelon — celui
    // du centre ne vaut pas pour le service régional, qui fait son propre contrôle.
    /** Pointé conforme par le centre de rattachement (facture d'un poste). */
    private LocalDateTime verifieCentreLe;
    private String verifieCentrePar;
    /** Pointé conforme par le service régional. */
    private LocalDateTime verifieRegionLe;
    private String verifieRegionPar;

    // ─── Rejet par ligne (patient) pendant le contrôle ───
    // Symétrique du pointage : un échelon peut rejeter un patient précis avec un motif, au lieu
    // de rejeter toute la facture d'un bloc. Dès qu'un patient est rejeté, la facture repart à la
    // structure pour correction (le motif dit quoi corriger).
    /** Rejeté par le district (1er échelon, facture d'un poste). */
    private LocalDateTime rejeteCentreLe;
    private String rejeteCentrePar;
    private String rejeteCentreMotif;
    /** Rejeté par le service régional. */
    private LocalDateTime rejeteRegionLe;
    private String rejeteRegionPar;
    private String rejeteRegionMotif;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;

    public String nomComplet() {
        return ((prenom == null ? "" : prenom) + " " + (nom == null ? "" : nom)).trim();
    }

    public boolean estACompleter() {
        return A_COMPLETER.equals(statut);
    }

    public boolean possedePiece(String type) {
        return pieces != null && pieces.contains(type);
    }

    /**
     * Dossier d'un centre ou d'un EPS créé par la saisie du BCSU : il n'est complet qu'avec au
     * moins un ticket de caisse (arbitrage du 12/10/2026). Un dossier peut en compter plusieurs ;
     * {@link #pieces} n'en garde que le type.
     */
    public boolean exigeTicketDeCaisse() {
        return lettreGarantieId != null;
    }

    /** Il manque le ticket de caisse exigé. */
    public boolean attendTicketDeCaisse() {
        return exigeTicketDeCaisse() && !possedePiece(PieceDossier.TICKET_CAISSE);
    }

    /** Un patient rejeté à l'un des échelons de contrôle. */
    public boolean estRejete() {
        return rejeteCentreLe != null || rejeteRegionLe != null;
    }

    /** Efface tout rejet (le patient repart « à corriger » puis la structure le renvoie). */
    public void effacerRejet() {
        rejeteCentreLe = null;
        rejeteCentrePar = null;
        rejeteCentreMotif = null;
        rejeteRegionLe = null;
        rejeteRegionPar = null;
        rejeteRegionMotif = null;
    }
}
