package com.csu.pharmacie.identite.controleur;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.identite.depot.DepotRegion;
import com.csu.pharmacie.entity.Region;

import java.util.Comparator;
import java.util.List;

/** Régions médicales — référentiel simple, administré par le niveau central. */
@RestController
@RequestMapping("/api/regions")
@RequiredArgsConstructor
public class ControleurRegion {

    private final DepotRegion depotRegion;

    @GetMapping
    public List<Region> lister() {
        return depotRegion.findAll().stream()
                .sorted(Comparator.comparing(r -> r.getNom() == null ? "" : r.getNom()))
                .toList();
    }

    @PostMapping
    public Region creer(@RequestBody Region region) {
        if (region.getCode() != null && depotRegion.findByCodeIgnoreCase(region.getCode()).isPresent()) {
            throw ExceptionMetier.conflit("Une région porte déjà le code « " + region.getCode() + " »");
        }
        region.setId(null);
        return depotRegion.save(region);
    }

    @PutMapping("/{id}")
    public Region modifier(@PathVariable String id, @RequestBody Region modifications) {
        Region region = depotRegion.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Région introuvable : " + id));
        if (modifications.getNom() != null) {
            region.setNom(modifications.getNom());
        }
        if (modifications.getCode() != null) {
            region.setCode(modifications.getCode());
        }
        return depotRegion.save(region);
    }
}
