package com.csu.pharmacie.officine.controleur;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.csu.pharmacie.commun.client.dto.SortieStockDto;
import com.csu.pharmacie.officine.service.ServiceStock;

/**
 * Point d'entrée des sorties de stock automatiques (§14), appelé par les services
 * poste et centre au moment où ils enregistrent une facture contenant une prestation
 * dépositaire.
 */
@RestController
@RequestMapping("/interne/stock")
@RequiredArgsConstructor
public class ControleurInterneStock {

    private final ServiceStock service;

    @PostMapping("/sorties")
    public ServiceStock.Reponse sortie(@RequestBody SortieStockDto demande) {
        return service.sortieAutomatique(demande);
    }
}
