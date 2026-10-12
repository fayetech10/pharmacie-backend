package com.csu.pharmacie.officine.controleur;

import com.csu.pharmacie.entity.ArticleStock;
import com.csu.pharmacie.entity.MouvementStock;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.csu.pharmacie.officine.service.ServiceStock;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Stock de la pharmacie : articles, mouvements, alertes de réapprovisionnement. */
@RestController
@RequestMapping("/api/pharmacie/stock")
@RequiredArgsConstructor
public class ControleurStock {

    private final ServiceStock service;

    @GetMapping("/articles")
    public List<ArticleStock> articles(@RequestParam(required = false) String structureId) {
        return service.articles(structureId);
    }

    @GetMapping("/articles/{id}")
    public ArticleStock detail(@PathVariable String id) {
        return service.parId(id);
    }

    @GetMapping("/alertes")
    public List<ArticleStock> alertes(@RequestParam(required = false) String structureId) {
        return service.alertes(structureId);
    }

    @PostMapping("/articles")
    public ArticleStock creer(@RequestBody ArticleStock saisie) {
        return service.enregistrer(null, saisie);
    }

    @PutMapping("/articles/{id}")
    public ArticleStock modifier(@PathVariable String id, @RequestBody ArticleStock saisie) {
        return service.enregistrer(id, saisie);
    }

    @PostMapping("/articles/{id}/entree")
    public MouvementStock entree(@PathVariable String id, @RequestBody Map<String, Object> corps) {
        return service.entree(id, entier(corps.get("quantite")), (String) corps.get("motif"));
    }

    @PostMapping("/articles/{id}/sortie")
    public MouvementStock sortie(@PathVariable String id, @RequestBody Map<String, Object> corps) {
        return service.sortieManuelle(id, entier(corps.get("quantite")), (String) corps.get("motif"));
    }

    @PostMapping("/articles/{id}/ajustement")
    public MouvementStock ajustement(@PathVariable String id, @RequestBody Map<String, Object> corps) {
        return service.ajuster(id, entier(corps.get("quantiteReelle")), (String) corps.get("motif"));
    }

    @GetMapping("/articles/{id}/mouvements")
    public List<MouvementStock> historique(@PathVariable String id) {
        return service.historique(id);
    }

    /** Journal des mouvements sur une période — sert au contrôle et à l'inventaire. */
    @GetMapping("/mouvements")
    public List<MouvementStock> journal(
            @RequestParam(required = false) String structureId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin) {
        return service.journal(structureId, debut, fin);
    }

    private static int entier(Object valeur) {
        if (valeur instanceof Number nombre) {
            return nombre.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(valeur));
        } catch (NumberFormatException e) {
            throw com.csu.pharmacie.commun.web.ExceptionMetier.invalide("Quantité invalide : " + valeur);
        }
    }
}
