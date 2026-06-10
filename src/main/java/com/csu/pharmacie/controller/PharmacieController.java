package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.PharmacieRequest;
import com.csu.pharmacie.entity.Pharmacie;
import com.csu.pharmacie.service.PharmacieService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
}
