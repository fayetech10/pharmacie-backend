package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.EnvoiGroupeRequest;
import com.csu.pharmacie.dto.FactureStructureRequest;
import com.csu.pharmacie.dto.ValidationRequest;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LettreGarantie;
import com.csu.pharmacie.service.FactureStructureService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/factures-structure")
@RequiredArgsConstructor
public class FactureStructureController {

    private final FactureStructureService factureService;
    private final com.csu.pharmacie.service.FactureStructureExportService exportService;

    @GetMapping("/{id}/export/excel")
    public ResponseEntity<byte[]> exportExcel(@PathVariable String id) {
        FactureStructure facture = factureService.getById(id);
        byte[] bytes = exportService.exportFactureStructureExcel(facture);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.valueOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", "Facture_Structure_" + facture.getNumero() + ".xlsx");
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    @GetMapping("/{id}/export/word")
    public ResponseEntity<byte[]> exportWord(@PathVariable String id) {
        FactureStructure facture = factureService.getById(id);
        byte[] bytes = exportService.exportFactureStructureWord(facture);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.valueOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        headers.setContentDispositionFormData("attachment", "Facture_Structure_" + facture.getNumero() + ".docx");
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    @GetMapping("/{id}/export/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable String id) {
        FactureStructure facture = factureService.getById(id);
        byte[] bytes = exportService.exportFactureStructurePdf(facture);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "Facture_Structure_" + facture.getNumero() + ".pdf");
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    @GetMapping("/export-groupe/excel")
    public ResponseEntity<?> exportGroupeExcel(
            @RequestParam String structureId,
            @RequestParam(required = false) String regime,
            @RequestParam int mois,
            @RequestParam int annee
    ) {
        try {
            com.csu.pharmacie.entity.Regime regimeEnum = null;
            if (regime != null && !regime.trim().isEmpty()) {
                try {
                    regimeEnum = com.csu.pharmacie.entity.Regime.valueOf(regime.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("Export Excel groupé : régime invalide « {} », ignoré.", regime);
                }
            }

            final com.csu.pharmacie.entity.Regime finalRegime = regimeEnum;
            List<FactureStructure> factures = factureService.getAllForCurrentUser().stream()
                    .filter(f -> f.getStructureSanitaireId() != null && f.getStructureSanitaireId().equals(structureId))
                    .filter(f -> finalRegime == null || f.getRegime() == finalRegime)
                    .filter(f -> f.getMois() == mois)
                    .filter(f -> f.getAnnee() == annee)
                    .toList();

            String nom = factures.isEmpty() ? "Structure" : factures.get(0).getStructureNom();
            byte[] bytes = exportService.exportGroupeExcel(factures, finalRegime, mois, annee, nom);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.valueOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDispositionFormData("attachment", "Facture_Globale_" + mois + "_" + annee + ".xlsx");
            return ResponseEntity.ok().headers(headers).body(bytes);
        } catch (Exception e) {
            log.error("Échec de l'export Excel groupé (structureId={}, mois={}, annee={})", structureId, mois, annee, e);
            return ResponseEntity.internalServerError().body("Erreur lors de l'export Excel groupé.");
        }
    }

    @GetMapping("/export-groupe/pdf")
    public ResponseEntity<?> exportGroupePdf(
            @RequestParam String structureId,
            @RequestParam(required = false) String regime,
            @RequestParam int mois,
            @RequestParam int annee
    ) {
        try {
            com.csu.pharmacie.entity.Regime regimeEnum = null;
            if (regime != null && !regime.trim().isEmpty()) {
                try {
                    regimeEnum = com.csu.pharmacie.entity.Regime.valueOf(regime.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("Export PDF groupé : régime invalide « {} », ignoré.", regime);
                }
            }

            final com.csu.pharmacie.entity.Regime finalRegime = regimeEnum;
            List<FactureStructure> factures = factureService.getAllForCurrentUser().stream()
                    .filter(f -> f.getStructureSanitaireId() != null && f.getStructureSanitaireId().equals(structureId))
                    .filter(f -> finalRegime == null || f.getRegime() == finalRegime)
                    .filter(f -> f.getMois() == mois)
                    .filter(f -> f.getAnnee() == annee)
                    .toList();

            String nom = factures.isEmpty() ? "Structure" : factures.get(0).getStructureNom();
            byte[] bytes = exportService.exportGroupePdf(factures, finalRegime, mois, annee, nom);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "Facture_Globale_" + mois + "_" + annee + ".pdf");
            return ResponseEntity.ok().headers(headers).body(bytes);
        } catch (Exception e) {
            log.error("Échec de l'export PDF groupé (structureId={}, mois={}, annee={})", structureId, mois, annee, e);
            return ResponseEntity.internalServerError().body("Erreur lors de l'export PDF groupé.");
        }
    }

    /** Recherche la lettre de garantie (scopée au BCSU de la structure) pour préremplir la facturation. */
    @GetMapping("/rechercher-lettre")
    public ResponseEntity<LettreGarantie> rechercherLettre(@RequestParam String numero) {
        return ResponseEntity.ok(factureService.rechercherLettre(numero));
    }

    @GetMapping
    public ResponseEntity<List<FactureStructure>> getAll(
            @RequestParam(required = false) Integer mois,
            @RequestParam(required = false) Integer annee,
            @RequestParam(required = false) String regime) {
        return ResponseEntity.ok(factureService.getAllForCurrentUser(mois, annee, regime));
    }

    /**
     * Identifiants + statuts des factures structure visibles : alimente les badges de
     * notification sans transférer (ni désérialiser) les lignes de facture.
     */
    @GetMapping("/statuts")
    public ResponseEntity<List<com.csu.pharmacie.dto.FactureStatutDto>> getStatuts() {
        return ResponseEntity.ok(factureService.getStatutsPourBadges());
    }

    /** Factures (envoyées et suivantes) des postes de santé rattachés à la structure courante. */
    @GetMapping("/postes-rattaches")
    public ResponseEntity<List<FactureStructure>> getFacturesPostesRattaches(
            @RequestParam(required = false) Integer mois,
            @RequestParam(required = false) Integer annee,
            @RequestParam(required = false) String regime) {
        return ResponseEntity.ok(factureService.getFacturesPostesRattaches(mois, annee, regime));
    }

    /** Supprime la saisie complète d'un patient (identifiée par sa lettre de garantie) avant l'envoi mensuel. */
    @DeleteMapping("/{id}/patients")
    public ResponseEntity<FactureStructure> supprimerPatient(@PathVariable String id, @RequestParam String lettre) {
        return ResponseEntity.ok(factureService.supprimerPatient(id, lettre));
    }

    /** Modifie une prestation (ligne) avant l'envoi mensuel. */
    @PutMapping("/{id}/lignes/{index}")
    public ResponseEntity<FactureStructure> modifierLigne(
            @PathVariable String id, @PathVariable int index,
            @Valid @RequestBody com.csu.pharmacie.dto.LigneFactureStructureDto dto) {
        return ResponseEntity.ok(factureService.modifierLigne(id, index, dto));
    }

    /** Supprime une prestation (ligne) avant l'envoi mensuel. */
    @DeleteMapping("/{id}/lignes/{index}")
    public ResponseEntity<FactureStructure> supprimerLigne(@PathVariable String id, @PathVariable int index) {
        return ResponseEntity.ok(factureService.supprimerLigne(id, index));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FactureStructure> getById(@PathVariable String id) {
        return ResponseEntity.ok(factureService.getById(id));
    }

    @PostMapping
    public ResponseEntity<FactureStructure> create(@Valid @RequestBody FactureStructureRequest request) {
        return ResponseEntity.ok(factureService.creer(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FactureStructure> update(@PathVariable String id, @Valid @RequestBody FactureStructureRequest request) {
        return ResponseEntity.ok(factureService.update(id, request));
    }

    @PostMapping("/{id}/envoyer")
    public ResponseEntity<FactureStructure> envoyer(@PathVariable String id) {
        return ResponseEntity.ok(factureService.envoyer(id));
    }

    /** Envoi groupé de la facture mensuelle (tous les brouillons du régime + mois/année). */
    @PostMapping("/envoyer-groupe")
    public ResponseEntity<List<FactureStructure>> envoyerGroupe(@Valid @RequestBody EnvoiGroupeRequest request) {
        return ResponseEntity.ok(factureService.envoyerGroupe(request.getRegime(), request.getMois(), request.getAnnee()));
    }

    @PostMapping("/{id}/valider")
    public ResponseEntity<FactureStructure> valider(@PathVariable String id, @RequestBody(required = false) ValidationRequest request) {
        return ResponseEntity.ok(factureService.valider(id, request != null ? request.getCommentaire() : null));
    }

    @PostMapping("/{id}/rejeter")
    public ResponseEntity<FactureStructure> rejeter(@PathVariable String id, @RequestBody ValidationRequest request) {
        return ResponseEntity.ok(factureService.rejeter(id, request != null ? request.getCommentaire() : null));
    }

    @PostMapping("/{id}/valider-poste")
    public ResponseEntity<FactureStructure> validerPoste(@PathVariable String id, @RequestBody(required = false) ValidationRequest request) {
        return ResponseEntity.ok(factureService.validerPoste(id, request != null ? request.getCommentaire() : null));
    }

    @PostMapping("/{id}/rejeter-poste")
    public ResponseEntity<FactureStructure> rejeterPoste(@PathVariable String id, @RequestBody ValidationRequest request) {
        return ResponseEntity.ok(factureService.rejeterPoste(id, request != null ? request.getCommentaire() : null));
    }

    /**
     * Purge les factures de structure restées en brouillon (jamais envoyées).
     * Déclaré AVANT /{id} : sans cela « brouillons » serait capté comme un identifiant.
     */
    @DeleteMapping("/brouillons")
    public ResponseEntity<java.util.Map<String, Integer>> purgerBrouillons(
            @RequestParam(required = false) String structureId) {
        int supprimees = factureService.purgerBrouillons(structureId);
        return ResponseEntity.ok(java.util.Map.of("supprimees", supprimees));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        factureService.delete(id);
        return ResponseEntity.ok().build();
    }
}
