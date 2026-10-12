package com.csu.pharmacie.referentiel.config;

import com.csu.pharmacie.entity.PrestationHorsCsu;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.csu.pharmacie.referentiel.depot.DepotPrestationHorsCsu;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catalogue des médicaments et consommables du <b>dépôt d'un poste de santé</b>, d'après la
 * liste nationale des médicaments et produits essentiels (niveau poste de santé).
 *
 * <p>Ces lignes sont posées en <b>référentiel national</b> ({@code structureId} nul) et à
 * <b>prix zéro</b> : ce sont des <em>désignations</em>, pas des tarifs. Le prix d'un
 * médicament de dépôt varie d'un poste à l'autre ; chaque poste fixe le sien depuis son
 * écran « Paramètres », ce qui crée une ligne à son nom que
 * {@code ServiceHorsCsu.applicables} fait primer sur celle-ci. Inscrire ici des montants
 * inventés reviendrait à facturer des patients sur des chiffres que personne n'a validés.</p>
 *
 * <p>La catégorie {@code Dépositaire} est le discriminant : c'est elle que lit la liste
 * déroulante « Dépositaire » du formulaire de facturation.</p>
 *
 * <p>Idempotent et <b>sans court-circuit sur base déjà peuplée</b> — contrairement à
 * {@link JeuDeDonneesReferentiel}, qui ne s'exécute que sur une base vierge et n'aurait donc
 * jamais posé ce catalogue sur les installations existantes. Chaque code est inséré s'il
 * manque, et jamais réécrit : un prix ajusté à la main sur la ligne nationale survit aux
 * redémarrages.</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class CatalogueDepositairePoste implements CommandLineRunner {

    /** Discriminant lu par le formulaire de facturation pour la liste « Dépositaire ». */
    public static final String CATEGORIE = "Dépositaire";

    private final DepotPrestationHorsCsu depot;

    @Override
    public void run(String... args) {
        Map<String, String> catalogue = catalogue();
        int crees = 0;
        for (Map.Entry<String, String> entree : catalogue.entrySet()) {
            if (depot.findByCodeIgnoreCaseAndStructureId(entree.getKey(), null).isPresent()) {
                continue;
            }
            depot.save(PrestationHorsCsu.builder()
                    .code(entree.getKey())
                    .designation(entree.getValue())
                    .categorie(CATEGORIE)
                    .prix(0)
                    .structureId(null)
                    .actif(true)
                    .build());
            crees++;
        }
        if (crees > 0) {
            log.info("Catalogue dépositaire poste de santé : {} médicament(s)/consommable(s) ajouté(s) "
                    + "au référentiel national, à tarifer par chaque poste.", crees);
        }
    }

    /** Code → désignation. LinkedHashMap : l'ordre thérapeutique est celui de l'affichage. */
    private static Map<String, String> catalogue() {
        Map<String, String> c = new LinkedHashMap<>();

        // ── Antipaludéens ──
        c.put("DEP-ACT-ADT", "Artéméther-Luméfantrine 20/120 mg adulte, comprimé");
        c.put("DEP-ACT-ENF", "Artéméther-Luméfantrine 20/120 mg enfant, comprimé");
        c.put("DEP-ART-INJ", "Artésunate injectable 60 mg");
        c.put("DEP-ART-SUP", "Artésunate suppositoire 100 mg");
        c.put("DEP-SP", "Sulfadoxine-Pyriméthamine 500/25 mg, comprimé");
        c.put("DEP-QUIN", "Quinine 300 mg, comprimé");
        c.put("DEP-TDR-PALU", "Test de diagnostic rapide du paludisme");

        // ── Antalgiques, antipyrétiques, anti-inflammatoires ──
        c.put("DEP-PARA-CP", "Paracétamol 500 mg, comprimé");
        c.put("DEP-PARA-SIR", "Paracétamol sirop 120 mg/5 ml");
        c.put("DEP-PARA-INJ", "Paracétamol injectable 1 g");
        c.put("DEP-IBU", "Ibuprofène 400 mg, comprimé");
        c.put("DEP-ASP", "Acide acétylsalicylique 500 mg, comprimé");
        c.put("DEP-DICLO", "Diclofénac 50 mg, comprimé");

        // ── Antibiotiques et anti-infectieux ──
        c.put("DEP-AMOX-GEL", "Amoxicilline 500 mg, gélule");
        c.put("DEP-AMOX-SIR", "Amoxicilline sirop 250 mg/5 ml");
        c.put("DEP-AMPI-INJ", "Ampicilline injectable 1 g");
        c.put("DEP-COTRI-CP", "Cotrimoxazole 480 mg, comprimé");
        c.put("DEP-COTRI-SIR", "Cotrimoxazole sirop 240 mg/5 ml");
        c.put("DEP-METRO-CP", "Métronidazole 250 mg, comprimé");
        c.put("DEP-METRO-SIR", "Métronidazole sirop 125 mg/5 ml");
        c.put("DEP-CIPRO", "Ciprofloxacine 500 mg, comprimé");
        c.put("DEP-DOXY", "Doxycycline 100 mg, comprimé");
        c.put("DEP-ERYTHRO", "Érythromycine 500 mg, comprimé");
        c.put("DEP-CEFTRI-INJ", "Ceftriaxone injectable 1 g");
        c.put("DEP-BENZA-INJ", "Benzathine benzylpénicilline 2,4 MUI injectable");
        c.put("DEP-GENTA-INJ", "Gentamicine injectable 80 mg");

        // ── Antiparasitaires ──
        c.put("DEP-ALBEN", "Albendazole 400 mg, comprimé");
        c.put("DEP-MEBEN", "Mébendazole 100 mg, comprimé");
        c.put("DEP-PRAZI", "Praziquantel 600 mg, comprimé");

        // ── Antianémiques, vitamines, nutrition ──
        c.put("DEP-FER-AF", "Fer + Acide folique, comprimé");
        c.put("DEP-AF", "Acide folique 5 mg, comprimé");
        c.put("DEP-VIT-A", "Vitamine A 200 000 UI, capsule");
        c.put("DEP-VIT-C", "Vitamine C 500 mg, comprimé");
        c.put("DEP-ZINC", "Zinc 20 mg, comprimé dispersible");
        c.put("DEP-SRO", "Sels de réhydratation orale, sachet");
        c.put("DEP-ATPE", "Aliment thérapeutique prêt à l'emploi, sachet");

        // ── Appareil digestif ──
        c.put("DEP-OMEP", "Oméprazole 20 mg, gélule");
        c.put("DEP-ANTIACIDE", "Antiacide hydroxyde d'aluminium/magnésium, comprimé");
        c.put("DEP-METOCLO", "Métoclopramide 10 mg, comprimé");
        c.put("DEP-BUTYL", "Butylscopolamine 10 mg, comprimé");

        // ── Respiratoire et allergie ──
        c.put("DEP-SALB-CP", "Salbutamol 4 mg, comprimé");
        c.put("DEP-SALB-SPR", "Salbutamol spray 100 µg");
        c.put("DEP-CHLORPHE", "Chlorphéniramine 4 mg, comprimé");
        c.put("DEP-PROMETH-SIR", "Prométhazine sirop");
        c.put("DEP-DEXA-INJ", "Dexaméthasone injectable 4 mg");
        c.put("DEP-HYDROCOR-INJ", "Hydrocortisone injectable 100 mg");

        // ── Cardiovasculaire et maladies chroniques ──
        c.put("DEP-AMLO", "Amlodipine 5 mg, comprimé");
        c.put("DEP-NIFE", "Nifédipine 20 mg LP, comprimé");
        c.put("DEP-METHYLDOPA", "Méthyldopa 250 mg, comprimé");
        c.put("DEP-FURO", "Furosémide 40 mg, comprimé");
        c.put("DEP-METFO", "Metformine 500 mg, comprimé");
        c.put("DEP-GLIBEN", "Glibenclamide 5 mg, comprimé");

        // ── Urgences, injectables et solutés ──
        c.put("DEP-ADRE-INJ", "Adrénaline injectable 1 mg");
        c.put("DEP-DIAZ-INJ", "Diazépam injectable 10 mg");
        c.put("DEP-MGSO4-INJ", "Sulfate de magnésium injectable");
        c.put("DEP-OCYTO-INJ", "Ocytocine injectable 5 UI");
        c.put("DEP-SSI", "Sérum salé isotonique 0,9 %, 500 ml");
        c.put("DEP-RL", "Ringer lactate, 500 ml");
        c.put("DEP-G5", "Sérum glucosé 5 %, 500 ml");
        c.put("DEP-LIDO", "Lidocaïne 2 % injectable");

        // ── Dermatologie et antiseptiques ──
        c.put("DEP-POVIDONE", "Povidone iodée 10 %, flacon");
        c.put("DEP-ALCOOL", "Alcool 70°, flacon");
        c.put("DEP-EAU-OXY", "Eau oxygénée 10 volumes, flacon");
        c.put("DEP-VIOLET", "Violet de gentiane, flacon");
        c.put("DEP-BENZOATE", "Benzoate de benzyle, lotion");
        c.put("DEP-MICONA", "Miconazole crème 2 %");
        c.put("DEP-TETRA-OPH", "Pommade ophtalmique tétracycline 1 %");

        // ── Santé de la reproduction ──
        c.put("DEP-COC", "Contraceptif oral combiné, plaquette");
        c.put("DEP-DMPA-INJ", "Médroxyprogestérone injectable (DMPA)");
        c.put("DEP-IMPLANT", "Implant contraceptif");
        c.put("DEP-PRESERVATIF", "Préservatif masculin");

        // ── Consommables ──
        c.put("DEP-COMPRESSE", "Compresses stériles, sachet");
        c.put("DEP-COTON", "Coton hydrophile");
        c.put("DEP-SPARADRAP", "Sparadrap");
        c.put("DEP-GANT", "Gants d'examen, paire");
        c.put("DEP-SERINGUE", "Seringue + aiguille");
        c.put("DEP-FIL-SUT", "Fil de suture");
        c.put("DEP-BANDE", "Bande de gaze");

        return c;
    }
}
