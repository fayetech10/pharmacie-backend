package com.csu.pharmacie.controller;

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
    public ResponseEntity<List<Facture>> getAllFactures() {
        return ResponseEntity.ok(factureService.getAllFactures());
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

    @PostMapping("/{id}/verifier")
    public ResponseEntity<Facture> verifierFacture(@PathVariable String id) {
        return ResponseEntity.ok(factureService.verifierFacture(id));
    }

    @PostMapping("/{id}/conformer")
    public ResponseEntity<Facture> conformerFacture(@PathVariable String id) {
        return ResponseEntity.ok(factureService.conformerFacture(id));
    }

    @PostMapping("/{id}/lignes/{index}/decision")
    public ResponseEntity<Facture> deciderLigne(@PathVariable String id,
                                                @PathVariable int index,
                                                @RequestBody LigneDecisionRequest request) {
        return ResponseEntity.ok(factureService.deciderLigne(id, index, request));
    }

    @PostMapping("/{id}/valider")
    public ResponseEntity<Facture> validerFacture(@PathVariable String id, @Valid @RequestBody ValidationRequest request) {
        return ResponseEntity.ok(factureService.validerFacture(id, request));
    }

    @PostMapping("/{id}/rejeter")
    public ResponseEntity<Facture> rejeterFacture(@PathVariable String id, @Valid @RequestBody ValidationRequest request) {
        return ResponseEntity.ok(factureService.rejeterFacture(id, request));
    }

    @PostMapping("/{id}/renvoyer-correction")
    public ResponseEntity<Facture> renvoyerPourCorrection(@PathVariable String id) {
        return ResponseEntity.ok(factureService.renvoyerPourCorrection(id));
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
}
