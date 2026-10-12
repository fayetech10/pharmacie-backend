package com.csu.pharmacie.poste.controleur;

import com.csu.pharmacie.entity.Beneficiaire;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.poste.service.ServiceBeneficiaire;

/** Bénéficiaires du poste de santé (§3). */
@RestController
@RequestMapping("/api/beneficiaires")
@RequiredArgsConstructor
public class ControleurBeneficiaire {

    private final ServiceBeneficiaire service;

    @GetMapping
    public Page<Beneficiaire> rechercher(@RequestParam(required = false) String recherche,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int taille) {
        return service.rechercher(recherche, page, taille);
    }

    @GetMapping("/{id}")
    public Beneficiaire detail(@PathVariable String id) {
        return service.parId(id);
    }

    /** Ligne de facturation pré-remplie depuis la fiche du bénéficiaire. */
    @GetMapping("/{id}/ligne")
    public LigneFactureStructure ligne(@PathVariable String id) {
        return service.versLigne(id);
    }

    @PostMapping
    public Beneficiaire creer(@RequestBody Beneficiaire saisie) {
        return service.enregistrer(null, saisie);
    }

    @PutMapping("/{id}")
    public Beneficiaire modifier(@PathVariable String id, @RequestBody Beneficiaire saisie) {
        return service.enregistrer(id, saisie);
    }

    @PutMapping("/{id}/activation")
    public Beneficiaire activation(@PathVariable String id, @RequestParam boolean actif) {
        return service.basculerActivation(id, actif);
    }
}
