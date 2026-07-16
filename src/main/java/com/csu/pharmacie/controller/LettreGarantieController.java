package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.AnnulationRequest;
import com.csu.pharmacie.dto.LettreGarantieRequest;
import com.csu.pharmacie.entity.LettreGarantie;
import com.csu.pharmacie.service.ExportService;
import com.csu.pharmacie.service.LettreGarantieService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/lettres-garantie")
@RequiredArgsConstructor
public class LettreGarantieController {

    private final LettreGarantieService lettreGarantieService;
    private final ExportService exportService;

    @GetMapping
    public ResponseEntity<List<LettreGarantie>> getAll(
            @RequestParam(required = false) Integer mois,
            @RequestParam(required = false) Integer annee,
            @RequestParam(required = false) String regime) {
        return ResponseEntity.ok(lettreGarantieService.getAllForCurrentUser(mois, annee, regime));
    }

    @GetMapping("/{id}")
    public ResponseEntity<LettreGarantie> getById(@PathVariable String id) {
        return ResponseEntity.ok(lettreGarantieService.getById(id));
    }

    @GetMapping("/search")
    public ResponseEntity<LettreGarantie> search(@RequestParam String numero) {
        return ResponseEntity.ok(lettreGarantieService.findByNumero(numero));
    }

    @PostMapping
    public ResponseEntity<LettreGarantie> create(@Valid @RequestBody LettreGarantieRequest request) {
        return ResponseEntity.ok(lettreGarantieService.creerLettreGarantie(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<LettreGarantie> update(@PathVariable String id, @Valid @RequestBody LettreGarantieRequest request) {
        return ResponseEntity.ok(lettreGarantieService.updateLettreGarantie(id, request));
    }

    @PostMapping("/{id}/emettre")
    public ResponseEntity<LettreGarantie> emettre(@PathVariable String id) {
        return ResponseEntity.ok(lettreGarantieService.emettre(id));
    }

    @PostMapping("/{id}/annuler")
    public ResponseEntity<LettreGarantie> annuler(@PathVariable String id, @Valid @RequestBody AnnulationRequest request) {
        return ResponseEntity.ok(lettreGarantieService.annuler(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        lettreGarantieService.deleteLettreGarantie(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/export/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable String id) {
        LettreGarantie lettre = lettreGarantieService.getById(id);
        byte[] pdfBytes = exportService.exportLettreGarantiePdf(lettre);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "Lettre_Garantie_" + lettre.getNumero() + ".pdf");

        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }
}
