package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.ControleDelivranceDto;
import com.csu.pharmacie.dto.FactureRequest;
import com.csu.pharmacie.dto.LigneDecisionRequest;
import com.csu.pharmacie.dto.LigneFactureDto;
import com.csu.pharmacie.dto.ValidationRequest;
import com.csu.pharmacie.entity.Facture;
import com.csu.pharmacie.service.ExportService;
import com.csu.pharmacie.service.FactureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/factures")
@RequiredArgsConstructor
public class FactureController {

    private final FactureService factureService;
    private final ExportService exportService;

    @GetMapping
    public ResponseEntity<List<Facture>> getAllFactures(
            @RequestParam(required = false) Integer mois,
            @RequestParam(required = false) Integer annee) {
        // Liste allégée (sans les images base64 des lignes) : réponse beaucoup plus légère.
        return ResponseEntity.ok(factureService.getAllFacturesLight(mois, annee));
    }

    /**
     * Identifiants + statuts des factures visibles : alimente les badges de notification
     * sans transférer (ni désérialiser) les lignes de facture.
     */
    @GetMapping("/statuts")
    public ResponseEntity<List<com.csu.pharmacie.dto.FactureStatutDto>> getStatuts() {
        return ResponseEntity.ok(factureService.getStatutsPourBadges());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Facture> getFactureById(@PathVariable String id) {
        return ResponseEntity.ok(factureService.getFactureById(id));
    }

    @GetMapping("/current")
    public ResponseEntity<Facture> getCurrent() {
        Facture f = factureService.getCurrentFacture();
        if (f == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(f);
    }

    @GetMapping("/retards")
    public ResponseEntity<List<Facture>> getRetards() {
        return ResponseEntity.ok(factureService.getRetards());
    }

    /**
     * Contrôle « médicament déjà délivré durant le mois » sur toute la base nationale.
     * Appelé avant l'ajout d'une ligne, que le bon soit numérique ou physique.
     */
    @GetMapping("/controle-delivrance")
    public ResponseEntity<ControleDelivranceDto> controlerDelivrance(
            @RequestParam(required = false) String matricule,
            @RequestParam(required = false) String nom,
            @RequestParam String medicament,
            @RequestParam(required = false) Integer mois,
            @RequestParam(required = false) Integer annee) {
        return ResponseEntity.ok(factureService.controlerDelivrance(matricule, nom, medicament, mois, annee));
    }

    @PostMapping
    public ResponseEntity<Facture> createFacture(@Valid @RequestBody FactureRequest request) {
        return ResponseEntity.ok(factureService.createFacture(request));
    }

    @PostMapping("/current/lignes")
    public ResponseEntity<Facture> addLignesToCurrent(@Valid @RequestBody List<LigneFactureDto> lignes) {
        return ResponseEntity.ok(factureService.addLignesToCurrent(lignes));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Facture> updateFacture(@PathVariable String id, @Valid @RequestBody FactureRequest request) {
        return ResponseEntity.ok(factureService.updateFacture(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFacture(@PathVariable String id) {
        factureService.deleteFacture(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/envoyer")
    public ResponseEntity<Facture> envoyerFacture(@PathVariable String id) {
        return ResponseEntity.ok(factureService.envoyerFacture(id));
    }

    @PostMapping("/{id}/payer")
    public ResponseEntity<Facture> payerFacture(@PathVariable String id) {
        return ResponseEntity.ok(factureService.payerFacture(id));
    }

    @PostMapping("/{id}/renvoyer-pharmacie")
    public ResponseEntity<Facture> renvoyerAPharmacie(@PathVariable String id) {
        return ResponseEntity.ok(factureService.renvoyerAPharmacie(id));
    }

    @PostMapping("/{id}/renvoyer-central")
    public ResponseEntity<Facture> renvoyerAuCentral(@PathVariable String id) {
        return ResponseEntity.ok(factureService.renvoyerAuCentral(id));
    }

    @PostMapping("/{id}/valider")
    public ResponseEntity<Facture> validerFacture(@PathVariable String id, @RequestBody(required = false) ValidationRequest request) {
        return ResponseEntity.ok(factureService.validerFacture(id, request));
    }

    @PostMapping("/{id}/rejeter")
    public ResponseEntity<Facture> rejeterFacture(@PathVariable String id, @Valid @RequestBody ValidationRequest request) {
        return ResponseEntity.ok(factureService.rejeterFacture(id, request));
    }

    @PostMapping("/{id}/lignes/{ligneIndex}/decider")
    public ResponseEntity<Facture> deciderLigne(@PathVariable String id,
                                                @PathVariable int ligneIndex,
                                                @RequestBody LigneDecisionRequest request) {
        return ResponseEntity.ok(factureService.deciderLigne(id, ligneIndex, request));
    }

    @GetMapping("/export/excel")
    public ResponseEntity<byte[]> exportExcel() {
        List<Facture> factures = factureService.getAllFactures();
        byte[] excelBytes = exportService.exportExcel(factures);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", "factures.xlsx");
        
        return ResponseEntity.ok()
                .headers(headers)
                .body(excelBytes);
    }

    @GetMapping("/{id}/export/excel")
    public ResponseEntity<byte[]> exportFactureExcel(@PathVariable String id) {
        Facture facture = factureService.getFactureById(id);
        byte[] excelBytes = exportService.exportFactureExcel(facture);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", "Facture_" + facture.getMois() + "_" + facture.getAnnee() + ".xlsx");

        return ResponseEntity.ok()
                .headers(headers)
                .body(excelBytes);
    }

    @GetMapping("/{id}/export/pdf")
    public ResponseEntity<byte[]> exportFacturePdf(@PathVariable String id) {
        Facture facture = factureService.getFactureById(id);
        byte[] pdfBytes = exportService.exportFacturePdf(facture);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "Facture_" + facture.getMois() + "_" + facture.getAnnee() + ".pdf");

        return ResponseEntity.ok()
                .headers(headers)
                .body(pdfBytes);
    }

    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportPdf() {
        List<Facture> factures = factureService.getAllFactures();
        byte[] pdfBytes = exportService.exportPdf(factures);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "factures.pdf");
        
        return ResponseEntity.ok()
                .headers(headers)
                .body(pdfBytes);
    }

    @PostMapping("/import")
    public ResponseEntity<?> importExcel(@RequestParam("file") MultipartFile file) {
        try {
            factureService.importerExcel(file.getInputStream());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            java.util.Map<String, String> error = new java.util.HashMap<>();
            error.put("message", e.getMessage() != null ? e.getMessage() : "Erreur lors de l'importation");
            if (e.getCause() != null) {
                error.put("cause", e.getCause().getMessage());
            }
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Réimport d'une facture corrigée (format Excel détaillé) : remplace les lignes
     * de la facture ciblée. Réservé au Service Régional / Central (et Admin).
     */
    @PostMapping("/{id}/import")
    public ResponseEntity<?> importFactureExcel(@PathVariable String id, @RequestParam("file") MultipartFile file) {
        try {
            Facture facture = factureService.importerFactureExcel(id, file.getInputStream());
            return ResponseEntity.ok(facture);
        } catch (Exception e) {
            java.util.Map<String, String> error = new java.util.HashMap<>();
            error.put("message", e.getMessage() != null ? e.getMessage() : "Erreur lors de l'importation");
            if (e.getCause() != null) {
                error.put("cause", e.getCause().getMessage());
            }
            return ResponseEntity.badRequest().body(error);
        }
    }
}
