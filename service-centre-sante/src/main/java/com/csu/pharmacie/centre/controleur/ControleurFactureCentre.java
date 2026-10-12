package com.csu.pharmacie.centre.controleur;

import com.csu.pharmacie.centre.dto.ResultatImportFactures;
import com.csu.pharmacie.centre.service.ServiceFactureCentre;
import com.csu.pharmacie.centre.service.ServiceImportFactures;
import com.csu.pharmacie.centre.service.ServiceImportPrestations;
import com.csu.pharmacie.centre.service.ServiceSupervisionPostes;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.facturation.ControleurFacturation;
import com.csu.pharmacie.commun.facturation.ServiceFacturation;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.RapportRapprochement;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.StatutFacture;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Facturation du centre de santé / EPS, sur le chemin historique
 * {@code /api/factures-structure}.
 *
 * <p>S'y ajoutent les routes que seul un centre expose : supervision des postes polarisés
 * ({@code /postes-rattaches}, {@code /{id}/valider-poste}, {@code /{id}/rejeter-poste}) et
 * l'étape service régional, la passerelle aiguillant le SR vers ce service.</p>
 */
@RestController
@RequestMapping("/api/factures-structure")
@RequiredArgsConstructor
public class ControleurFactureCentre extends ControleurFacturation {

    private final ServiceFactureCentre service;
    private final ServiceSupervisionPostes supervision;
    private final ServiceImportFactures importFactures;
    private final ServiceImportPrestations importPrestations;

    @Override
    protected ServiceFacturation service() {
        return service;
    }

    // ────────────────────────── supervision des postes polarisés ──────────────────────────

    /** Factures transmises par les postes de santé rattachés à ce centre. */
    @GetMapping("/postes-rattaches")
    public List<FactureStructure> facturesPostes(@RequestParam(required = false) Integer mois,
                                                 @RequestParam(required = false) Integer annee,
                                                 @RequestParam(required = false) Regime regime,
                                                 @RequestParam(required = false) StatutFacture statut) {
        return supervision.facturesDesPostes(mois, annee, regime, statut);
    }

    /** Même contenu, groupé par poste — la vue repliée de l'écran. */
    @GetMapping("/postes-rattaches/groupees")
    public List<ServiceSupervisionPostes.BlocPoste> facturesPostesGroupees(
            @RequestParam(required = false) Integer mois,
            @RequestParam(required = false) Integer annee,
            @RequestParam(required = false) Regime regime,
            @RequestParam(required = false) StatutFacture statut) {
        return supervision.facturesGroupees(mois, annee, regime, statut);
    }

    /** Liste des postes polarisés, pour les filtres de l'écran. */
    @GetMapping("/postes-rattaches/structures")
    public List<StructureDto> postes() {
        return supervision.mesPostes();
    }

    @PostMapping("/{id}/valider-poste")
    public FactureStructure validerPoste(@PathVariable String id) {
        return supervision.valider(id);
    }

    @PostMapping("/{id}/rejeter-poste")
    public FactureStructure rejeterPoste(@PathVariable String id, @RequestBody Map<String, String> corps) {
        // Le dashboard envoie le motif sous la clé « commentaire » ; « motif » est accepté aussi.
        return supervision.rejeter(id, corps.getOrDefault("commentaire", corps.get("motif")));
    }

    /** Validation en lot d'un mois complet pour un poste. */
    @PostMapping("/postes-rattaches/valider-lot")
    public ServiceSupervisionPostes.ResultatLot validerLot(@RequestBody List<String> factureIds) {
        return supervision.validerLot(factureIds);
    }

    // ────────────────────────── pointage pendant le contrôle ──────────────────────────

    /**
     * Pointe des patients « vérifié » (ou retire le pointage) pendant le contrôle de la
     * facture — par le centre de rattachement ou par le service régional, selon l'appelant.
     */
    @PostMapping("/{id}/verification")
    public FactureStructure pointer(@PathVariable String id, @RequestBody RequetePointage corps) {
        return service.pointerDossiers(id, corps.dossierIds(), corps.verifie());
    }

    /** Dossiers d'un patient (ses visites) et sens du pointage. */
    public record RequetePointage(List<String> dossierIds, boolean verifie) {
    }

    /**
     * Rejet « par ligne » : marque (ou annule) le rejet d'un ou plusieurs patients pendant le
     * contrôle, avec leur motif — district (1er échelon) ou service régional selon l'appelant.
     */
    @PostMapping("/{id}/rejet-lignes")
    public FactureStructure rejeterLignes(@PathVariable String id, @RequestBody RequeteRejetLignes corps) {
        return service.rejeterDossiers(id, corps.dossierIds(), corps.motif(), corps.rejete());
    }

    /** Dossiers des patients à rejeter (ou à dé-rejeter), et le motif commun. */
    public record RequeteRejetLignes(List<String> dossierIds, String motif, boolean rejete) {
    }

    // ────────────────────── reprise des factures de l'ancienne application ──────────────────────

    /**
     * Modèle Excel d'un régime : la facture de ce régime telle que l'application l'exporte,
     * préremplie de la structure, de la période et du n° d'origine s'ils sont donnés.
     */
    @GetMapping("/import/modele")
    public ResponseEntity<byte[]> modeleImport(@RequestParam Regime regime,
                                               @RequestParam(required = false) String structureId,
                                               @RequestParam(required = false) Integer mois,
                                               @RequestParam(required = false) Integer annee,
                                               @RequestParam(required = false) String numeroOrigine) {
        String nom = "modele_facture_" + regime.name().toLowerCase(java.util.Locale.ROOT) + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"")
                .contentType(MediaType.valueOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(importFactures.modele(regime, structureId, mois, annee, numeroOrigine));
    }

    /**
     * Import, par le Service Régional, d'une facture déjà générée par l'ancienne application
     * (un fichier = une facture, au format du régime) pour une structure de sa région. Tout ou
     * rien : le compte rendu liste les erreurs. {@code simulation=true} : contrôle et aperçu,
     * rien n'est enregistré.
     */
    @PostMapping("/import")
    public ResultatImportFactures importer(@RequestPart("fichier") MultipartFile fichier,
                                           @RequestParam String structureId,
                                           @RequestParam Regime regime,
                                           @RequestParam int mois,
                                           @RequestParam int annee,
                                           @RequestParam StatutFacture statut,
                                           @RequestParam(required = false) String numeroOrigine,
                                           @RequestParam(defaultValue = "false") boolean simulation) {
        return importFactures.importer(fichier,
                new ServiceImportFactures.Cible(structureId, regime, mois, annee, statut, numeroOrigine), simulation);
    }

    // ──────────── import de fin de mois du centre / EPS (prestations des patients du BCSU) ────────────

    /** Modèle d'import du régime pour le mois, prérempli des patients enregistrés par le BCSU. */
    @GetMapping("/rapprochement/modele")
    public ResponseEntity<byte[]> modeleRapprochement(@RequestParam Regime regime, @RequestParam int mois,
                                                      @RequestParam int annee) {
        String nom = "import_%s_%d-%02d.xlsx".formatted(regime.name().toLowerCase(java.util.Locale.ROOT), annee, mois);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"")
                .contentType(MediaType.valueOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(importPrestations.modele(regime, mois, annee));
    }

    /**
     * Import du fichier de fin de mois : rapprochement avec les patients du BCSU et rapport.
     * {@code simulation=true} : aperçu, rien n'est enregistré.
     */
    @PostMapping("/rapprochement")
    public RapportRapprochement importerRapprochement(@RequestPart("fichier") MultipartFile fichier,
                                                      @RequestParam Regime regime, @RequestParam int mois,
                                                      @RequestParam int annee,
                                                      @RequestParam(defaultValue = "true") boolean simulation) {
        return importPrestations.importer(fichier, regime, mois, annee, simulation);
    }

    /** Rapport du dernier import confirmé de la facture du mois (vide s'il n'y en a pas). */
    @GetMapping("/rapprochement/dernier")
    public ResponseEntity<RapportRapprochement> dernierRapprochement(@RequestParam Regime regime, @RequestParam int mois,
                                                                     @RequestParam int annee) {
        RapportRapprochement rapport = importPrestations.dernierRapport(regime, mois, annee);
        return rapport == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(rapport);
    }

    // ──────────── tickets de caisse en série (centre / EPS) ────────────

    /** Patients du BCSU auxquels rattacher un ticket : identifiant du régime et date de prise en charge. */
    @GetMapping("/tickets/visites")
    public ServiceFacturation.RechercheTicket visitesPourTicket(@RequestParam Regime regime,
                                                                @RequestParam String identifiant,
                                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                                LocalDate date) {
        return service.rechercherVisites(regime, identifiant, date);
    }

    /** Ajoute un ticket au dossier (un de plus, jamais à la place d'un autre). Corps : {@code {"image": "data:image/…"}}. */
    @PostMapping("/{id}/dossiers/{dossierId}/tickets")
    public ServiceFacturation.VisiteTicket ajouterTicket(@PathVariable String id, @PathVariable String dossierId,
                                                         @RequestBody Map<String, String> corps) {
        return service.ajouterTicket(id, dossierId, corps.get("image"));
    }

    /** Tickets du dossier, sans leur photo. */
    @GetMapping("/{id}/dossiers/{dossierId}/tickets")
    public List<ServiceFacturation.TicketResume> tickets(@PathVariable String id, @PathVariable String dossierId) {
        return service.tickets(id, dossierId);
    }

    /** Photo d'un ticket. */
    @GetMapping("/{id}/dossiers/{dossierId}/tickets/{ticketId}")
    public Map<String, String> ticket(@PathVariable String id, @PathVariable String dossierId,
                                      @PathVariable String ticketId) {
        return service.ticket(id, dossierId, ticketId);
    }

    @DeleteMapping("/{id}/dossiers/{dossierId}/tickets/{ticketId}")
    public ServiceFacturation.VisiteTicket retirerTicket(@PathVariable String id, @PathVariable String dossierId,
                                                         @PathVariable String ticketId) {
        return service.retirerTicket(id, dossierId, ticketId);
    }

    // ────────────────────────── étape service régional ──────────────────────────

    @PostMapping("/{id}/valider")
    public FactureStructure validerServiceRegional(@PathVariable String id,
                                                   @RequestBody(required = false) Map<String, String> corps) {
        return service.validerParServiceRegional(id, corps == null ? null : corps.get("commentaire"));
    }

    @PostMapping("/{id}/rejeter")
    public FactureStructure rejeterServiceRegional(@PathVariable String id,
                                                   @RequestBody Map<String, String> corps) {
        return service.rejeterParServiceRegional(id,
                corps.getOrDefault("commentaire", corps.get("motif")));
    }
}
