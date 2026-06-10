package com.csu.pharmacie.controller;

import com.csu.pharmacie.entity.Medicament;
import com.csu.pharmacie.service.MedicamentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/medicaments")
@RequiredArgsConstructor
public class MedicamentController {

    private final MedicamentService medicamentService;

    @GetMapping
    public ResponseEntity<List<Medicament>> getAll() {
        return ResponseEntity.ok(medicamentService.getAll());
    }

    @GetMapping("/search")
    public ResponseEntity<List<Medicament>> search(@RequestParam String query) {
        return ResponseEntity.ok(medicamentService.search(query));
    }

    @PostMapping
    public ResponseEntity<Medicament> create(@RequestBody Medicament medicament) {
        return ResponseEntity.ok(medicamentService.create(medicament));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Medicament> update(@PathVariable String id, @RequestBody Medicament medicament) {
        return ResponseEntity.ok(medicamentService.update(id, medicament));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        medicamentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/import-eligibles")
    public ResponseEntity<?> importEligibles(@RequestParam("file") MultipartFile file) {
        try {
            medicamentService.importerMedicamentsEligibles(file.getInputStream());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            Map<String, String> error = new java.util.HashMap<>();
            error.put("message", e.getMessage() != null ? e.getMessage() : "Erreur lors de l'importation");
            if (e.getCause() != null) {
                error.put("cause", e.getCause().getMessage());
            }
            return ResponseEntity.badRequest().body(error);
        }
    }
}
