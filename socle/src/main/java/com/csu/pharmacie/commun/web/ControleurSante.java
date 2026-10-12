package com.csu.pharmacie.commun.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sonde de disponibilité commune à tous les services (route publique).
 *
 * <p>Sert au démarrage de la stack — savoir <em>quel</em> service répond est la première
 * question qu'on se pose en architecture distribuée — et au maintien en éveil des
 * hébergements qui suspendent les conteneurs inactifs.</p>
 */
@RestController
@RequestMapping("/api/health")
public class ControleurSante {

    @Value("${spring.application.name:service}")
    private String nom;

    @GetMapping
    public Map<String, Object> sante() {
        Map<String, Object> reponse = new LinkedHashMap<>();
        reponse.put("service", nom);
        reponse.put("statut", "UP");
        reponse.put("horodatage", LocalDateTime.now().toString());
        return reponse;
    }
}
