package com.csu.pharmacie.referentiel.config;

import com.csu.pharmacie.entity.PrestationHorsCsu;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.csu.pharmacie.referentiel.depot.DepotPrestationHorsCsu;

import java.util.List;
import java.util.Locale;

/**
 * Retire l'ancien catalogue « Dépositaire » du référentiel national (78 médicaments à 0 F,
 * codes {@code DEP-…}, posés au démarrage par l'ex-{@code CatalogueDepositairePoste}).
 *
 * <p>Depuis le 14/10/2026, la liste de référence des postes de santé est celle fournie par
 * l'utilisateur, importée par l'administration comme <b>prestations par défaut des postes</b>
 * (lignes nationales {@code typeStructure = "PS"}). Garder l'ancienne liste proposait chaque
 * médicament sous deux noms (« ACT ADULTE Comprimé » / « Artéméther-Luméfantrine 20/120 mg
 * adulte… »).</p>
 *
 * <p>Désactivation et non suppression : réversible. Seules les lignes encore telles que posées
 * (nationales, communes, à 0 F) sont touchées ; les prix qu'un poste s'est fixés sur ces codes
 * sont des lignes à son nom et restent. Idempotent.</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RetraitAncienCatalogueDepositaire implements CommandLineRunner {

    private final DepotPrestationHorsCsu depot;

    @Override
    public void run(String... args) {
        List<PrestationHorsCsu> anciennes = depot.findAll().stream()
                .filter(t -> t.getStructureId() == null && t.getTypeStructure() == null && t.isActif())
                .filter(t -> t.getPrix() == 0 && t.getCode() != null
                        && t.getCode().toUpperCase(Locale.ROOT).startsWith("DEP-"))
                .toList();
        if (anciennes.isEmpty()) {
            return;
        }
        anciennes.forEach(t -> t.setActif(false));
        depot.saveAll(anciennes);
        log.info("Ancien catalogue dépositaire national : {} ligne(s) désactivée(s), remplacé par les "
                + "prestations par défaut des postes de santé.", anciennes.size());
    }
}
