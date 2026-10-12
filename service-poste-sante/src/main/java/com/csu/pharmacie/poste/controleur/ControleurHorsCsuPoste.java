package com.csu.pharmacie.poste.controleur;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.csu.pharmacie.commun.export.Documents;
import com.csu.pharmacie.commun.horscsu.SituationCaisse;
import com.csu.pharmacie.entity.FactureHorsCsu;
import com.csu.pharmacie.poste.service.ServiceHorsCsuPoste;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Module Hors CSU du poste de santé (§8) : caisse, situations, historique, réimpression. */
@RestController
@RequestMapping("/api/hors-csu")
@RequiredArgsConstructor
public class ControleurHorsCsuPoste {

    private final ServiceHorsCsuPoste service;

    @GetMapping("/factures")
    public List<FactureHorsCsu> historique() {
        return service.historique();
    }

    @GetMapping("/factures/{id}")
    public FactureHorsCsu detail(@PathVariable String id) {
        return service.parId(id);
    }

    @PostMapping("/factures")
    public FactureHorsCsu encaisser(@RequestBody FactureHorsCsu saisie) {
        return service.encaisser(saisie);
    }

    @PostMapping("/factures/{id}/annuler")
    public FactureHorsCsu annuler(@PathVariable String id, @RequestBody Map<String, String> corps) {
        return service.annuler(id, corps.get("motif"));
    }

    /** Réimpression : le reçu est reconstruit à la demande depuis la facture stockée. */
    @GetMapping("/factures/{id}/recu.pdf")
    public ResponseEntity<byte[]> recu(@PathVariable String id) {
        FactureHorsCsu facture = service.parId(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(Documents.nomFichier("recu_" + facture.getNumero(), "pdf"),
                                StandardCharsets.UTF_8)
                        .build().toString())
                .body(service.pdf(id));
    }

    @GetMapping("/situation/journaliere")
    public SituationCaisse situationJournaliere(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate jour) {
        return service.situationJournaliere(jour);
    }

    @GetMapping("/situation/mensuelle")
    public SituationCaisse situationMensuelle(@RequestParam(required = false) Integer mois,
                                              @RequestParam(required = false) Integer annee) {
        return service.situationMensuelle(mois, annee);
    }
}
