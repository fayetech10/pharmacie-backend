package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.PharmacieImportResult;
import com.csu.pharmacie.dto.PharmacieRequest;
import com.csu.pharmacie.entity.Pharmacie;
import com.csu.pharmacie.service.PharmacieService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pharmacies")
@RequiredArgsConstructor
public class PharmacieController {

    private final PharmacieService pharmacieService;

    @GetMapping
    public ResponseEntity<List<Pharmacie>> getAllPharmacies() {
        return ResponseEntity.ok(pharmacieService.getAllPharmacies());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Pharmacie> getPharmacieById(@PathVariable String id) {
        return ResponseEntity.ok(pharmacieService.getPharmacieById(id));
    }

    @PostMapping
    public ResponseEntity<Pharmacie> createPharmacie(@RequestBody PharmacieRequest request) {
        return ResponseEntity.ok(pharmacieService.createPharmacie(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Pharmacie> updatePharmacie(@PathVariable String id, @RequestBody PharmacieRequest request) {
        return ResponseEntity.ok(pharmacieService.updatePharmacie(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePharmacie(@PathVariable String id) {
        pharmacieService.deletePharmacie(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Importe des pharmacies (et leurs comptes Pharmacien) depuis un fichier Excel.
     * Renvoie un récapitulatif : nombre d'importées, d'échecs et le détail des erreurs.
     */
    @PostMapping("/import")
    public ResponseEntity<?> importPharmacies(@RequestParam("file") MultipartFile file) {
        try {
            PharmacieImportResult result = pharmacieService.importerPharmacies(file.getInputStream());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, String> error = new java.util.HashMap<>();
            error.put("message", e.getMessage() != null ? e.getMessage() : "Erreur lors de l'importation");
            if (e.getCause() != null) {
                error.put("cause", e.getCause().getMessage());
            }
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * PDF des identifiants de connexion des pharmacies dont les ids sont envoyés (tableau
     * JSON, ordre de l'écran) ; corps vide = toutes les pharmacies visibles.
     */
    @PostMapping("/export-pdf")
    public ResponseEntity<byte[]> exporterIdentifiantsPdf(@RequestBody(required = false) List<String> ids) {
        byte[] contenu = pharmacieService.exporterIdentifiantsPdf(ids);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"identifiants_pharmacies.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(contenu);
    }

    /** Export Excel des pharmacies avec leurs identifiants de connexion (après import). */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exporterPharmacies() {
        byte[] contenu = pharmacieService.exporterPharmaciesExcel();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pharmacies_identifiants.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(contenu);
    }

    /** Télécharge un fichier Excel modèle à remplir pour l'import. */
    @GetMapping("/import-template")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        byte[] contenu = pharmacieService.genererModeleImport();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"modele_import_pharmacies.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(contenu);
    }
}
