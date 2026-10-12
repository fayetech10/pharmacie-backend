package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.PatientRequest;
import com.csu.pharmacie.entity.Patient;
import com.csu.pharmacie.service.PatientService;
import com.csu.pharmacie.service.PdfExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;
    private final PdfExportService pdfExportService;

    @GetMapping("/search")
    public ResponseEntity<List<Patient>> search(@RequestParam String query) {
        return ResponseEntity.ok(patientService.search(query));
    }

    @GetMapping
    public ResponseEntity<List<Patient>> getAll() {
        return ResponseEntity.ok(patientService.getAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Patient> getById(@PathVariable String id) {
        return ResponseEntity.ok(patientService.getById(id));
    }

    @GetMapping("/{id}/dossier-csu")
    public ResponseEntity<com.csu.pharmacie.dto.DossierPatientDto> getDossierConsolide(@PathVariable String id) {
        return ResponseEntity.ok(patientService.getDossierConsolide(id));
    }

    @GetMapping("/{id}/dossier-csu/pdf")
    public ResponseEntity<byte[]> getDossierConsolidePdf(@PathVariable String id) {
        try {
            com.csu.pharmacie.dto.DossierPatientDto dossier = patientService.getDossierConsolide(id);
            byte[] pdfBytes = pdfExportService.generatePatientDossierPdf(dossier);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "Dossier_Consolide_" + dossier.getPatient().getNom() + ".pdf");
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(pdfBytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping
    public ResponseEntity<Patient> create(@Valid @RequestBody PatientRequest request) {
        return ResponseEntity.ok(patientService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Patient> update(@PathVariable String id, @Valid @RequestBody PatientRequest request) {
        return ResponseEntity.ok(patientService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        patientService.delete(id);
        return ResponseEntity.ok().build();
    }
}
