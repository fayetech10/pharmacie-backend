package com.csu.pharmacie.identite.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.identite.depot.DepotRegion;
import com.csu.pharmacie.identite.depot.DepotUtilisateur;
import com.csu.pharmacie.entity.Region;
import com.csu.pharmacie.entity.User;


/**
 * Amorçage minimal d'une base neuve : régions officielles et compte administrateur.
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
    private final DepotUtilisateur depotUtilisateur;
    private final PasswordEncoder encodeur;

    @Override
    public void run(String... args) {
        amorcerRegions();
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
