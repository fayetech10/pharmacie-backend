package com.csu.pharmacie.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoint de santé public et léger (ne touche pas la base de données).
 *
 * Sert de cible à un service de keep-alive externe (UptimeRobot, cron-job.org, Render Cron…)
 * qui le ping toutes les ~10 min : sur l'offre gratuite Render, l'instance s'endort après
 * 15 min d'inactivité et le premier accès suivant subit un long « cold start » (~30-60 s).
 * Maintenir l'instance éveillée supprime cette lenteur au premier chargement.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
