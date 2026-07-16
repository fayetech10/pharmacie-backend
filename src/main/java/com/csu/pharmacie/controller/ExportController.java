package com.csu.pharmacie.controller;

import com.csu.pharmacie.entity.Facture;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.service.ExportService;
import com.csu.pharmacie.service.FactureService;
import com.csu.pharmacie.service.FactureStructureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final FactureService factureService;
    private final FactureStructureService factureStructureService;
    private final ExportService exportService;

    @GetMapping("/region-global/excel")
    public ResponseEntity<byte[]> exportGlobalRegionExcel() {
        // Retrieve factures scoped for the current regional user
        List<Facture> facturesPharma = factureService.getAllFactures();
        List<FactureStructure> facturesStruct = factureStructureService.getAllForCurrentUser();

        byte[] excelBytes = exportService.exportGlobalRegionExcel(facturesPharma, facturesStruct);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", "Export_Global_Region.xlsx");
        return ResponseEntity.ok().headers(headers).body(excelBytes);
    }
}
