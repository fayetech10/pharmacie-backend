package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.ConstatRequest;
import com.csu.pharmacie.entity.Constat;
import com.csu.pharmacie.service.ConstatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/constats")
@RequiredArgsConstructor
public class ConstatController {

    private final ConstatService constatService;

    @GetMapping
    public ResponseEntity<List<Constat>> getAll() {
        return ResponseEntity.ok(constatService.getAllForCurrentUser());
    }

    @PostMapping
    public ResponseEntity<Constat> create(@Valid @RequestBody ConstatRequest request) {
        return ResponseEntity.ok(constatService.creer(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        constatService.supprimer(id);
        return ResponseEntity.ok().build();
    }
}
