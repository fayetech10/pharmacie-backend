package com.csu.pharmacie.referentiel.config;

import com.csu.pharmacie.entity.Prestation;
import com.csu.pharmacie.entity.PrestationHorsCsu;
import com.csu.pharmacie.entity.ServiceMedical;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.csu.pharmacie.referentiel.depot.DepotPrestation;
import com.csu.pharmacie.referentiel.depot.DepotPrestationHorsCsu;
import com.csu.pharmacie.referentiel.depot.DepotServiceMedical;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Amorçage d'un référentiel minimal, pour que la facturation soit testable dès le premier
 * démarrage. Les prix sont des ordres de grandeur, destinés à être remplacés par l'import
 * du référentiel officiel.
 *
 * <p>Désactivé en production ({@code APP_DONNEES_DEMO=false}) : sur une base neuve, ces prix
 * indicatifs seraient facturés tant que personne n'a importé le référentiel officiel.</p>
 */
@Component
@RequiredArgsConstructor
public class JeuDeDonneesReferentiel implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(JeuDeDonneesReferentiel.class);

    private final DepotServiceMedical depotService;
    private final DepotPrestation depotPrestation;
    private final DepotPrestationHorsCsu depotHorsCsu;

    @Value("${app.donnees-demo:true}")
    private boolean donneesDemo;

    @Override
    public void run(String... args) {
        if (!donneesDemo || depotService.count() > 0 || depotPrestation.count() > 0) {
            return;
        }

        Map<String, String> services = new LinkedHashMap<>();
        services.put("CONS", "Consultation");
        services.put("MAT", "Maternité");
        services.put("LABO", "Laboratoire");
        services.put("PHAR", "Pharmacie");
        services.put("HOSP", "Hospitalisation");
        services.put("IMAG", "Imagerie");

        Map<String, String> ids = new LinkedHashMap<>();
        int ordre = 10;
        for (Map.Entry<String, String> service : services.entrySet()) {
            ServiceMedical cree = depotService.save(ServiceMedical.builder()
                    .code(service.getKey())
                    .nom(service.getValue())
                    .ordre(ordre)
                    .actif(true)
                    .build());
            ids.put(service.getKey(), cree.getId());
            ordre += 10;
        }

        creer("CONS-GEN", "Consultation générale", ids.get("CONS"), 1000);
        creer("CONS-SPE", "Consultation spécialisée", ids.get("CONS"), 3000);
        creer("CONS-ENF", "Consultation enfant de moins de 5 ans", ids.get("CONS"), 500);
        creer("MAT-CPN", "Consultation prénatale", ids.get("MAT"), 1000);
        creer("MAT-ACC", "Accouchement simple", ids.get("MAT"), 5000);
        creer("MAT-CES", "Césarienne", ids.get("MAT"), 60000);
        creer("LABO-GE", "Goutte épaisse", ids.get("LABO"), 1500);
        creer("LABO-NFS", "Numération formule sanguine", ids.get("LABO"), 3500);
        creer("IMAG-ECHO", "Échographie", ids.get("IMAG"), 7000);
        creer("HOSP-JOUR", "Journée d'hospitalisation", ids.get("HOSP"), 4000);

        depotHorsCsu.save(PrestationHorsCsu.builder()
                .code("HC-CONS").designation("Consultation (patient payant)")
                .categorie("Consultation").prix(2000).actif(true).build());
        depotHorsCsu.save(PrestationHorsCsu.builder()
                .code("HC-PANS").designation("Pansement").categorie("Soins").prix(1500).actif(true).build());
        depotHorsCsu.save(PrestationHorsCsu.builder()
                .code("HC-INJ").designation("Injection").categorie("Soins").prix(1000).actif(true).build());

        log.info("Amorçage : {} services, 10 prestations CSU et 3 tarifs Hors CSU créés.", services.size());
    }

    private void creer(String code, String designation, String serviceId, double prix) {
        depotPrestation.save(Prestation.builder()
                .code(code)
                .designation(designation)
                .serviceId(serviceId)
                .prix(prix)
                .actif(true)
                .build());
    }
}
