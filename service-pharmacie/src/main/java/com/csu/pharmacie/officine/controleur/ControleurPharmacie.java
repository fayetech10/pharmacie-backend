package com.csu.pharmacie.officine.controleur;

import com.csu.pharmacie.entity.Pharmacie;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.officine.depot.DepotPharmacie;

import java.util.Comparator;
import java.util.List;

/** Officines et dépôts pharmaceutiques. */
@RestController
@RequestMapping("/api/pharmacie/pharmacies")
@RequiredArgsConstructor
public class ControleurPharmacie {

    private final DepotPharmacie depot;

    @GetMapping
    public List<Pharmacie> lister(@RequestParam(required = false) String districtId,
                                  @RequestParam(required = false) String structureId) {
        List<Pharmacie> pharmacies;
        if (structureId != null && !structureId.isBlank()) {
            pharmacies = depot.findByStructureId(structureId);
        } else if (districtId != null && !districtId.isBlank()) {
            pharmacies = depot.findByDistrictId(districtId);
        } else {
            pharmacies = depot.findAll();
        }
        return pharmacies.stream()
                .sorted(Comparator.comparing(p -> p.getNom() == null ? "" : p.getNom()))
                .toList();
    }

    @GetMapping("/{id}")
    public Pharmacie detail(@PathVariable String id) {
        return depot.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Pharmacie introuvable : " + id));
    }

    @PostMapping
    public Pharmacie creer(@RequestBody Pharmacie pharmacie) {
        if (pharmacie.getCode() == null || pharmacie.getCode().isBlank()) {
            throw ExceptionMetier.invalide("Le code de la pharmacie est obligatoire");
        }
        if (depot.existsByCodeIgnoreCase(pharmacie.getCode())) {
            throw ExceptionMetier.conflit("Une pharmacie porte déjà le code « " + pharmacie.getCode() + " »");
        }
        pharmacie.setId(null);
        return depot.save(pharmacie);
    }

    @PutMapping("/{id}")
    public Pharmacie modifier(@PathVariable String id, @RequestBody Pharmacie modifications) {
        Pharmacie pharmacie = detail(id);
        if (modifications.getNom() != null) {
            pharmacie.setNom(modifications.getNom());
        }
        pharmacie.setStructureId(modifications.getStructureId());
        pharmacie.setDistrictId(modifications.getDistrictId());
        pharmacie.setRegionId(modifications.getRegionId());
        pharmacie.setAdresse(modifications.getAdresse());
        pharmacie.setTelephone(modifications.getTelephone());
        pharmacie.setEmail(modifications.getEmail());
        return depot.save(pharmacie);
    }

    @PutMapping("/{id}/activation")
    public Pharmacie activation(@PathVariable String id, @RequestParam boolean actif) {
        Pharmacie pharmacie = detail(id);
        pharmacie.setActif(actif);
        return depot.save(pharmacie);
    }
}
