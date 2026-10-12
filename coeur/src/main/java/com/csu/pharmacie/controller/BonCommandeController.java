package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.AnnulationRequest;
import com.csu.pharmacie.entity.BonCommande;
import com.csu.pharmacie.service.BonCommandeService;
import com.csu.pharmacie.service.ExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bons-commande")
@RequiredArgsConstructor
public class BonCommandeController {

    private final BonCommandeService bonCommandeService;
    private final ExportService exportService;

    @GetMapping
    public ResponseEntity<List<BonCommande>> getAll(
            @RequestParam(required = false) Integer mois,
            @RequestParam(required = false) Integer annee,
            @RequestParam(required = false) String regime) {
        return ResponseEntity.ok(bonCommandeService.getAllForCurrentUser(mois, annee, regime));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BonCommande> getById(@PathVariable String id) {
        return ResponseEntity.ok(bonCommandeService.getById(id));
    }

    @GetMapping("/search")
    public ResponseEntity<BonCommande> search(@RequestParam String numero) {
        return ResponseEntity.ok(bonCommandeService.findByNumero(numero));
    }

    /** Récupère le bon associé à une lettre de garantie (204 si aucun). */
    @GetMapping("/by-lettre/{lettreGarantieId}")
    public ResponseEntity<BonCommande> getByLettre(@PathVariable String lettreGarantieId) {
        BonCommande bon = bonCommandeService.findByLettre(lettreGarantieId);
        return bon != null ? ResponseEntity.ok(bon) : ResponseEntity.noContent().build();
    }

    @PostMapping
    public ResponseEntity<BonCommande> create(@RequestBody Map<String, Object> body) {
        String lettreGarantieId = body.get("lettreGarantieId") != null ? body.get("lettreGarantieId").toString() : null;
        int nombreLignes = body.get("nombreLignes") != null ? Integer.parseInt(body.get("nombreLignes").toString()) : 0;
        return ResponseEntity.ok(bonCommandeService.creerBonCommande(lettreGarantieId, nombreLignes));
    }

    @PostMapping("/{id}/annuler")
    public ResponseEntity<BonCommande> annuler(@PathVariable String id, @Valid @RequestBody AnnulationRequest request) {
        return ResponseEntity.ok(bonCommandeService.annuler(id, request));
    }

    @GetMapping("/{id}/export/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable String id) {
        BonCommande bon = bonCommandeService.getById(id);
        byte[] pdfBytes = exportService.exportBonCommandePdf(bon);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "Bon_Commande_" + bon.getNumero() + ".pdf");

        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }
}
