package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Photo justificative d'un dossier patient : ticket de caisse, CNI, extrait de naissance.
 *
 * <p>Hors du jsonb de la facture, volontairement : une facture mensuelle compte des
 * dizaines de patients, et chaque écran qui la relit — « Mes factures », le centre de
 * rattachement — aurait téléchargé toutes leurs photos. Le dossier ne garde que la liste
 * des types présents ; l'image se lit à la demande.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pieces_dossier", indexes = {
        @Index(name = "idx_piece_dossier", columnList = "dossier_id"),
        @Index(name = "idx_piece_facture", columnList = "facture_id")
})
public class PieceDossier {

    public static final String TICKET_CAISSE = "TICKET_CAISSE";
    public static final String CNI_RECTO = "CNI_RECTO";
    public static final String CNI_VERSO = "CNI_VERSO";
    public static final String EXTRAIT_NAISSANCE = "EXTRAIT_NAISSANCE";

    /**
     * Photo de la carte d'assuré prise par l'agent au poste de santé (régimes à carte).
     * Facultative : elle ne conditionne pas l'envoi de la facture.
     */
    public static final String CARTE_ASSURE = "CARTE_ASSURE";

    /**
     * Photo du porteur renvoyée par le système des cartes CSU au contrôle d'éligibilité
     * (régimes à carte). Posée par le serveur seul : volontairement absente de {@link #TYPES}.
     */
    public static final String PHOTO_ASSURE = "PHOTO_ASSURE";

    /**
     * Types que l'écran peut fournir — une chaîne plutôt qu'un enum : aucune contrainte
     * CHECK à faire évoluer.
     */
    public static final List<String> TYPES =
            List.of(TICKET_CAISSE, CNI_RECTO, CNI_VERSO, EXTRAIT_NAISSANCE, CARTE_ASSURE);

    @Id
    @UuidGenerator
    private String id;

    @Column(nullable = false)
    private String factureId;

    @Column(nullable = false)
    private String dossierId;

    @Column(nullable = false, length = 32)
    private String type;

    /**
     * Code d'immatriculation du porteur — renseigné pour {@link #PHOTO_ASSURE} : c'est ce
     * qui permet de retrouver sa photo depuis sa fiche patient, hors de toute facture.
     */
    private String matricule;

    /** Image en data URL, compressée par le navigateur avant l'envoi. */
    @Column(columnDefinition = "text", nullable = false)
    private String image;

    private LocalDateTime createdAt;
    private String createdBy;

    public static String libelle(String type) {
        return switch (type == null ? "" : type) {
            case TICKET_CAISSE -> "Ticket de caisse";
            case CNI_RECTO -> "CNI (recto)";
            case CNI_VERSO -> "CNI (verso)";
            case EXTRAIT_NAISSANCE -> "Extrait de naissance";
            case CARTE_ASSURE -> "Carte d'assuré";
            case PHOTO_ASSURE -> "Photo de l'assuré (carte CSU)";
            default -> type;
        };
    }
}
