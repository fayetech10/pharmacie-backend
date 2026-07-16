package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.AnnulationRequest;
import com.csu.pharmacie.dto.FeuilleSoinsRequest;
import com.csu.pharmacie.dto.PriseEnChargeFeuilleRequest;
import com.csu.pharmacie.entity.FeuilleSoins;
import com.csu.pharmacie.service.ExportService;
import com.csu.pharmacie.service.FeuilleSoinsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/feuilles-soins")
@RequiredArgsConstructor
public class FeuilleSoinsController {

    private final FeuilleSoinsService feuilleSoinsService;
    private final ExportService exportService;

    @GetMapping
    public ResponseEntity<List<FeuilleSoins>> getAll(
            @RequestParam(required = false) Integer mois,
            @RequestParam(required = false) Integer annee,
            @RequestParam(required = false) String regime) {
        return ResponseEntity.ok(feuilleSoinsService.getAllForCurrentUser(mois, annee, regime));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FeuilleSoins> getById(@PathVariable String id) {
        return ResponseEntity.ok(feuilleSoinsService.getById(id));
    }

    /** La structure retrouve la feuille présentée par le patient. */
    @GetMapping("/search")
    public ResponseEntity<FeuilleSoins> search(@RequestParam String numero) {
        return ResponseEntity.ok(feuilleSoinsService.findByNumero(numero));
    }

    /** Étape 1 — délivrance par l'agent bureau (BCSU). */
    @PostMapping
    public ResponseEntity<FeuilleSoins> delivrer(@Valid @RequestBody FeuilleSoinsRequest request) {
        return ResponseEntity.ok(feuilleSoinsService.delivrer(request));
    }

    /** Étape 2 — la structure sanitaire remplit la prise en charge. */
    @PostMapping("/{id}/prise-en-charge")
    public ResponseEntity<FeuilleSoins> remplirPriseEnCharge(@PathVariable String id,
                                                             @Valid @RequestBody PriseEnChargeFeuilleRequest request) {
        return ResponseEntity.ok(feuilleSoinsService.remplirPriseEnCharge(id, request));
    }

    @PostMapping("/{id}/annuler")
    public ResponseEntity<FeuilleSoins> annuler(@PathVariable String id, @Valid @RequestBody AnnulationRequest request) {
        return ResponseEntity.ok(feuilleSoinsService.annuler(id, request));
    }

    /** PDF au format du formulaire papier ASCSU (à remettre au patient). */
    @GetMapping("/{id}/export/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable String id) {
        FeuilleSoins feuille = feuilleSoinsService.getById(id);
        byte[] pdfBytes = exportService.exportFeuilleSoinsPdf(feuille);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "Feuille_Soins_" + feuille.getNumero() + ".pdf");

        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }
}
