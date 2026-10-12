package com.csu.pharmacie.poste.controleur;

import com.csu.pharmacie.commun.facturation.ControleurFacturation;
import com.csu.pharmacie.commun.facturation.ServiceFacturation;
import com.csu.pharmacie.poste.service.ServiceFacturePoste;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Facturation du poste de santé, sur le chemin historique {@code /api/factures-structure}.
 *
 * <p>La passerelle y aiguille les comptes de poste ; les comptes de centre atteignent le
 * contrôleur équivalent de service-centre-sante. Le dashboard appelle la même URL dans
 * les deux cas.</p>
 */
@RestController
@RequestMapping("/api/factures-structure")
@RequiredArgsConstructor
public class ControleurFacturePoste extends ControleurFacturation {

    private final ServiceFacturePoste service;

    @Override
    protected ServiceFacturation service() {
        return service;
    }
}
