package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.StructureSanitaireRequest;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.service.StructureSanitaireService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
