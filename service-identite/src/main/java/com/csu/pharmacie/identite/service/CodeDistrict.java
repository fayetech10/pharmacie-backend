package com.csu.pharmacie.identite.service;

import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.Region;
import com.csu.pharmacie.identite.depot.DepotDistrict;
import com.csu.pharmacie.identite.depot.DepotRegion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Code attribué automatiquement à un district : {@code {CODE_RÉGION}DT{NN}} — THDT01, DKDT02.
 *
 * <p>Le formulaire ne demande pas ce code (demande du 08/10/2026) : la région, déjà saisie,
 * suffit à le composer. Partagé par la création d'un district et par celle d'un centre
 * « siège du district » qui crée son district — d'où un composant à part, sans dépendance
 * vers les services (qui dépendent déjà l'un de l'autre).</p>
 */
@Component
@RequiredArgsConstructor
public class CodeDistrict {

    private final DepotDistrict depotDistrict;
    private final DepotRegion depotRegion;

    /** Premier code libre pour un district de la région donnée. */
    public String generer(String regionId) {
        if (regionId == null || regionId.isBlank()) {
            throw ExceptionMetier.invalide("La région du district est obligatoire pour lui attribuer un code.");
        }
        Region region = depotRegion.findById(regionId)
                .orElseThrow(() -> ExceptionMetier.invalide("Région introuvable."));
        String racine = ((region.getCode() == null ? "" : region.getCode()) + "DT")
                .trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        String code;
        int n = 1;
        do {
            code = racine + String.format("%02d", n++);
        } while (depotDistrict.existsByCodeIgnoreCase(code));
        return code;
    }
}
