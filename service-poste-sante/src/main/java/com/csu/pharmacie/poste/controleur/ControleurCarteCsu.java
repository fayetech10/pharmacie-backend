package com.csu.pharmacie.poste.controleur;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.csu.pharmacie.commun.carte.ServiceCarteCsu;

/**
 * Vérification d'une carte CSU d'après le site officiel, pour préremplir un patient.
 *
 * <p>Sert deux écrans : la facturation du poste de santé et la lettre de garantie de
 * l'agent BCSU. Le chemin est donc neutre ({@code /api/carte-csu}) et routé vers ce
 * service sans prédicat de rôle par la passerelle ; c'est {@code SecuritePoste} qui
 * restreint les rôles autorisés.</p>
 */
@RestController
@RequestMapping("/api/carte-csu")
@RequiredArgsConstructor
public class ControleurCarteCsu {

    private final ServiceCarteCsu service;

    @GetMapping
    public ServiceCarteCsu.CarteCsu verifier(@RequestParam String code) {
        return service.verifier(code);
    }
}
