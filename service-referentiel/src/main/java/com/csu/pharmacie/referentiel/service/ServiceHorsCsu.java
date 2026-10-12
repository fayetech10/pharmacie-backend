package com.csu.pharmacie.referentiel.service;

import com.csu.pharmacie.entity.PrestationHorsCsu;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.csu.pharmacie.commun.export.LecteurExcel;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.referentiel.depot.DepotPrestationHorsCsu;
import com.csu.pharmacie.referentiel.dto.ResultatImportReferentiel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Référentiel Hors CSU : tarifs applicables aux patients payants (§8). */
@Service
@RequiredArgsConstructor
public class ServiceHorsCsu {

    private final DepotPrestationHorsCsu depot;

    /**
     * Tarifs réellement <b>facturables</b> par la structure : son catalogue, moins les
     * lignes nationales qu'elle n'a jamais tarifées.
     *
     * <p>Le catalogue national des médicaments de dépôt est posé à 0 F : ce sont des
     * <em>désignations</em> proposées à qui veut les reprendre, pas des tarifs. Les servir
     * ici mettait 78 médicaments à 0 F dans la liste de caisse de <b>toute</b> structure —
     * y compris un centre de santé qui n'a pas de dépôt, et qui les prenait pour les
     * prestations d'un poste. Facturer 0 F n'a de toute façon aucun sens.</p>
     *
     * <p>Une ligne à 0 F <em>propre à la structure</em> est en revanche conservée : là,
     * le zéro est un choix (gratuité assumée), pas un prix manquant.</p>
     */
    public List<PrestationHorsCsu> applicables(String structureId) {
        return catalogue(structureId).stream()
                .filter(tarif -> tarif.getStructureId() != null || tarif.getPrix() > 0)
                .toList();
    }

    /**
     * Catalogue complet visible par une structure : le référentiel national, complété (et
     * surchargé) par ses propres tarifs — y compris les lignes non encore tarifées.
     *
     * <p>C'est ce que l'on propose à la structure pour <b>choisir ce qu'elle veut tarifer</b>
     * (modèle d'import Excel), à distinguer de {@link #applicables} qui ne rend que le
     * vendable.</p>
     */
    public List<PrestationHorsCsu> catalogue(String structureId) {
        List<PrestationHorsCsu> tarifs = depot.applicablesA(structureId);
        // En cas de doublon de code, la version propre à la structure l'emporte.
        return tarifs.stream()
                .collect(java.util.stream.Collectors.toMap(
                        p -> p.getCode().toLowerCase(Locale.ROOT),
                        p -> p,
                        (national, specifique) -> specifique.getStructureId() != null ? specifique : national,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();
    }

    public List<PrestationHorsCsu> lister() {
        return depot.findAll();
    }

    /** Tarifs administrés par la structure elle-même (son écran « Paramètres »). */
    public List<PrestationHorsCsu> mesTarifs(String structureId) {
        if (structureId == null || structureId.isBlank()) {
            return List.of();
        }
        return depot.findByStructureIdOrderByCategorieAscDesignationAsc(structureId);
    }

    public PrestationHorsCsu parId(String id) {
        return depot.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Tarif Hors CSU introuvable : " + id));
    }

    @Transactional
    public PrestationHorsCsu creer(PrestationHorsCsu tarif) {
        if (tarif.getDesignation() == null || tarif.getDesignation().isBlank()) {
            throw ExceptionMetier.invalide("La désignation de la prestation est obligatoire");
        }
        tarif.setDesignation(tarif.getDesignation().trim());
        if (tarif.getCode() == null || tarif.getCode().isBlank()) {
            // Prestation saisie en cours de facturation : l'agent ne connaît pas de code,
            // on le dérive de la désignation, comme l'import Excel.
            tarif.setCode(codeDepuis(tarif.getDesignation()));
        }
        tarif.setCode(tarif.getCode().trim().toUpperCase(Locale.ROOT));
        // Unicité par structure, pas globale : une structure doit pouvoir se fixer son
        // propre prix sur un code du référentiel national (c'est tout l'objet de
        // « applicables »), et deux structures doivent pouvoir employer le même code.
        if (depot.findByCodeIgnoreCaseAndStructureId(tarif.getCode(), tarif.getStructureId()).isPresent()) {
            throw ExceptionMetier.conflit(tarif.getStructureId() == null
                    ? "Un tarif national porte déjà le code « " + tarif.getCode() + " »"
                    : "Vous avez déjà un tarif portant le code « " + tarif.getCode() + " »");
        }
        if (tarif.getPrix() < 0) {
            throw ExceptionMetier.invalide("Le prix ne peut pas être négatif");
        }
        tarif.setId(null);
        return depot.save(tarif);
    }

    /** Code dérivé d'une désignation : majuscules sans accents, mots séparés par « _ ». */
    static String codeDepuis(String designation) {
        String code = java.text.Normalizer.normalize(designation.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return code.isEmpty() ? "PRESTATION" : code;
    }

    @Transactional
    public PrestationHorsCsu modifier(String id, PrestationHorsCsu modifications) {
        PrestationHorsCsu tarif = parId(id);
        if (modifications.getDesignation() != null) {
            tarif.setDesignation(modifications.getDesignation());
        }
        if (modifications.getCategorie() != null) {
            tarif.setCategorie(modifications.getCategorie());
        }
        if (modifications.getPrix() < 0) {
            throw ExceptionMetier.invalide("Le prix ne peut pas être négatif");
        }
        tarif.setPrix(modifications.getPrix());
        // structureId n'est délibérément PAS repris du corps de la requête : l'appartenance
        // d'un tarif se décide à sa création. La recopier ici laissait une structure
        // s'approprier un tarif national (ou le rendre national) d'un simple PUT.
        return depot.save(tarif);
    }

    @Transactional
    public PrestationHorsCsu basculerActivation(String id, boolean actif) {
        PrestationHorsCsu tarif = parId(id);
        tarif.setActif(actif);
        return depot.save(tarif);
    }

    // ═══════════════ Import « Service | Prestation | Prix (FCFA) » ═══════════════

    /** Colonnes du modèle (fichier type du 07/10/2026), dans l'ordre. */
    static final String[] COLONNES_MODELE = {"Service", "Prestation", "Prix (FCFA)"};
    /** Synonymes reconnus à l'import — les anciens modèles (Code, Designation, Categorie, Prix) passent toujours. */
    private static final String[] COL_SERVICE = {"Service", "Categorie", "Catégorie", "Groupe"};
    private static final String[] COL_PRESTATION = {"Prestation", "Prestations", "Designation", "Désignation",
            "Libelle", "Libellé", "Acte", "Nom"};
    private static final String[] COL_PRIX = {"Prix (FCFA)", "Prix", "Prix unitaire", "Prix unitaire (FCFA)",
            "Tarif", "Tarif (FCFA)", "Montant", "Montant (FCFA)", "PU"};
    private static final String[] COL_CODE = {"Code", "Code acte"};
    /** En deçà, un service inconnu du fichier est signalé (souvent une colonne décalée). */
    private static final int SEUIL_SERVICE_RARE = 5;

    /**
     * Modèle d'import « Service | Prestation | Prix (FCFA) », prérempli du catalogue de la
     * structure (le sien et le national) : il ne reste qu'à saisir les prix manquants, puis à
     * le réimporter tel quel. Un prix vide se laisse vide (0 F n'est pas un prix).
     */
    public byte[] modeleImport(String structureId) {
        // catalogue() et non applicables() : le modèle doit justement proposer les lignes
        // PAS encore tarifées — c'est tout son objet.
        List<PrestationHorsCsu> catalogue = new ArrayList<>(catalogue(structureId));
        catalogue.sort(Comparator.comparing((PrestationHorsCsu t) -> t.getCategorie() == null ? "" : t.getCategorie(),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PrestationHorsCsu::getDesignation, String.CASE_INSENSITIVE_ORDER));
        try (Workbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Sheet feuille = classeur.createSheet("Prestations");

            CellStyle styleEntete = classeur.createCellStyle();
            Font gras = classeur.createFont();
            gras.setBold(true);
            styleEntete.setFont(gras);

            Row entete = feuille.createRow(0);
            for (int i = 0; i < COLONNES_MODELE.length; i++) {
                Cell cellule = entete.createCell(i);
                cellule.setCellValue(COLONNES_MODELE[i]);
                cellule.setCellStyle(styleEntete);
            }

            int ligne = 1;
            for (PrestationHorsCsu tarif : catalogue) {
                Row r = feuille.createRow(ligne++);
                r.createCell(0).setCellValue(tarif.getCategorie() == null ? "" : tarif.getCategorie());
                r.createCell(1).setCellValue(tarif.getDesignation());
                if (tarif.getPrix() > 0) {
                    r.createCell(2).setCellValue(tarif.getPrix());
                }
            }
            for (int i = 0; i < COLONNES_MODELE.length; i++) {
                feuille.autoSizeColumn(i);
            }
            feuille.createFreezePane(0, 1);

            classeur.write(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw ExceptionMetier.invalide("Génération du modèle impossible : " + e.getMessage());
        }
    }

    /**
     * Import de la liste des prestations d'une structure (ou du national, pour un compte
     * d'administration) — modèle « Service | Prestation | Prix (FCFA) ».
     *
     * <ul>
     *   <li>Une prestation est identifiée par son <b>service et sa désignation</b> : la même
     *       prestation peut figurer dans plusieurs services (« Kit SRO + Zinc » au Dépositaire,
     *       en Pédiatrie, au Tri enfant…), chacun la retrouve à la facturation. Avant, le code
     *       dérivé du seul nom faisait écraser chaque ligne par la suivante — dernier service
     *       et dernier prix (souvent vide) gagnants. Une colonne « Code », si présente, reste
     *       prioritaire.</li>
     *   <li>Prix vide : la prestation est créée à 0 F et signalée ; une prestation existante
     *       garde son prix (réimporter le modèle ne remet rien à zéro).</li>
     *   <li>Services ramenés à une seule écriture ({@link NomsDeServices}) ; les services
     *       rares et inconnus sont signalés.</li>
     * </ul>
     */
    @Transactional
    public ResultatImportReferentiel importer(MultipartFile fichier, String structureId) {
        ResultatImportReferentiel resultat = new ResultatImportReferentiel();
        List<LecteurExcel.LigneImport> lignes;
        try {
            lignes = LecteurExcel.lire(fichier.getInputStream());
        } catch (IOException e) {
            throw ExceptionMetier.invalide("Lecture du fichier impossible : " + e.getMessage());
        }
        resultat.setLignesLues(lignes.size());

        // Recherche scopée à la structure : sans cela, l'import d'un poste retrouvait la ligne
        // d'un AUTRE poste (ou la ligne nationale) et la réécrivait.
        List<PrestationHorsCsu> siens = new ArrayList<>(tarifsDuPerimetre(structureId));
        List<PrestationHorsCsu> nationaux = structureId == null ? List.of()
                : depot.applicablesA(structureId).stream().filter(t -> t.getStructureId() == null).toList();
        Set<String> codesPris = new HashSet<>();
        siens.forEach(t -> codesPris.add(t.getCode().toUpperCase(Locale.ROOT)));

        NomsDeServices services = new NomsDeServices(
                siens.stream().map(PrestationHorsCsu::getCategorie).filter(c -> c != null && !c.isBlank()).toList(),
                lignes.stream().map(l -> l.texte(COL_SERVICE)).toList());

        int sansPrix = 0;
        for (LecteurExcel.LigneImport ligne : lignes) {
            String designation = ligne.texte(COL_PRESTATION);
            if (designation == null) {
                resultat.erreur(ligne.numeroLigne(), "colonne « Prestation » obligatoire");
                continue;
            }
            designation = designation.trim().replaceAll("\\s+", " ");
            String service = services.canonique(ligne.texte(COL_SERVICE));
            Double prix;
            try {
                prix = ligne.texte(COL_PRIX) == null ? null : ligne.nombre(COL_PRIX);
            } catch (ExceptionMetier e) {
                resultat.erreur(ligne.numeroLigne(), "prix illisible « " + ligne.texte(COL_PRIX) + " »");
                continue;
            }
            if (prix != null && prix < 0) {
                resultat.erreur(ligne.numeroLigne(), "le prix ne peut pas être négatif");
                continue;
            }

            String code = ligne.texte(COL_CODE);
            PrestationHorsCsu tarif = code != null
                    ? trouverParCode(siens, code)
                    : trouverParServiceEtDesignation(siens, service, designation);
            boolean nouveau = tarif == null;
            if (nouveau) {
                tarif = new PrestationHorsCsu();
                String codeRetenu = code != null ? code.trim().toUpperCase(Locale.ROOT)
                        : codeLibre(designation, service, nationaux, codesPris);
                tarif.setCode(codeRetenu);
                tarif.setStructureId(structureId);
                codesPris.add(codeRetenu);
                siens.add(tarif);
            }
            tarif.setDesignation(designation);
            if (service != null) {
                tarif.setCategorie(service);
            }
            if (prix != null) {
                tarif.setPrix(prix);
            } else if (nouveau) {
                tarif.setPrix(0);
                sansPrix++;
            }
            tarif.setActif(true);
            depot.save(tarif);

            if (nouveau) {
                resultat.setCrees(resultat.getCrees() + 1);
            } else {
                resultat.setMisAJour(resultat.getMisAJour() + 1);
            }
        }

        if (sansPrix > 0) {
            resultat.avertir(sansPrix + " prestation(s) importée(s) sans prix, enregistrée(s) à 0 F : saisissez "
                    + "leur prix dans la liste des prestations (ou à la facturation, il y sera reporté).");
        }
        Map<String, Integer> rares = services.rares(SEUIL_SERVICE_RARE);
        if (!rares.isEmpty()) {
            StringBuilder liste = new StringBuilder();
            rares.forEach((nom, n) -> liste.append(liste.isEmpty() ? "" : ", ").append("« ").append(nom).append(" » (")
                    .append(n).append(n > 1 ? " lignes)" : " ligne)"));
            resultat.avertir("Services peu employés, à vérifier (souvent une prestation saisie dans la colonne "
                    + "« Service ») : " + liste + ".");
        }
        return resultat;
    }

    /** Tarifs déjà enregistrés dans le périmètre de l'import (la structure, ou le national). */
    private List<PrestationHorsCsu> tarifsDuPerimetre(String structureId) {
        if (structureId != null && !structureId.isBlank()) {
            return depot.findByStructureIdOrderByCategorieAscDesignationAsc(structureId);
        }
        return depot.findAll().stream().filter(t -> t.getStructureId() == null).toList();
    }

    private static PrestationHorsCsu trouverParCode(List<PrestationHorsCsu> tarifs, String code) {
        return tarifs.stream().filter(t -> t.getCode().equalsIgnoreCase(code.trim())).findFirst().orElse(null);
    }

    private static PrestationHorsCsu trouverParServiceEtDesignation(List<PrestationHorsCsu> tarifs,
                                                                   String service, String designation) {
        String cleService = NomsDeServices.cle(service);
        String cleDesignation = NomsDeServices.cle(designation);
        return tarifs.stream()
                .filter(t -> NomsDeServices.cle(t.getDesignation()).equals(cleDesignation)
                        && NomsDeServices.cle(t.getCategorie()).equals(cleService))
                .findFirst().orElse(null);
    }

    /**
     * Code d'une prestation créée par l'import. Le tarif national de même service et même
     * désignation prête le sien (la version de la structure le surcharge alors, cf.
     * {@link #catalogue}) ; sinon le code dérivé du nom, et s'il est déjà pris — la même
     * prestation dans un autre service — celui dérivé du service et du nom, numéroté au besoin.
     */
    static String codeLibre(String designation, String service, List<PrestationHorsCsu> nationaux, Set<String> codesPris) {
        String cleService = NomsDeServices.cle(service);
        String cleDesignation = NomsDeServices.cle(designation);
        String national = nationaux.stream()
                .filter(t -> NomsDeServices.cle(t.getDesignation()).equals(cleDesignation)
                        && NomsDeServices.cle(t.getCategorie()).equals(cleService))
                .map(t -> t.getCode().toUpperCase(Locale.ROOT))
                .findFirst().orElse(null);
        List<String> candidats = new ArrayList<>();
        if (national != null) candidats.add(national);
        candidats.add(codeDepuis(designation));
        if (service != null && !service.isBlank()) candidats.add(codeDepuis(service + " " + designation));
        for (String candidat : candidats) {
            if (!codesPris.contains(candidat)) return candidat;
        }
        String base = candidats.get(candidats.size() - 1);
        int n = 2;
        while (codesPris.contains(base + "_" + n)) n++;
        return base + "_" + n;
    }
}
