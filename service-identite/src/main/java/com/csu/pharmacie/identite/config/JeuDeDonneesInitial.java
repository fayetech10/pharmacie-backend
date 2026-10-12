package com.csu.pharmacie.identite.config;

import com.csu.pharmacie.entity.District;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.TypeStructure;
import com.csu.pharmacie.identite.depot.DepotDistrict;
import com.csu.pharmacie.identite.depot.DepotRegion;
import com.csu.pharmacie.identite.depot.DepotStructure;
import com.csu.pharmacie.identite.depot.DepotUtilisateur;
import com.csu.pharmacie.entity.Region;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.User;

import java.util.List;

/**
 * Amorçage d'une base vide : les 14 régions médicales, un district et un couple
 * centre / poste de démonstration, plus le compte administrateur.
 *
 * <p>N'écrit rien si des données existent déjà — le démarrage doit rester idempotent,
 * y compris sur une base de production.</p>
 */
@Component
@RequiredArgsConstructor
public class JeuDeDonneesInitial implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(JeuDeDonneesInitial.class);

    /** Les 14 régions médicales du Sénégal. */
    private static final String[][] REGIONS = {
            {"DK", "Dakar"}, {"TH", "Thiès"}, {"SL", "Saint-Louis"}, {"DL", "Diourbel"},
            {"FK", "Fatick"}, {"KL", "Kaolack"}, {"KF", "Kaffrine"}, {"TB", "Tambacounda"},
            {"KD", "Kolda"}, {"ZG", "Ziguinchor"}, {"LG", "Louga"}, {"MT", "Matam"},
            {"SE", "Sédhiou"}, {"KE", "Kédougou"}
    };

    private final DepotRegion depotRegion;
    private final DepotDistrict depotDistrict;
    private final DepotStructure depotStructure;
    private final DepotUtilisateur depotUtilisateur;
    private final PasswordEncoder encodeur;

    /**
     * District, centre et postes de démonstration. À désactiver en production
     * ({@code APP_DONNEES_DEMO=false}) : leurs codes (« CS-DK001 ») ne suivent pas le format
     * {RÉGION}{TYPE}{NN} dont dérivent les numéros de lettres de garantie et de bons.
     */
    @Value("${app.donnees-demo:true}")
    private boolean donneesDemo;

    @Override
    public void run(String... args) {
        amorcerRegions();
        if (donneesDemo) {
            String districtId = amorcerDistrict();
            amorcerStructures(districtId);
        }
        amorcerAdministrateur();
    }

    private void amorcerRegions() {
        if (depotRegion.count() > 0) {
            return;
        }
        for (String[] region : REGIONS) {
            depotRegion.save(Region.builder().code(region[0]).nom(region[1]).build());
        }
        log.info("Amorçage : {} régions médicales créées.", REGIONS.length);
    }

    private String amorcerDistrict() {
        if (depotDistrict.count() > 0) {
            return depotDistrict.findAll().get(0).getId();
        }
        String regionDakar = depotRegion.findByCodeIgnoreCase("DK").map(Region::getId).orElse(null);
        District district = depotDistrict.save(District.builder()
                .code("DK-CENTRE")
                .nom("District sanitaire Dakar Centre")
                .regionId(regionDakar)
                .actif(true)
                .build());
        log.info("Amorçage : district « {} » créé.", district.getNom());
        return district.getId();
    }

    /**
     * Un centre et un poste qui lui est rattaché : c'est le minimum pour que le circuit
     * poste → centre soit testable dès le premier démarrage.
     */
    private void amorcerStructures(String districtId) {
        if (depotStructure.count() > 0) {
            return;
        }
        String regionId = depotDistrict.findById(districtId).map(District::getRegionId).orElse(null);

        StructureSanitaire centre = depotStructure.save(StructureSanitaire.builder()
                .code("CS-DK001")
                .nom("Centre de santé Gaspard Camara")
                .typeStructure(TypeStructure.CS.name())
                .districtId(districtId)
                .regionId(regionId)
                .actif(true)
                .build());

        depotStructure.saveAll(List.of(
                StructureSanitaire.builder()
                        .code("PS-DK001")
                        .nom("Poste de santé Fass")
                        .typeStructure(TypeStructure.PS.name())
                        .structureRattachementId(centre.getId())
                        .districtId(districtId)
                        .regionId(regionId)
                        .actif(true)
                        .build(),
                StructureSanitaire.builder()
                        .code("PS-DK002")
                        .nom("Poste de santé Colobane")
                        .typeStructure(TypeStructure.PS.name())
                        .structureRattachementId(centre.getId())
                        .districtId(districtId)
                        .regionId(regionId)
                        .actif(true)
                        .build()));

        log.info("Amorçage : 1 centre de santé et 2 postes rattachés créés.");
    }

    /**
     * Mot de passe du premier compte administrateur. Le défaut est public (il figure dans la
     * documentation) : sur un serveur exposé, n'importe qui pouvait prendre la main sur la
     * plateforme entre son démarrage et la première connexion de l'administrateur.
     * En production : ADMIN_MOT_DE_PASSE_INITIAL, à changer de toute façon à la connexion.
     */
    @Value("${app.admin.mot-de-passe-initial:password123}")
    private String motDePasseAdministrateur;

    private void amorcerAdministrateur() {
        if (depotUtilisateur.count() > 0) {
            return;
        }
        depotUtilisateur.save(User.builder()
                .nom("Administrateur")
                .prenom("SEN-CSU")
                .email("admin@sencsu.sn")
                .username("ADMIN")
                .password(encodeur.encode(motDePasseAdministrateur))
                .role(Role.ADMIN)
                .actif(true)
                .mustChangePassword(true)
                .build());
        if ("password123".equals(motDePasseAdministrateur)) {
            log.warn("Amorçage : compte administrateur « admin@sencsu.sn » créé avec le mot de passe "
                    + "par défaut « password123 ». À CHANGER dès la première connexion.");
        } else {
            log.info("Amorçage : compte administrateur « admin@sencsu.sn » créé avec le mot de passe "
                    + "initial fourni (ADMIN_MOT_DE_PASSE_INITIAL), à changer à la première connexion.");
        }
    }
}
