package com.csu.pharmacie.centre.service;

import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.dto.DistrictDto;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.facturation.DepotFactureStructure;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.TypeStructure;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Activités du réseau d'une région (14/10/2026) : ce que font ses districts, centres et postes
 * de santé sur une période — factures et leur statut, patients, visites, dossiers à compléter,
 * montants, dernière saisie.
 *
 * <p>Même règle de comptage que la consolidation district : <b>toutes</b> les factures de la
 * période, brouillons et rejetées compris, ventilées « transmises »
 * ({@code StatutFacture.estComptabilise}) / « en cours ». Un poste garde sa facture en
 * brouillon jusqu'à l'envoi de fin de mois : ne compter que les transmises laisserait la vue à
 * zéro tout le mois. Seuls des agrégats et des résumés de factures sortent d'ici.</p>
 *
 * <p>Périmètre : les structures de la région (service identité) et leurs factures — lues par
 * structure, ce qui rattrape d'office celles dont le district ou la région n'ont pas été figés.
 * Toutes les structures actives figurent, à zéro si elles n'ont rien saisi : ce sont celles que
 * la région doit relancer.</p>
 */
@Service
@RequiredArgsConstructor
public class ServiceActivitesRegion {

    /** Ordre d'affichage dans un district : centres et hôpitaux d'abord, puis leurs postes. */
    private static final Map<TypeStructure, Integer> ORDRE_TYPE = Map.of(
            TypeStructure.CS, 0, TypeStructure.EPS, 1, TypeStructure.PS, 2);

    private final DepotFactureStructure depot;
    private final ClientIdentite clientIdentite;

    public ActivitesRegion construire(String regionIdDemandee, Integer mois, Integer annee) {
        String regionId = regionAutorisee(regionIdDemandee);
        int an = annee == null || annee <= 0 ? LocalDate.now().getYear() : annee;
        Integer moisVise = mois == null || mois < 1 || mois > 12 ? null : mois;

        List<StructureDto> structures = clientIdentite.structuresDeLaRegion(regionId).stream()
                .filter(s -> s.getId() != null)
                .toList();
        List<String> ids = structures.stream().map(StructureDto::getId).toList();
        List<FactureStructure> factures = ids.isEmpty() ? List.of()
                : depot.findByStructureSanitaireIdInAndAnnee(ids, an).stream()
                .filter(f -> f.getStatut() != null)
                .filter(f -> moisVise == null || f.getMois() == moisVise)
                .toList();

        Map<String, List<FactureStructure>> parStructure = new LinkedHashMap<>();
        for (FactureStructure facture : factures) {
            parStructure.computeIfAbsent(facture.getStructureSanitaireId(), k -> new ArrayList<>()).add(facture);
        }

        // Districts de la région, y compris ceux qui n'ont encore aucune structure ; à défaut
        // (identité injoignable ou plus ancienne), ceux que portent les structures.
        Map<String, ActiviteDistrict> districts = new LinkedHashMap<>();
        ActivitesRegion resultat = new ActivitesRegion();
        for (DistrictDto dto : clientIdentite.districtsDeLaRegion(regionId)) {
            if (dto.getId() != null) {
                districts.put(dto.getId(), ActiviteDistrict.depuis(dto.getId(), dto.getCode(), dto.getNom(), dto.isActif()));
                if (resultat.regionNom == null) {
                    resultat.regionNom = dto.getRegionNom();
                }
            }
        }

        resultat.regionId = regionId;
        resultat.mois = moisVise;
        resultat.annee = an;
        resultat.periode = moisVise == null ? "Année " + an : FactureStructure.MOIS[moisVise] + " " + an;

        for (StructureDto structure : structures) {
            List<FactureStructure> siennes = parStructure.getOrDefault(structure.getId(), List.of());
            // Une structure désactivée n'apparaît que si elle a encore saisi sur la période.
            if (!structure.isActif() && siennes.isEmpty()) {
                continue;
            }
            if (resultat.regionNom == null) {
                resultat.regionNom = structure.getRegionNom();
            }
            String cle = structure.getDistrictId() == null || structure.getDistrictId().isBlank()
                    ? SANS_DISTRICT : structure.getDistrictId();
            ActiviteDistrict district = districts.computeIfAbsent(cle, k -> SANS_DISTRICT.equals(k)
                    ? ActiviteDistrict.depuis(null, null, "Sans district", true)
                    : ActiviteDistrict.depuis(k, null, structure.getDistrictNom(), true));
            district.ajouter(ActiviteStructure.depuis(structure, siennes));
        }

        List<ActiviteDistrict> liste = new ArrayList<>(districts.values());
        liste.forEach(ActiviteDistrict::trier);
        // « Sans district » en dernier, les autres par nom.
        liste.sort(Comparator.comparing((ActiviteDistrict d) -> d.getId() == null)
                .thenComparing(d -> sansAccent(d.getNom())));
        resultat.districts = liste;
        for (ActiviteDistrict district : liste) {
            resultat.indicateurs.fusionner(district.getIndicateurs());
            resultat.nombreStructures += district.getNombreStructures();
            resultat.structuresActives += district.getStructuresActives();
        }
        resultat.nombreDistricts = (int) liste.stream().filter(d -> d.getId() != null).count();
        return resultat;
    }

    /**
     * Région consultable par l'appelant. Le service régional ne voit que la sienne — celle de
     * son jeton —, quelle que soit la région demandée ; le niveau central et l'administrateur
     * désignent la région voulue.
     */
    private String regionAutorisee(String regionIdDemandee) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        Role role = utilisateur.getRole();
        boolean demandee = regionIdDemandee != null && !regionIdDemandee.isBlank();

        if (role == Role.SERVICE_REGIONAL) {
            String region = utilisateur.getRegionId();
            if (region == null || region.isBlank()) {
                throw ExceptionMetier.interdit("Votre compte n'est rattaché à aucune région.");
            }
            if (demandee && !regionIdDemandee.equals(region)) {
                throw ExceptionMetier.interdit("Vous ne pouvez consulter que les activités de votre région.");
            }
            return region;
        }
        if (role == Role.SERVICE_CENTRAL || role == Role.ADMIN) {
            if (!demandee) {
                throw ExceptionMetier.invalide("Précisez la région à consulter.");
            }
            return regionIdDemandee;
        }
        throw ExceptionMetier.interdit("Activités du réseau réservées au service régional et au niveau central.");
    }

    private static final String SANS_DISTRICT = "__sans_district__";

    private static String sansAccent(String texte) {
        return texte == null ? "" : Normalizer.normalize(texte, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    // ────────────────────────────── réponse ──────────────────────────────

    /** Vue d'ensemble de la région sur la période. */
    @Data
    public static class ActivitesRegion {
        private String regionId;
        private String regionNom;
        /** {@code null} = toute l'année. */
        private Integer mois;
        private int annee;
        private String periode;
        private int nombreDistricts;
        /** Structures affichées : actives, plus les désactivées qui ont saisi sur la période. */
        private int nombreStructures;
        /** Structures ayant au moins une facture sur la période. */
        private int structuresActives;
        private Indicateurs indicateurs = new Indicateurs();
        private List<ActiviteDistrict> districts = new ArrayList<>();
    }

    /** Un district et ses structures. {@code id} nul : structures sans district. */
    @Data
    public static class ActiviteDistrict {
        private String id;
        private String code;
        private String nom;
        private boolean actif;
        private int nombrePostes;
        private int nombreCentres;
        private int nombreStructures;
        private int structuresActives;
        private Indicateurs indicateurs = new Indicateurs();
        private List<ActiviteStructure> structures = new ArrayList<>();

        static ActiviteDistrict depuis(String id, String code, String nom, boolean actif) {
            ActiviteDistrict district = new ActiviteDistrict();
            district.id = id;
            district.code = code;
            district.nom = nom == null || nom.isBlank() ? "District" : nom;
            district.actif = actif;
            return district;
        }

        void ajouter(ActiviteStructure structure) {
            structures.add(structure);
            nombreStructures++;
            if (structure.getType() == TypeStructure.PS) {
                nombrePostes++;
            } else {
                nombreCentres++;
            }
            if (structure.getIndicateurs().getNombreFactures() > 0) {
                structuresActives++;
            }
            indicateurs.fusionner(structure.getIndicateurs());
        }

        void trier() {
            structures.sort(Comparator
                    .comparing((ActiviteStructure s) -> ORDRE_TYPE.getOrDefault(s.getType(), 3))
                    .thenComparing(s -> sansAccent(s.getNom())));
        }

        /** Part des structures ayant saisi sur la période, en pourcentage. */
        public double getTauxActivite() {
            return nombreStructures == 0 ? 0 : Math.round(structuresActives * 1000.0 / nombreStructures) / 10.0;
        }
    }

    /** Une structure (poste, centre, EPS) et ses factures de la période. */
    @Data
    public static class ActiviteStructure {
        private String id;
        private String code;
        private String nom;
        private TypeStructure type;
        private boolean actif;
        private boolean siegeDistrict;
        /** Pour un poste : son centre de rattachement. */
        private String structureRattachementNom;
        private Indicateurs indicateurs = new Indicateurs();
        private List<ResumeFacture> factures = new ArrayList<>();

        static ActiviteStructure depuis(StructureDto dto, List<FactureStructure> factures) {
            ActiviteStructure structure = new ActiviteStructure();
            structure.id = dto.getId();
            structure.code = dto.getCode();
            structure.nom = dto.getNom();
            structure.type = dto.getTypeStructure();
            structure.actif = dto.isActif();
            structure.siegeDistrict = dto.isSiegeDistrict();
            structure.structureRattachementNom = dto.getStructureRattachementNom();
            for (FactureStructure facture : factures) {
                structure.indicateurs.ajouter(facture);
                structure.factures.add(ResumeFacture.depuis(facture));
            }
            // Les plus récentes d'abord (mois décroissant), puis par régime.
            structure.factures.sort(Comparator.comparing(ResumeFacture::getMois).reversed()
                    .thenComparing(f -> f.getRegime() == null ? "" : f.getRegime().name()));
            return structure;
        }
    }

    /** Résumé d'une facture : de quoi la situer et l'ouvrir, sans ses lignes ni ses dossiers. */
    @Data
    public static class ResumeFacture {
        private String id;
        private String numero;
        private Regime regime;
        private int mois;
        private int annee;
        private String statut;
        private String etape;
        private int patients;
        private int visites;
        private int dossiersACompleter;
        private double montantTotal;
        private double montantSencsu;
        private boolean importee;
        private LocalDateTime derniereSaisie;

        static ResumeFacture depuis(FactureStructure facture) {
            ResumeFacture resume = new ResumeFacture();
            resume.id = facture.getId();
            resume.numero = facture.getNumero();
            resume.regime = facture.getRegime();
            resume.mois = facture.getMois();
            resume.annee = facture.getAnnee();
            resume.statut = facture.getStatut().name();
            resume.etape = facture.getEtape();
            resume.patients = clesPatients(facture).size();
            resume.visites = visites(facture);
            resume.dossiersACompleter = facture.getNombreDossiersACompleter();
            resume.montantTotal = facture.getMontantTotal();
            resume.montantSencsu = facture.getMontantTotalSencsu();
            resume.importee = Boolean.TRUE.equals(facture.getImportee());
            resume.derniereSaisie = derniereSaisie(facture);
            return resume;
        }
    }

    /**
     * Compteurs d'un périmètre (structure, district, région). Les patients sont distincts à
     * chaque échelon : un même patient vu deux fois dans le mois, ou sous deux régimes, compte
     * une fois.
     */
    @Data
    public static class Indicateurs {
        private int nombreFactures;
        /** Brouillons et factures rejetées : à saisir, compléter ou corriger. */
        private int facturesEnCours;
        /** Factures transmises (soumises au district → payées). */
        private int facturesTransmises;
        /** Nombre de factures par statut ({@code StatutFacture.name()}). */
        private Map<String, Integer> parStatut = new LinkedHashMap<>();
        private int patients;
        private int visites;
        private int dossiersACompleter;
        private double montantTotal;
        private double montantSencsu;
        private double montantSencsuTransmis;
        private double montantSencsuEnCours;
        /** Dernière modification d'une facture du périmètre (dernière saisie). */
        private LocalDateTime derniereSaisie;

        @JsonIgnore
        private final Set<String> clesPatients = new HashSet<>();

        void ajouter(FactureStructure facture) {
            boolean transmise = facture.getStatut().estComptabilise();
            nombreFactures++;
            if (transmise) {
                facturesTransmises++;
                montantSencsuTransmis += facture.getMontantTotalSencsu();
            } else {
                facturesEnCours++;
                montantSencsuEnCours += facture.getMontantTotalSencsu();
            }
            parStatut.merge(facture.getStatut().name(), 1, Integer::sum);
            clesPatients.addAll(clesPatients(facture));
            patients = clesPatients.size();
            visites += visites(facture);
            dossiersACompleter += facture.getNombreDossiersACompleter();
            montantTotal += facture.getMontantTotal();
            montantSencsu += facture.getMontantTotalSencsu();
            derniereSaisie = plusRecente(derniereSaisie, derniereSaisie(facture));
        }

        void fusionner(Indicateurs autre) {
            nombreFactures += autre.nombreFactures;
            facturesEnCours += autre.facturesEnCours;
            facturesTransmises += autre.facturesTransmises;
            autre.parStatut.forEach((statut, n) -> parStatut.merge(statut, n, Integer::sum));
            clesPatients.addAll(autre.clesPatients);
            patients = clesPatients.size();
            visites += autre.visites;
            dossiersACompleter += autre.dossiersACompleter;
            montantTotal += autre.montantTotal;
            montantSencsu += autre.montantSencsu;
            montantSencsuTransmis += autre.montantSencsuTransmis;
            montantSencsuEnCours += autre.montantSencsuEnCours;
            derniereSaisie = plusRecente(derniereSaisie, autre.derniereSaisie);
        }
    }

    // ────────────────────────────── calculs ──────────────────────────────

    /**
     * Patients d'une facture, comme à l'écran (nombrePatients) : un dossier = une visite, le
     * patient se reconnaît à son matricule, à défaut prénom + nom + date de naissance. Les
     * anciennes saisies sans dossier se comptent par bénéficiaire de ligne.
     */
    static Set<String> clesPatients(FactureStructure facture) {
        Set<String> cles = new HashSet<>();
        List<DossierPatient> dossiers = facture.getDossiers();
        if (dossiers != null && !dossiers.isEmpty()) {
            for (DossierPatient dossier : dossiers) {
                String matricule = normaliser(dossier.getMatricule());
                cles.add(!matricule.isEmpty() ? matricule
                        : "n:" + normaliser(dossier.getPrenom()) + "|" + normaliser(dossier.getNom())
                        + "|" + (dossier.getDateNaissance() == null ? "" : dossier.getDateNaissance()));
            }
            return cles;
        }
        if (facture.getLignes() != null) {
            facture.getLignes().stream()
                    .map(LigneFactureStructure::cleBeneficiaire)
                    .filter(Objects::nonNull)
                    .forEach(cle -> cles.add("l:" + normaliser(cle)));
        }
        return cles;
    }

    /** Visites : un dossier patient chacune ; sans dossier, un bénéficiaire de ligne chacune. */
    static int visites(FactureStructure facture) {
        List<DossierPatient> dossiers = facture.getDossiers();
        return dossiers != null && !dossiers.isEmpty() ? dossiers.size() : facture.getNombreBeneficiaires();
    }

    private static LocalDateTime derniereSaisie(FactureStructure facture) {
        return facture.getUpdatedAt() != null ? facture.getUpdatedAt() : facture.getCreatedAt();
    }

    private static LocalDateTime plusRecente(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private static String normaliser(String texte) {
        return texte == null ? "" : sansAccent(texte.trim()).replaceAll("\\s+", " ");
    }
}
