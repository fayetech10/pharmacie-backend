package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.PharmacieImportResult;
import com.csu.pharmacie.dto.StructureSanitaireRequest;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.service.StructureSanitaireService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/structures-sanitaires")
@RequiredArgsConstructor
public class StructureSanitaireController {

    private final StructureSanitaireService structureService;

    @GetMapping
    public ResponseEntity<List<StructureSanitaire>> getAll() {
        return ResponseEntity.ok(structureService.getAll());
    }

    /** Accessible à tout utilisateur authentifié — permet au frontend de résoudre
     *  le nom de la structure d'un agent BCSU à partir de son userId. */
    @GetMapping("/by-bcsu/{bcsuId}")
    public ResponseEntity<List<StructureSanitaire>> getByBcsu(@PathVariable String bcsuId) {
        return ResponseEntity.ok(structureService.getByBcsuId(bcsuId));
    }

    /** Postes de santé polarisés (rattachés) par une structure sanitaire. */
    @GetMapping("/{id}/postes")
    public ResponseEntity<List<StructureSanitaire>> getPostes(@PathVariable String id) {
        return ResponseEntity.ok(structureService.getPostesRattaches(id));
    }

    /**
     * Import d'une liste de postes de santé depuis un fichier Excel (Service Régional).
     * Chaque ligne crée un poste (PS) rattaché à une structure + son compte de connexion.
     */
    @PostMapping("/import-postes")
    public ResponseEntity<?> importPostes(@RequestParam("file") MultipartFile file) {
        try {
            PharmacieImportResult result = structureService.importerPostes(file.getInputStream());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage() != null ? e.getMessage() : "Erreur lors de l'importation");
            return ResponseEntity.badRequest().body(error);
        }
    }

    /** Télécharge le fichier Excel modèle pour l'import des postes de santé. */
    @GetMapping("/import-postes-template")
    public ResponseEntity<byte[]> importPostesTemplate() {
        byte[] bytes = structureService.genererModeleImportPostes();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"modele_import_postes_sante.xlsx\"")
                .contentType(MediaType.valueOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    /** Export Excel des structures sanitaires avec leurs identifiants de connexion. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exporterStructures() {
        byte[] bytes = structureService.exporterStructuresExcel();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"structures_identifiants.xlsx\"")
                .contentType(MediaType.valueOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StructureSanitaire> getById(@PathVariable String id) {
        return ResponseEntity.ok(structureService.getById(id));
    }

    @PostMapping
    public ResponseEntity<StructureSanitaire> create(@Valid @RequestBody StructureSanitaireRequest request) {
        return ResponseEntity.ok(structureService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<StructureSanitaire> update(@PathVariable String id, @Valid @RequestBody StructureSanitaireRequest request) {
        return ResponseEntity.ok(structureService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        structureService.delete(id);
        return ResponseEntity.ok().build();
    }
}
