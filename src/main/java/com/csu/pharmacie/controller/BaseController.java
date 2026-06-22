package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.BaseImportResult;
import com.csu.pharmacie.entity.BaseImmatricule;
import com.csu.pharmacie.entity.BaseTraite;
import com.csu.pharmacie.service.BaseImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Bases « traité » et « immatriculé & imprimé » — réservées au service régional de
 * Thiès (vérifié dans {@link BaseImportService}). Permet l'import Excel, la
 * consultation et le téléchargement des modèles.
 */
@RestController
@RequestMapping("/api/bases")
@RequiredArgsConstructor
public class BaseController {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final BaseImportService baseImportService;

    // ----- Consultation -----

    @GetMapping("/traite")
    public ResponseEntity<List<BaseTraite>> getBaseTraite() {
        return ResponseEntity.ok(baseImportService.getBaseTraite());
    }

    @GetMapping("/immatricule")
    public ResponseEntity<List<BaseImmatricule>> getBaseImmatricule() {
        return ResponseEntity.ok(baseImportService.getBaseImmatricule());
    }

    // ----- Import -----

    @PostMapping("/traite/import")
    public ResponseEntity<?> importBaseTraite(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(baseImportService.importerBaseTraite(file.getInputStream()));
        } catch (Exception e) {
            return erreur(e);
        }
    }

    @PostMapping("/immatricule/import")
    public ResponseEntity<?> importBaseImmatricule(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(baseImportService.importerBaseImmatricule(file.getInputStream()));
        } catch (Exception e) {
            return erreur(e);
        }
    }

    // ----- Modèles Excel -----

    @GetMapping("/traite/template")
    public ResponseEntity<byte[]> templateTraite() {
        return fichierExcel(baseImportService.genererModeleTraite(), "modele_base_traite.xlsx");
    }

    @GetMapping("/immatricule/template")
    public ResponseEntity<byte[]> templateImmatricule() {
        return fichierExcel(baseImportService.genererModeleImmatricule(), "modele_base_immatricule.xlsx");
    }

    // ----- Helpers -----

    private ResponseEntity<byte[]> fichierExcel(byte[] contenu, String nomFichier) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nomFichier + "\"")
                .contentType(MediaType.parseMediaType(XLSX_MIME))
                .body(contenu);
    }

    private ResponseEntity<?> erreur(Exception e) {
        Map<String, String> error = new java.util.HashMap<>();
        error.put("message", e.getMessage() != null ? e.getMessage() : "Erreur lors de l'importation");
        if (e.getCause() != null) {
            error.put("cause", e.getCause().getMessage());
        }
        return ResponseEntity.badRequest().body(error);
    }
}
