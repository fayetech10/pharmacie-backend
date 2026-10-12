package com.csu.pharmacie.entity;

import com.csu.pharmacie.utils.CleRapprochement;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Facture d'une structure sanitaire : un dossier par lettre de garantie (un patient),
 * complété par l'agent puis transmis au Service Régional. Réutilise StatutFacture pour
 * le workflow de validation (BROUILLON → ENVOYEE → VALIDEE_SR / REJETEE_SR → ...).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "factures_structure", indexes = {
        @Index(name = "idx_fs_numero", columnList = "numero", unique = true),
        @Index(name = "idx_fs_structure", columnList = "structure_sanitaire_id"),
        @Index(name = "idx_fs_region", columnList = "region_id")
})
public class FactureStructure {
    @Id
    @UuidGenerator
    private String id;

    @Column(unique = true, nullable = false)
    private String numero;

    /**
     * Verrou optimiste. Deux écrivains se partagent désormais la facture d'un centre : le
     * BCSU (cœur), qui y place ses patients au fil du mois, et la structure
     * (service-centre-sante), qui la complète. Les dossiers et les lignes vivent dans des
     * colonnes JSON réécrites d'un bloc : sans ce contrôle, la seconde écriture effaçait
     * silencieusement la première. Elle échoue maintenant en 409, et l'utilisateur recommence.
     *
     * <p>{@code default 0} : la colonne arrive sur une table déjà peuplée, dont les lignes
     * doivent recevoir une version (une version nulle ferait prendre la facture pour une
     * nouvelle entité).</p>
     */
    @Version
    @Column(columnDefinition = "bigint default 0")
    private Long version;

    private String structureSanitaireId;
    private String structureNom;
    private String regionId;
    private String bcsuId;

    // ─── Contexte recopié à l'émission, pour que la facture reste lisible plus tard ───
    /** Type de la structure émettrice : il décide du circuit (PS → centre, CS/EPS → SR). */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(16)")
    private TypeStructure typeStructure;
    /** Pour un poste : centre de santé / EPS qui le polarise et valide sa facture. */
    private String structureRattachementId;
    /** District de consolidation (§9) — figé ici, la structure pouvant changer de district. */
    private String districtId;
    private String districtNom;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(255)")
    private Regime regime;

    // Classement « par régime et par mois » côté « Mes factures ».
    private int mois;
    private int annee;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<LigneFactureStructure> lignes = new ArrayList<>();

    private double montantTotal;
    private double montantTotalBeneficiaire;
    private double montantTotalSencsu;

    @Enumerated(EnumType.STRING)
    private StatutFacture statut;

    @Column(columnDefinition = "text")
    private String ticketCaisse;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<DocumentComplementaire> documentsComplementaires = new ArrayList<>();

    /**
     * Dossiers patients de la facture (identité, éligibilité, pièces). Colonne ajoutée
     * par le module Poste de Santé : nulle sur les factures antérieures, dont les
     * dossiers sont reconstitués à la lecture depuis les lignes.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<DossierPatient> dossiers = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<HistoriqueDossier> historique = new ArrayList<>();

    private String commentaireRejet;

    /**
     * Rapport du dernier import de fin de mois (centres et EPS), réaffichable. Hors du JSON de
     * la facture : il peut lister des centaines de lignes, et les listes de factures n'en ont
     * pas l'usage — il a sa propre route.
     */
    @JsonIgnore
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private RapportRapprochement dernierRapprochement;

    /**
     * Facture reprise de l'application utilisée avant la bascule (import du Service
     * Régional). Elle garde le statut qu'elle avait là-bas et n'est jamais touchée par la
     * clôture mensuelle automatique. {@code Boolean} et non {@code boolean} : la colonne est
     * ajoutée à une table déjà peuplée, elle doit pouvoir rester nulle sur les lignes existantes.
     */
    private Boolean importee;
    /** Numéro de la facture dans l'ancienne application, quand le fichier d'import le fournit. */
    private String numeroOrigine;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;

    /**
     * Bénéficiaires distincts de la facture — listes, consolidations et tableaux de bord.
     *
     * <p>Calculé et non stocké : la valeur se déduit des lignes, et une colonne aurait
     * imposé une migration sur une table déjà peuplée pour un chiffre qui peut se
     * désynchroniser du contenu. Les lignes sont de toute façon chargées avec la facture
     * (colonne JSON), le calcul ne coûte donc aucune requête.</p>
     */
    @Transient
    public int getNombreBeneficiaires() {
        if (lignes == null || lignes.isEmpty()) {
            return 0;
        }
        return (int) lignes.stream()
                .map(LigneFactureStructure::cleBeneficiaire)
                .distinct()
                .count();
    }

    // ─── Étapes d'une facture de centre / EPS (spécification « Facturation CS / EPS », §5) ───
    /** Le BCSU enregistre les patients ; aucun import n'a encore été confirmé. */
    public static final String ETAPE_EN_COURS = "EN_COURS_DE_SAISIE";
    /** Un import a été confirmé, mais des patients attendent encore prestations ou pièces. */
    public static final String ETAPE_IMPORTEE = "IMPORTEE";
    /** Prête à être transmise par la structure (elle ne part plus seule à la clôture du mois). */
    public static final String ETAPE_COMPLETE = "COMPLETE";
    /** Transmise : verrouillée pour la structure (sauf rejet). */
    public static final String ETAPE_TRANSMISE = "TRANSMISE";

    /**
     * Étape d'une facture de centre ou d'EPS des régimes à clé de rapprochement — {@code null}
     * pour les autres. Calculée et non stockée, comme le statut des dossiers : une valeur
     * de plus dans {@link StatutFacture} aurait figé une contrainte CHECK sur les bases
     * existantes, pour une information qui se déduit du contenu.
     */
    @Transient
    public String getEtape() {
        if (typeStructure == null || !typeStructure.polarise() || !CleRapprochement.regimeConcerne(regime)) {
            return null;
        }
        if (statut != null && !statut.estModifiable()) {
            return ETAPE_TRANSMISE;
        }
        if (estComplete()) {
            return ETAPE_COMPLETE;
        }
        return dernierRapprochement != null ? ETAPE_IMPORTEE : ETAPE_EN_COURS;
    }

    /** Prête à être transmise : des prestations, et plus aucun dossier « à compléter ». */
    public boolean estComplete() {
        return lignes != null && !lignes.isEmpty() && getNombreDossiersACompleter() == 0;
    }

    /** Patients enregistrés dont le dossier attend encore des prestations ou des pièces. */
    @Transient
    public int getNombreDossiersACompleter() {
        if (dossiers == null) {
            return 0;
        }
        // Un dossier du BCSU sans ticket de caisse compte toujours, quel que soit son statut
        // enregistré (il a pu être dit complet avant que le ticket ne soit exigé).
        return (int) dossiers.stream().filter(d -> d.estACompleter() || d.attendTicketDeCaisse()).count();
    }

    /** Libellé de la période, pour les en-têtes de documents. */
    public String periodeLibelle() {
        return MOIS[Math.max(1, Math.min(12, mois))] + " " + annee;
    }

    /** Ajoute une étape au parcours du dossier (la liste peut être nulle si elle vient du JSON). */
    public void tracer(HistoriqueDossier evenement) {
        if (historique == null) {
            historique = new ArrayList<>();
        }
        historique.add(evenement);
    }

    public static final String[] MOIS = {"", "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
            "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre"};
}
