package com.csu.pharmacie.centre.controleur;

import com.csu.pharmacie.centre.service.ServiceActivitesRegion;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Activités du réseau d'une région : districts, centres et postes de santé (14/10/2026).
 *
 * <p>Sous {@code /api/consolidation} pour être aiguillé vers ce service par la passerelle
 * sans nouvelle route ; l'accès est réglé à part dans {@code SecuriteCentre} (la consolidation
 * district, elle, reste au seul district). La région d'un service régional est celle de son
 * jeton, imposée en service.</p>
 */
@RestController
@RequestMapping("/api/consolidation/region")
@RequiredArgsConstructor
public class ControleurActivitesRegion {

    private final ServiceActivitesRegion serviceActivitesRegion;

    @GetMapping("/activites")
    public ServiceActivitesRegion.ActivitesRegion activites(@RequestParam(required = false) String regionId,
                                                            @RequestParam(required = false) Integer mois,
                                                            @RequestParam(required = false) Integer annee) {
        return serviceActivitesRegion.construire(regionId, mois, annee);
    }
}
