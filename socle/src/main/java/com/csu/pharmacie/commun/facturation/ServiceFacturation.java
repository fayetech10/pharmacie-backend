package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.commun.carte.ServiceCarteCsu;
import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.ClientPharmacie;
import com.csu.pharmacie.commun.client.ClientReferentiel;
import com.csu.pharmacie.commun.client.dto.SortieStockDto;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.export.Documents;
import com.csu.pharmacie.commun.export.EnteteDocument;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.HistoriqueDossier;
import com.csu.pharmacie.entity.LettreGarantie;
import com.csu.pharmacie.entity.PieceDossier;
import com.csu.pharmacie.entity.PrestationSoins;
import com.csu.pharmacie.entity.RapportRapprochement;
import com.csu.pharmacie.entity.FeuilleSoins;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.TypeStructure;
import com.csu.pharmacie.entity.VerificationCarte;
import com.csu.pharmacie.utils.CleRapprochement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Set;

/**
 * Facturation CSU d'un point de soins — poste comme centre.
 *
 * <p>Une seule implémentation, sur l'entité {@code FactureStructure} du backend : les deux
 * services l'utilisent tels quels et se distinguent par leur API et leur périmètre, pas par
 * leur modèle. Ce qui diffère réellement est le circuit de transmission, et il se déduit du
 * type de la structure émettrice (voir {@link #statutApresEnvoi}).</p>
 *
 * <p>Tout ce qui est calculable est recalculé ici : prix relus au référentiel, montants et
 * parts dérivés du régime, numéro attribué par le serveur. Les valeurs envoyées par le
 * navigateur ne peuvent donc pas influencer le montant facturé.</p>
 */
@RequiredArgsConstructor
public class ServiceFacturation {

    /** Préfixe des numéros émis par les microservices ({@code FS} reste celui du cœur). */
    private static final String PREFIXE_NUMERO = "FS";

    protected final DepotFactureStructure depot;
    protected final ReglesFacturation regles;
    protected final ClientIdentite clientIdentite;
    protected final ClientReferentiel clientReferentiel;
    protected final ClientPharmacie clientPharmacie;
    protected final Numerotation numerotation;
    protected final GenerateurPdf generateurPdf;
    protected final DepotLettreGarantie depotLettreGarantie;
    protected final DepotFeuilleSoins depotFeuilleSoins;
    protected final DepotPieceDossier depotPieces;
    protected final ServiceCarteCsu serviceCarteCsu;

    /** Une photo compressée par le navigateur pèse ~150 Ko ; au-delà, l'envoi est anormal. */
    private static final int TAILLE_MAX_PIECE = 3_000_000;

    /**
     * Une facture établie à partir d'une lettre de garantie tient d'ELLE son régime et
     * l'identité de son bénéficiaire — pas du client.
     *
     * <p>C'est la règle appliquée partout ailleurs : les prix unitaires sont relus dans le
     * référentiel pour qu'un client modifié ne puisse pas facturer un montant arbitraire.
     * Sans cette relecture, il pouvait facturer sous un autre nom, ou sous un régime plus
     * avantageux que celui que la lettre accorde.</p>
     *
     * <p>Deux bugs en découlaient directement : le régime, jamais transmis par l'écran d'un
     * centre de santé, faisait échouer l'enregistrement ; et l'identité du bénéficiaire,
     * jamais transmise non plus, laissait des lignes de facture anonymes.</p>
     *
     * <p>Sans numéro de lettre — un poste de santé qui saisit lui-même son bénéficiaire —
     * la requête reste la source, et le régime demeure obligatoire.</p>
     *
     * @return la lettre appliquée, {@code null} sans numéro de lettre
     */
    private LettreGarantie appliquerLettreGarantie(RequeteFacturation requete) {
        String numero = requete.getLettreGarantieNumero();
        if (numero == null || numero.isBlank()) {
            return null;
        }
        LettreGarantie lettre = depotLettreGarantie.findByNumeroIgnoreCase(numero.trim())
                .orElseThrow(() -> ExceptionMetier.invalide(
                        "Aucune lettre de garantie pour le numéro « " + numero.trim() + " »"));

        requete.setLettreGarantieId(lettre.getId());
        requete.setRegime(lettre.getRegime());
        requete.setPatientId(lettre.getPatientId());
        requete.setPatientNom(lettre.getPatientNom());
        requete.setPatientPrenom(lettre.getPatientPrenom());
        requete.setPatientTelephone(lettre.getPatientTelephone());
        requete.setPatientDateNaissance(lettre.getPatientDateNaissance());
        requete.setPatientSexe(lettre.getPatientSexe());
        // patientMatricule et patientNumeroCni ne sont PAS écrasés : la lettre porte un
        // « numéro d'assuré » dont rien n'établit qu'il s'agit du même identifiant, et le
        // matricule Sésame est calculé par le serveur. Les rapprocher demande une décision
        // métier, pas une supposition.
        return lettre;
    }

    // ══════════════════════════ lecture ══════════════════════════

    /** Factures de la structure de l'appelant, filtrées par période, régime et statut. */
    public List<FactureStructure> mesFactures(Integer mois, Integer annee, Regime regime, StatutFacture statut) {
        return facturesDuPerimetre().stream()
                .filter(f -> mois == null || mois <= 0 || f.getMois() == mois)
                .filter(f -> annee == null || annee <= 0 || f.getAnnee() == annee)
                .filter(f -> regime == null || f.getRegime() == regime)
                .filter(f -> statut == null || f.getStatut() == statut)
                .sorted(comparateurPeriode())
                .toList();
    }

    /**
     * Factures visibles par l'appelant, selon SON périmètre.
     *
     * <p>Une structure voit les siennes. Un superviseur n'en a aucune : il voit celles de son
     * échelon — sa région pour le service régional, son district pour le district, toutes pour
     * le service central et l'administrateur.</p>
     *
     * <p>Sans cette distinction, {@code structureCourante()} refusait la requête en 403
     * (« Votre compte n'est rattaché à aucune structure sanitaire ») et l'écran régional
     * restait vide. {@link #verifierAcces} laissait pourtant déjà passer les superviseurs :
     * l'intention était là, la lecture ne la suivait pas.</p>
     *
     * <p>Les <b>brouillons</b> sont exclus de la vue superviseur — ils appartiennent encore à
     * la structure qui les saisit, exactement comme dans {@link #facturesDesPostes}.</p>
     */
    private List<FactureStructure> facturesDuPerimetre() {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        Role role = utilisateur.getRole();

        if (role == null || !role.estSuperviseur()) {
            return depot.findByStructureSanitaireId(ContexteUtilisateur.structureCourante());
        }

        List<FactureStructure> factures;
        if (role == Role.SERVICE_REGIONAL) {
            // Sans région sur le compte, rien — et surtout pas la vue nationale.
            String region = utilisateur.getRegionId();
            factures = region == null || region.isBlank() ? List.of() : depot.findByRegionId(region);
        } else if (role == Role.DISTRICT && utilisateur.getDistrictId() != null) {
            factures = depot.findByDistrictId(utilisateur.getDistrictId());
        } else {
            // Service central et administrateur : pas de périmètre géographique.
            factures = depot.findAll();
        }
        return factures.stream()
                .filter(f -> f.getStatut() != null && f.getStatut() != StatutFacture.BROUILLON)
                .toList();
    }

    /**
     * Factures que le district contrôle (valide / rejette / pointe) : le régime 0-5 ans des
     * postes de santé (depuis le 08/10/2026, à la place du centre de rattachement) et des
     * centres de santé de son district, siège compris (spécification « Facturation CS / EPS »,
     * §8). Les brouillons sont exclus — ils appartiennent encore à la structure.
     *
     * <p>Le type est celui figé sur la facture, à défaut celui de sa structure émettrice
     * (factures antérieures au type figé). Même périmètre que la consolidation : les factures
     * sans district figé sont rattrapées par leur structure.</p>
     */
    public List<FactureStructure> facturesDesPostesDuDistrict(String districtId, Integer mois, Integer annee,
                                                              Regime regime, StatutFacture statut) {
        // Seul le régime 0-5 ans passe par le district ; les autres vont directement au
        // service régional. Un filtre régime autre que 0-5 ne ramène donc rien côté district.
        if (regime != null && regime != Regime.ZERO_CINQ_ANS) {
            return List.of();
        }
        List<StructureDto> structures = clientIdentite.structuresDuDistrict(districtId);
        Set<String> controlees = structures.stream()
                .filter(s -> s.getTypeStructure() != null && s.getTypeStructure().passeParLeDistrict(Regime.ZERO_CINQ_ANS))
                .map(StructureDto::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return facturesDuDistrict(districtId, structures, mois, annee, Regime.ZERO_CINQ_ANS).stream()
                .filter(f -> f.getTypeStructure() != null
                        ? f.getTypeStructure().passeParLeDistrict(Regime.ZERO_CINQ_ANS)
                        : controlees.contains(f.getStructureSanitaireId()))
                .filter(f -> f.getStatut() != StatutFacture.BROUILLON)
                .filter(f -> statut == null || f.getStatut() == statut)
                .toList();
    }

    /**
     * District d'une facture : celui figé à sa création, à défaut celui de sa structure
     * émettrice (factures du cœur, créées sans district). {@code null} si aucun.
     */
    protected String districtDe(FactureStructure facture) {
        if (facture.getDistrictId() != null && !facture.getDistrictId().isBlank()) {
            return facture.getDistrictId();
        }
        if (facture.getStructureSanitaireId() == null) {
            return null;
        }
        StructureDto structure = clientIdentite.structure(facture.getStructureSanitaireId());
        String district = structure == null ? null : structure.getDistrictId();
        return district == null || district.isBlank() ? null : district;
    }

    /**
     * Région d'une facture : celle figée à sa création, à défaut celle de sa structure
     * émettrice (factures du cœur, créées sans région). {@code null} si aucune.
     */
    protected String regionDe(FactureStructure facture) {
        if (facture.getRegionId() != null && !facture.getRegionId().isBlank()) {
            return facture.getRegionId();
        }
        if (facture.getStructureSanitaireId() == null) {
            return null;
        }
        StructureDto structure = clientIdentite.structure(facture.getStructureSanitaireId());
        String region = structure == null ? null : structure.getRegionId();
        return region == null || region.isBlank() ? null : region;
    }

    /**
     * Un service régional n'accède qu'aux factures de SA région (celle du jeton) : connaître
     * l'identifiant d'une facture suffisait à la lire — PDF et pièces compris — depuis une
     * autre région. Sans région sur le compte, aucune facture. Les autres rôles ne sont pas
     * concernés ici.
     */
    private void verifierRegion(FactureStructure facture, UtilisateurAuthentifie utilisateur) {
        if (utilisateur.getRole() != Role.SERVICE_REGIONAL) {
            return;
        }
        String region = utilisateur.getRegionId();
        if (region == null || region.isBlank()) {
            throw ExceptionMetier.interdit("Votre compte n'est rattaché à aucune région");
        }
        if (!region.equals(regionDe(facture))) {
            throw ExceptionMetier.interdit("Cette facture n'appartient pas à votre région");
        }
    }

    /**
     * Factures d'un district — postes et centres confondus (§9), <b>brouillons et factures
     * rejetées compris</b>.
     *
     * <p>La facture se remplit au fil du mois et ne part qu'en fin de période : ne compter que
     * les transmises laissait la consolidation à zéro tout le mois en cours, alors que les
     * postes facturaient. Chaque facture porte son statut ; c'est à l'affichage de distinguer
     * le transmis ({@link StatutFacture#estComptabilise()}) de l'en-cours (arbitrage du
     * 03/10/2026).</p>
     */
    public List<FactureStructure> facturesDuDistrict(String districtId, Integer mois, Integer annee,
                                                     Regime regime) {
        return facturesDuDistrict(districtId, clientIdentite.structuresDuDistrict(districtId), mois, annee, regime);
    }

    /**
     * Factures comptabilisées d'un district dont on connaît déjà les structures.
     *
     * <p>Le district est figé sur la facture à sa création — mais seuls les microservices le
     * font : le cœur ne le renseignait pas, et une structure rattachée à son district après
     * coup laissait ses factures sans district. Lues par le seul {@code districtId}, ces
     * factures n'entraient dans aucune consolidation, et le district voyait « non facturés »
     * des postes qui avaient bel et bien transmis. Elles sont donc rattrapées par leur
     * structure émettrice — seulement quand aucun district n'est figé, pour ne pas déplacer
     * l'historique d'une structure qui a changé de district.</p>
     */
    public List<FactureStructure> facturesDuDistrict(String districtId, List<StructureDto> structures,
                                                     Integer mois, Integer annee, Regime regime) {
        Map<String, FactureStructure> factures = new LinkedHashMap<>();
        depot.findByDistrictId(districtId).forEach(f -> factures.put(f.getId(), f));
        List<String> structureIds = structures == null ? List.of() : structures.stream()
                .map(StructureDto::getId)
                .filter(Objects::nonNull)
                .toList();
        if (!structureIds.isEmpty()) {
            depot.findByStructureSanitaireIdIn(structureIds).stream()
                    .filter(f -> f.getDistrictId() == null || f.getDistrictId().isBlank())
                    .forEach(f -> factures.putIfAbsent(f.getId(), f));
        }
        return factures.values().stream()
                .filter(f -> f.getStatut() != null)
                .filter(f -> mois == null || mois <= 0 || f.getMois() == mois)
                .filter(f -> annee == null || annee <= 0 || f.getAnnee() == annee)
                .filter(f -> regime == null || f.getRegime() == regime)
                .sorted(comparateurPeriode())
                .toList();
    }

    /** Identifiants + statuts uniquement — charge utile minimale pour les pastilles du menu. */
    public List<Map<String, Object>> statutsLegers() {
        return mesFactures(null, null, null, null).stream()
                .map(f -> Map.<String, Object>of(
                        "id", f.getId(),
                        "numero", f.getNumero() == null ? "" : f.getNumero(),
                        "statut", f.getStatut() == null ? "" : f.getStatut().name()))
                .toList();
    }

    /**
     * Facture par identifiant, avec contrôle d'accès.
     *
     * <p>Une structure ne voit que ses propres factures ; un centre voit en plus celles des
     * postes qu'il polarise ; les échelons de supervision voient leur périmètre. Ce contrôle
     * est ici et pas seulement dans la configuration de sécurité : les règles de chemin ne
     * savent pas à qui appartient la facture demandée.</p>
     */
    public FactureStructure parId(String id) {
        FactureStructure facture = depot.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Facture introuvable : " + id));
        verifierLecture(facture);
        // En mémoire seulement (lecture) : les identifiants sont déterministes, l'écran
        // peut donc agir sur un dossier reconstitué comme sur un dossier enregistré.
        normaliserDossiers(facture);
        return facture;
    }

    protected void verifierLecture(FactureStructure facture) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        // Le district — compte district ou centre siège du district — lit les factures de SON
        // district (postes et centres), pas les autres.
        if (utilisateur.aCapaciteDistrict() && utilisateur.getDistrictId() != null
                && utilisateur.getDistrictId().equals(districtDe(facture))) {
            return;
        }
        if (utilisateur.getRole() == Role.DISTRICT) {
            throw ExceptionMetier.interdit("Cette facture n'appartient pas à votre district");
        }
        if (utilisateur.getRole() == Role.SERVICE_REGIONAL) {
            verifierRegion(facture, utilisateur);
            return;
        }
        // Niveau central et administrateur : accès national.
        if (utilisateur.getRole() != null && utilisateur.getRole().estSuperviseur()) {
            return;
        }
        String structureId = utilisateur.getStructureId();
        if (structureId == null) {
            throw ExceptionMetier.interdit("Votre compte n'est rattaché à aucune structure sanitaire");
        }
        // Une structure ne lit que ses propres factures : le centre de rattachement ne voit plus
        // celles des postes qu'il polarise — leur contrôle est passé au district (08/10/2026).
        if (structureId.equals(facture.getStructureSanitaireId())) {
            return;
        }
        throw ExceptionMetier.interdit("Cette facture n'appartient pas à votre structure");
    }

    // ══════════════════════════ facturation incrémentielle ══════════════════════════

    /**
     * Ajoute un bénéficiaire et ses prestations à la facture du mois pour son régime,
     * en la créant si elle n'existe pas encore.
     *
     * <p>C'est ainsi que l'écran de facturation travaille : on saisit un patient à la fois
     * et la facture mensuelle s'accumule, au rythme du terrain.</p>
     *
     * <p>Une saisie envoyée plusieurs fois au même instant n'est comptée qu'une fois : ses
     * envois passent un à un (cf. {@link DepotFactureStructure#verrouiller}), et les suivants
     * trouvent sa clé au lieu de créer un second dossier.</p>
     */
    @Transactional
    public FactureStructure ajouterPatient(RequeteFacturation requete) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        String structureId = ContexteUtilisateur.structureCourante();

        // Saisie hors ligne renvoyée : si elle est déjà arrivée (réponse perdue en route, ou
        // envoi en double), on renvoie la facture qui l'a reçue, sans rien y ajouter — avant
        // tout contrôle, et quel que soit le mois (elle a pu être reportée entre-temps).
        String cle = requete.getCleSynchronisation() == null || requete.getCleSynchronisation().isBlank()
                ? null : requete.getCleSynchronisation().trim();
        if (cle != null) {
            // Avant toute lecture des factures : un envoi simultané de la même saisie attend ici
            // que le premier soit enregistré, puis le trouve. Seuls les envois d'une même saisie
            // s'attendent — pas les autres patients de la structure.
            depot.verrouiller("saisie:" + structureId + ":" + cle);
            Optional<FactureStructure> dejaRecue = factureAyantRecu(structureId, cle);
            if (dejaRecue.isPresent()) {
                return dejaRecue.get();
            }
        }

        LettreGarantie lettre = appliquerLettreGarantie(requete);

        Regime regime = requete.getRegime();
        if (regime == null) {
            throw ExceptionMetier.invalide("Le régime du bénéficiaire est obligatoire");
        }

        // Centres et EPS : la saisie du BCSU a déjà placé ce patient dans la facture de son
        // mois. Les prestations complètent ce dossier — un second ferait de la même visite deux
        // lignes de facture.
        Optional<FactureStructure> factureDeLaLettre = factureDuDossierBcsu(lettre, structureId);
        if (factureDeLaLettre.isPresent()) {
            return completerDossierBcsu(factureDeLaLettre.get(), lettre, requete, utilisateur, cle);
        }

        // Le dossier d'abord : le contrôle d'éligibilité peut en réécrire l'identité, et
        // c'est cette identité-là que les lignes doivent porter.
        DossierPatient dossier = requete.versDossier();
        boolean surLettre = requete.getLettreGarantieId() != null;
        if (surLettre) {
            // Saisie sur lettre de garantie : l'identité vient de la lettre et fait foi.
            dossier.setLettreGarantieNumero(requete.getLettreGarantieNumero());
            dossier.setIdentiteVerrouillee(true);
        }
        String photoCarte = controlerEligibilite(regime, dossier);
        exigerIdentite(dossier);
        requete.appliquerIdentite(dossier);

        List<LigneFactureStructure> nouvelles = requete.lignesCompletees();
        if (nouvelles.isEmpty() && !requete.estPartiel()) {
            throw ExceptionMetier.invalide("Ajoutez au moins une prestation pour ce bénéficiaire, "
                    + "ou enregistrez-le comme « à compléter ».");
        }
        if (nouvelles.isEmpty()) {
            // Sans ligne, ReglesFacturation ne verrait jamais ce patient : le contrôle d'âge
            // du régime est fait ici, sur son identité.
            regles.controlerAge(regime, LigneFactureStructure.builder()
                    .patientNom(dossier.getNom())
                    .patientPrenom(dossier.getPrenom())
                    .patientDateNaissance(dossier.getDateNaissance())
                    .datePriseEnCharge(LocalDate.now())
                    .build());
        }

        LocalDate aujourdhui = LocalDate.now();
        int mois = requete.getMois() != null && requete.getMois() > 0 ? requete.getMois() : aujourdhui.getMonthValue();
        int annee = requete.getAnnee() != null && requete.getAnnee() > 0 ? requete.getAnnee() : aujourdhui.getYear();
        if (mois < 1 || mois > 12) {
            throw ExceptionMetier.invalide("Mois invalide : " + mois);
        }

        FactureStructure facture = factureDuMois(structureId, requete.getRegime(), mois, annee, utilisateur);
        if (!facture.getStatut().estModifiable()) {
            throw ExceptionMetier.conflit("La facture « " + facture.getRegime().getLibelle() + " » de "
                    + FactureStructure.MOIS[mois] + " " + annee + " est « " + facture.getStatut().getLibelle()
                    + " » : elle ne peut plus recevoir de nouvelle saisie.");
        }

        if (facture.getLignes() == null) {
            facture.setLignes(new ArrayList<>());
        }
        normaliserDossiers(facture);

        // Chaque visite est un dossier : un patient revenu plusieurs fois dans le mois a autant
        // de dossiers que de visites, chacun sur sa ligne de la facture, et le forfait (un par
        // dossier) vaut une fois par visite. Choix du 06/10/2026 — auparavant les visites du
        // mois étaient réunies dans un seul dossier, donc un seul forfait par mois.
        dossier.setId(UUID.randomUUID().toString());
        // Date de la visite gardée par le dossier : un dossier enregistré sans prestation la
        // retrouve quand on le complète, même le lendemain.
        dossier.setDatePriseEnCharge(requete.getDatePriseEnCharge() != null ? requete.getDatePriseEnCharge()
                : nouvelles.isEmpty() ? aujourdhui : nouvelles.get(0).getDatePriseEnCharge());
        dossier.setPieces(typesFournis(requete.getPieces()));
        if (photoCarte != null && !photoCarte.isBlank()) {
            // Déclarée avant l'enregistrement de la facture, qui porte la liste des pièces ;
            // l'image elle-même suit, une fois la facture identifiée.
            marquerPhotoCarte(dossier);
        }
        dossier.setStatut(statutDossier(regime, dossier, nouvelles.size(), requete.estPartiel()));
        dossier.setCreatedAt(LocalDateTime.now());
        dossier.setUpdatedAt(dossier.getCreatedAt());
        dossier.setCreatedBy(utilisateur.getId());
        facture.getDossiers().add(dossier);
        if (cle != null) {
            // Persisté avec le reste : finaliser() marque la facture modifiée (updatedAt).
            if (dossier.getClesSynchronisation() == null) {
                dossier.setClesSynchronisation(new ArrayList<>());
            }
            dossier.getClesSynchronisation().add(cle);
        }

        final String dossierId = dossier.getId();
        nouvelles.forEach(ligne -> ligne.setDossierId(dossierId));
        facture.getLignes().addAll(nouvelles);

        if (requete.getTicketCaisse() != null && !requete.getTicketCaisse().isBlank()) {
            facture.setTicketCaisse(requete.getTicketCaisse());
        }
        if (requete.getDocumentsComplementaires() != null && !requete.getDocumentsComplementaires().isEmpty()) {
            if (facture.getDocumentsComplementaires() == null) {
                facture.setDocumentsComplementaires(new ArrayList<>());
            }
            facture.getDocumentsComplementaires().addAll(requete.getDocumentsComplementaires());
        }
        facture.tracer(trace(facture, utilisateur, dossier, "Patient", null,
                dossier.nomComplet() + " — " + nouvelles.size() + " prestation(s)",
                dossier.estACompleter() ? "Patient enregistré (à compléter)" : "Patient ajouté à la facture"));

        FactureStructure enregistree = finaliser(facture);
        // Après l'enregistrement : la pièce référence la facture, qui n'avait pas encore
        // d'identifiant si elle vient d'être créée pour ce patient.
        enregistrerPieces(enregistree.getId(), dossier.getId(), requete.getPieces(), utilisateur);
        enregistrerPhotoCarte(enregistree.getId(), dossier, photoCarte, utilisateur);
        return enregistree;
    }

    /**
     * Facture de la structure où la saisie du BCSU a placé le patient de cette lettre (centres
     * et EPS, cf. {@code FactureMensuelleBcsuService} du cœur) — vide pour une lettre sans
     * dossier, ou dont le dossier est dans la facture d'une autre structure.
     */
    private Optional<FactureStructure> factureDuDossierBcsu(LettreGarantie lettre, String structureId) {
        if (lettre == null || lettre.getFactureStructureId() == null || lettre.getDossierId() == null) {
            return Optional.empty();
        }
        return depot.findById(lettre.getFactureStructureId())
                .filter(f -> structureId.equals(f.getStructureSanitaireId()))
                .filter(f -> f.getDossiers() != null
                        && f.getDossiers().stream().anyMatch(d -> lettre.getDossierId().equals(d.getId())));
    }

    /**
     * Ajoute les prestations saisies au dossier que le BCSU a créé pour cette lettre. L'identité
     * reste celle du dossier (la lettre fait foi) ; le n° de registre et l'adresse saisis par la
     * structure le complètent.
     */
    private FactureStructure completerDossierBcsu(FactureStructure facture, LettreGarantie lettre,
                                                  RequeteFacturation requete, UtilisateurAuthentifie utilisateur,
                                                  String cle) {
        if (!facture.getStatut().estModifiable()) {
            throw ExceptionMetier.conflit("La facture « " + facture.getRegime().getLibelle() + " » de "
                    + facture.periodeLibelle() + " est « " + facture.getStatut().getLibelle()
                    + " » : elle ne peut plus recevoir de prestations.");
        }
        if (facture.getLignes() == null) {
            facture.setLignes(new ArrayList<>());
        }
        normaliserDossiers(facture);
        DossierPatient dossier = dossierDe(facture, lettre.getDossierId());

        DossierPatient saisi = requete.versDossier();
        if (saisi.getNumeroRegistre() != null) {
            dossier.setNumeroRegistre(saisi.getNumeroRegistre());
        }
        if (saisi.getAdresse() != null) {
            dossier.setAdresse(saisi.getAdresse());
        }
        requete.appliquerIdentite(dossier);

        List<LigneFactureStructure> nouvelles = requete.lignesCompletees();
        if (nouvelles.isEmpty() && !requete.estPartiel()) {
            throw ExceptionMetier.invalide("Ajoutez au moins une prestation pour ce bénéficiaire.");
        }
        nouvelles.forEach(ligne -> ligne.setDossierId(dossier.getId()));
        facture.getLignes().addAll(nouvelles);

        List<HistoriqueDossier> traces = new ArrayList<>();
        appliquerPieces(facture, dossier, requete.getPieces(), utilisateur, traces);
        if (cle != null) {
            if (dossier.getClesSynchronisation() == null) {
                dossier.setClesSynchronisation(new ArrayList<>());
            }
            dossier.getClesSynchronisation().add(cle);
        }
        String statutAvant = dossier.getStatut();
        dossier.setStatut(statutDossier(facture.getRegime(), dossier, lignesDu(facture, dossier.getId()).size(),
                requete.estPartiel()));
        dossier.setUpdatedAt(LocalDateTime.now());
        traces.add(trace(facture, utilisateur, dossier, "Prestations", null, nouvelles.size() + " prestation(s)",
                "Prestations ajoutées au patient enregistré par le BCSU (lettre " + lettre.getNumero() + ")"));
        if (!Objects.equals(statutAvant, dossier.getStatut())) {
            traces.add(trace(facture, utilisateur, dossier, "Statut du dossier", libelleStatutDossier(statutAvant),
                    libelleStatutDossier(dossier.getStatut()), null));
        }

        if (requete.getTicketCaisse() != null && !requete.getTicketCaisse().isBlank()) {
            facture.setTicketCaisse(requete.getTicketCaisse());
        }
        if (requete.getDocumentsComplementaires() != null && !requete.getDocumentsComplementaires().isEmpty()) {
            if (facture.getDocumentsComplementaires() == null) {
                facture.setDocumentsComplementaires(new ArrayList<>());
            }
            facture.getDocumentsComplementaires().addAll(requete.getDocumentsComplementaires());
        }
        traces.forEach(facture::tracer);
        return finaliser(facture);
    }

    // ══════════════════════════ import de fin de mois (centres et EPS) ══════════════════════════

    /**
     * Applique l'import de fin de mois d'un centre ou d'un EPS (spécification « Facturation
     * CS / EPS », §4-5) : les prestations du fichier, déjà rapprochées des patients du BCSU,
     * remplacent celles qu'un import précédent avait apportées à ces mêmes dossiers.
     *
     * <p>Les prestations saisies à l'écran et les dossiers absents du fichier ne bougent pas :
     * réimporter le même fichier ne change rien, réimporter un fichier corrigé corrige. Les
     * montants passent par les mêmes règles que toute saisie (parts du régime, forfait), et les
     * feuilles de soins sont mises à jour.</p>
     *
     * @param lignesParDossier prestations du fichier par dossier ; seules la visite (date) et
     *                         la prestation y sont renseignées, l'identité vient du dossier
     */
    @Transactional
    public FactureStructure appliquerImport(String factureId, Map<String, List<LigneFactureStructure>> lignesParDossier,
                                            RapportRapprochement rapport) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        FactureStructure facture = factureModifiable(factureId);
        Map<String, String> patients = depotLettreGarantie.findAllById(facture.getDossiers().stream()
                        .filter(d -> lignesParDossier.containsKey(d.getId()))
                        .map(DossierPatient::getLettreGarantieId)
                        .filter(Objects::nonNull)
                        .toList()).stream()
                .filter(l -> l.getPatientId() != null)
                .collect(Collectors.toMap(LettreGarantie::getId, LettreGarantie::getPatientId, (a, b) -> a));

        int prestations = 0;
        for (Map.Entry<String, List<LigneFactureStructure>> entree : lignesParDossier.entrySet()) {
            DossierPatient dossier = dossierDe(facture, entree.getKey());
            List<LigneFactureStructure> nouvelles = entree.getValue();
            for (LigneFactureStructure ligne : nouvelles) {
                identifier(ligne, dossier, patients.get(dossier.getLettreGarantieId()));
            }
            facture.getLignes().removeIf(l -> dossier.getId().equals(l.getDossierId())
                    && LigneFactureStructure.ORIGINE_IMPORT.equals(l.getOrigine()));
            // À la suite des prestations saisies à l'écran pour ce patient, s'il en a.
            int position = -1;
            for (int i = 0; i < facture.getLignes().size(); i++) {
                if (dossier.getId().equals(facture.getLignes().get(i).getDossierId())) {
                    position = i + 1;
                }
            }
            if (position < 0) {
                facture.getLignes().addAll(nouvelles);
            } else {
                facture.getLignes().addAll(position, nouvelles);
            }

            nouvelles.stream().map(LigneFactureStructure::getNumeroRegistre).filter(Objects::nonNull)
                    .filter(n -> !n.isBlank()).findFirst().ifPresent(dossier::setNumeroRegistre);
            dossier.setStatut(statutDossier(facture.getRegime(), dossier, lignesDu(facture, dossier.getId()).size(), false));
            dossier.setUpdatedAt(LocalDateTime.now());
            prestations += nouvelles.size();
        }

        String resume = "Import du fichier « " + (rapport.getFichier() == null ? "?" : rapport.getFichier()) + " » : "
                + lignesParDossier.size() + " patient(s) rapproché(s), " + prestations + " prestation(s)";
        facture.tracer(trace(facture, utilisateur, null, "Import de fin de mois", null, resume, resume));
        rapport.setFactureId(facture.getId());
        rapport.setFactureNumero(facture.getNumero());
        facture.setDernierRapprochement(rapport);
        return finaliser(facture);
    }

    // ══════════════════════════ tickets de caisse (centres et EPS) ══════════════════════════

    /** Une visite du BCSU, telle que la saisie des tickets en série la propose. */
    public record VisiteTicket(String factureId, String factureNumero, String dossierId, String patient,
                               LocalDate date, String lettreGarantieNumero, int prestations, long tickets,
                               String statut) {
    }

    /**
     * Visites trouvées pour un identifiant et une date. {@code autresDates} : l'identifiant est
     * connu ce mois-là, mais à d'autres dates — souvent une erreur de date.
     */
    public record RechercheTicket(List<VisiteTicket> visites, List<LocalDate> autresDates) {
    }

    /** Un ticket d'un dossier, sans sa photo. */
    public record TicketResume(String id, LocalDateTime createdAt) {
    }

    /**
     * Patients du BCSU auxquels rattacher un ticket : ceux de la facture du mois de la date,
     * avec cet identifiant et cette date de prise en charge (spécification « Facturation CS /
     * EPS », §5). Plusieurs pour deux enfants d'un même accompagnant : l'écran fait choisir.
     */
    public RechercheTicket rechercherVisites(Regime regime, String identifiant, LocalDate date) {
        if (regime == null || date == null) {
            throw ExceptionMetier.invalide("Le régime et la date de prise en charge sont obligatoires.");
        }
        String cle = CleRapprochement.normaliser(identifiant);
        if (cle == null) {
            throw ExceptionMetier.invalide("Saisissez le " + CleRapprochement.libelleIdentifiant(regime) + ".");
        }
        Optional<FactureStructure> facture = depot.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(
                ContexteUtilisateur.structureCourante(), regime, date.getMonthValue(), date.getYear());
        if (facture.isEmpty()) {
            return new RechercheTicket(List.of(), List.of());
        }
        FactureStructure f = facture.get();
        List<DossierPatient> memeCle = (f.getDossiers() == null ? List.<DossierPatient>of() : f.getDossiers()).stream()
                .filter(DossierPatient::exigeTicketDeCaisse)
                .filter(d -> cle.equals(CleRapprochement.cleDe(regime, d)))
                .toList();
        List<VisiteTicket> visites = memeCle.stream()
                .filter(d -> date.equals(d.getDatePriseEnCharge()))
                .map(d -> visiteTicket(f, d))
                .toList();
        List<LocalDate> autresDates = visites.isEmpty() ? memeCle.stream()
                .map(DossierPatient::getDatePriseEnCharge)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList() : List.of();
        return new RechercheTicket(visites, autresDates);
    }

    /**
     * Ajoute un ticket de caisse au dossier — un de plus, jamais à la place d'un autre : une
     * visite peut en compter plusieurs. Le dossier est recalculé : le ticket peut le compléter.
     */
    @Transactional
    public VisiteTicket ajouterTicket(String factureId, String dossierId, String image) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        if (image == null || image.isBlank()) {
            throw ExceptionMetier.invalide("Photographiez le ticket de caisse.");
        }
        controlerPiece(PieceDossier.TICKET_CAISSE, image);
        FactureStructure facture = factureModifiable(factureId);
        DossierPatient dossier = dossierDe(facture, dossierId);

        depotPieces.save(PieceDossier.builder()
                .factureId(facture.getId())
                .dossierId(dossierId)
                .type(PieceDossier.TICKET_CAISSE)
                .image(image)
                .createdAt(LocalDateTime.now())
                .createdBy(utilisateur.getId())
                .build());
        long tickets = depotPieces.countByDossierIdAndType(dossierId, PieceDossier.TICKET_CAISSE);
        marquerTicket(dossier, true);
        recalculerDossier(facture, dossier);
        facture.tracer(trace(facture, utilisateur, dossier, "Pièce justificative", null,
                PieceDossier.libelle(PieceDossier.TICKET_CAISSE),
                "Ticket de caisse n°" + tickets + " ajouté — " + dossier.nomComplet()));
        facture.setUpdatedAt(LocalDateTime.now());
        FactureStructure enregistree = depot.save(facture);
        return visiteTicket(enregistree, dossierDe(enregistree, dossierId));
    }

    /** Tickets d'un dossier, sans leur photo, dans l'ordre où ils ont été ajoutés. */
    public List<TicketResume> tickets(String factureId, String dossierId) {
        FactureStructure facture = parId(factureId);
        dossierDe(facture, dossierId);
        return depotPieces.findByDossierIdAndTypeOrderByCreatedAtAsc(dossierId, PieceDossier.TICKET_CAISSE).stream()
                .map(p -> new TicketResume(p.getId(), p.getCreatedAt()))
                .toList();
    }

    /** Photo d'un ticket, lue à la demande. */
    public Map<String, String> ticket(String factureId, String dossierId, String ticketId) {
        FactureStructure facture = parId(factureId);
        dossierDe(facture, dossierId);
        PieceDossier piece = depotPieces.findByIdAndDossierId(ticketId, dossierId)
                .filter(p -> PieceDossier.TICKET_CAISSE.equals(p.getType()))
                .orElseThrow(() -> ExceptionMetier.introuvable("Ticket de caisse introuvable dans ce dossier"));
        return Map.of("type", piece.getType(), "libelle", PieceDossier.libelle(piece.getType()), "image", piece.getImage());
    }

    /** Retire un ticket (photo ratée, mauvais patient) ; le dernier retiré, le dossier redevient « à compléter ». */
    @Transactional
    public VisiteTicket retirerTicket(String factureId, String dossierId, String ticketId) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        FactureStructure facture = factureModifiable(factureId);
        DossierPatient dossier = dossierDe(facture, dossierId);
        PieceDossier piece = depotPieces.findByIdAndDossierId(ticketId, dossierId)
                .filter(p -> PieceDossier.TICKET_CAISSE.equals(p.getType()))
                .orElseThrow(() -> ExceptionMetier.introuvable("Ticket de caisse introuvable dans ce dossier"));
        depotPieces.delete(piece);
        long restants = depotPieces.countByDossierIdAndType(dossierId, PieceDossier.TICKET_CAISSE);
        marquerTicket(dossier, restants > 0);
        recalculerDossier(facture, dossier);
        facture.tracer(trace(facture, utilisateur, dossier, "Pièce justificative",
                PieceDossier.libelle(PieceDossier.TICKET_CAISSE), null,
                "Ticket de caisse retiré — " + dossier.nomComplet() + " (" + restants + " restant(s))"));
        facture.setUpdatedAt(LocalDateTime.now());
        FactureStructure enregistree = depot.save(facture);
        return visiteTicket(enregistree, dossierDe(enregistree, dossierId));
    }

    /** Le dossier ne garde que le type de pièce présent, une fois, quel que soit le nombre de tickets. */
    private static void marquerTicket(DossierPatient dossier, boolean present) {
        if (dossier.getPieces() == null) {
            dossier.setPieces(new ArrayList<>());
        }
        dossier.getPieces().removeIf(PieceDossier.TICKET_CAISSE::equals);
        if (present) {
            dossier.getPieces().add(PieceDossier.TICKET_CAISSE);
        }
    }

    private void recalculerDossier(FactureStructure facture, DossierPatient dossier) {
        dossier.setStatut(statutDossier(facture.getRegime(), dossier, lignesDu(facture, dossier.getId()).size(), false));
        dossier.setUpdatedAt(LocalDateTime.now());
    }

    private VisiteTicket visiteTicket(FactureStructure facture, DossierPatient dossier) {
        return new VisiteTicket(facture.getId(), facture.getNumero(), dossier.getId(), dossier.nomComplet(),
                dossier.getDatePriseEnCharge(), dossier.getLettreGarantieNumero(),
                lignesDu(facture, dossier.getId()).size(),
                depotPieces.countByDossierIdAndType(dossier.getId(), PieceDossier.TICKET_CAISSE),
                dossier.getStatut());
    }

    /** Identité du dossier et rattachement à sa lettre : ceux d'une saisie sur lettre de garantie. */
    private static void identifier(LigneFactureStructure ligne, DossierPatient dossier, String patientId) {
        ligne.setDossierId(dossier.getId());
        ligne.setOrigine(LigneFactureStructure.ORIGINE_IMPORT);
        ligne.setPatientId(patientId);
        ligne.setPatientNom(dossier.getNom());
        ligne.setPatientPrenom(dossier.getPrenom());
        ligne.setPatientSexe(dossier.getSexe());
        ligne.setPatientDateNaissance(dossier.getDateNaissance());
        ligne.setPatientTelephone(dossier.getTelephone());
        ligne.setPatientAdresse(dossier.getAdresse());
        ligne.setPatientMatricule(dossier.getMatricule());
        ligne.setPatientNumeroCni(dossier.getNumeroCni());
        ligne.setVerificationCarte(dossier.getVerificationCarte());
        ligne.setLettreGarantieId(dossier.getLettreGarantieId());
        ligne.setLettreGarantieNumero(dossier.getLettreGarantieNumero());
    }

    // ══════════════════════════ dossiers patients ══════════════════════════

    /**
     * Modifie le dossier d'un patient : identité (sauf si elle a été validée par le
     * contrôle d'éligibilité), pièces justificatives et prestations, remplacées en bloc.
     *
     * <p>C'est le « Mes factures → Modifier → patient » du cahier des charges : compléter un
     * dossier enregistré partiellement, ou corriger ses prestations avant l'envoi. Chaque
     * différence est historisée (qui, quoi, quand, ancienne et nouvelle valeur).</p>
     */
    @Transactional
    public FactureStructure modifierDossier(String factureId, String dossierId, RequeteFacturation requete) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        FactureStructure facture = factureModifiable(factureId);
        DossierPatient dossier = dossierDe(facture, dossierId);
        Regime regime = facture.getRegime();
        List<HistoriqueDossier> traces = new ArrayList<>();
        DossierPatient saisi = requete.versDossier();

        // ----- Identité -----
        // Seuls le nom et l'identifiant qui a servi au contrôle d'éligibilité sont figés
        // (cahier « Espace PS », §5.2 et §6.3) : « tout est modifiable sauf le nom ».
        if (dossier.isIdentiteVerrouillee()) {
            exigerIdentiteInchangee(dossier, saisi);
        } else {
            changer(traces, facture, utilisateur, dossier, "Nom", dossier.getNom(), saisi.getNom(), dossier::setNom);
            changer(traces, facture, utilisateur, dossier, "Prénom", dossier.getPrenom(), saisi.getPrenom(), dossier::setPrenom);
            changer(traces, facture, utilisateur, dossier, "Matricule", dossier.getMatricule(), saisi.getMatricule(),
                    dossier::setMatricule);
            // Carte vérifiée après coup (site de vérification revenu) : l'identité passe
            // sous le contrôle du système source et se verrouille.
            if (regime.exigeCarteCsu() && dossier.getLettreGarantieNumero() == null
                    && VerificationCarte.VERIFIEE.equals(saisi.getVerificationCarte())
                    && !VerificationCarte.VERIFIEE.equals(dossier.getVerificationCarte())) {
                dossier.setVerificationCarte(VerificationCarte.VERIFIEE);
                String photoCarte = controlerEligibilite(regime, dossier);
                traces.add(trace(facture, utilisateur, dossier, "Éligibilité", "Non vérifiée",
                        "Carte vérifiée", "Contrôle d'éligibilité effectué"));
                if (photoCarte != null && !photoCarte.isBlank()) {
                    enregistrerPhotoCarte(facture.getId(), dossier, photoCarte, utilisateur);
                    traces.add(trace(facture, utilisateur, dossier, "Pièce justificative", null,
                            PieceDossier.libelle(PieceDossier.PHOTO_ASSURE),
                            "Photo de l'assuré récupérée de la carte CSU — " + dossier.nomComplet()));
                }
            }
        }
        changer(traces, facture, utilisateur, dossier, "Sexe", dossier.getSexe(), saisi.getSexe(), dossier::setSexe);
        changer(traces, facture, utilisateur, dossier, "Date de naissance", dossier.getDateNaissance(),
                saisi.getDateNaissance(), dossier::setDateNaissance);
        changer(traces, facture, utilisateur, dossier, "N° de pièce", dossier.getNumeroCni(), saisi.getNumeroCni(),
                dossier::setNumeroCni);
        // Coordonnées et informations médicales : toujours modifiables.
        changer(traces, facture, utilisateur, dossier, "Téléphone", dossier.getTelephone(), saisi.getTelephone(),
                dossier::setTelephone);
        changer(traces, facture, utilisateur, dossier, "Adresse", dossier.getAdresse(), saisi.getAdresse(),
                dossier::setAdresse);
        changer(traces, facture, utilisateur, dossier, "N° registre", dossier.getNumeroRegistre(),
                saisi.getNumeroRegistre(), dossier::setNumeroRegistre);
        exigerIdentite(dossier);

        // ----- Pièces -----
        appliquerPieces(facture, dossier, requete.getPieces(), utilisateur, traces);

        // ----- Prestations : celles du dossier sont remplacées, à leur place dans la facture -----
        List<LigneFactureStructure> anciennes = lignesDu(facture, dossierId);
        requete.appliquerIdentite(dossier);
        // Le rattachement au circuit BCSU (lettre, feuille de soins) n'est pas saisi à
        // l'écran : sans cette reprise, modifier un patient d'un centre le détachait de sa
        // lettre de garantie, et sa feuille de soins n'était plus mise à jour.
        anciennes.stream().filter(l -> l.getLettreGarantieId() != null).findFirst().ifPresent(l -> {
            if (requete.getLettreGarantieId() == null) {
                requete.setLettreGarantieId(l.getLettreGarantieId());
                requete.setLettreGarantieNumero(l.getLettreGarantieNumero());
            }
            if (requete.getFeuilleSoinsId() == null) {
                requete.setFeuilleSoinsId(l.getFeuilleSoinsId());
                requete.setFeuilleSoinsNumero(l.getFeuilleSoinsNumero());
            }
        });
        // La date de prise en charge ne change plus une fois la visite saisie (demande du
        // 19/10/2026) : les prestations ajoutées en complétant le dossier, même le lendemain,
        // portent la sienne. Les dossiers du BCSU (lettre de garantie) gardent leur circuit.
        LocalDate dateVisite = dossier.getLettreGarantieId() == null ? dateDeLaVisite(dossier, anciennes) : null;
        if (dateVisite != null) {
            dossier.setDatePriseEnCharge(dateVisite);
            if (requete.getLignes() != null) {
                requete.getLignes().forEach(ligne -> ligne.setDatePriseEnCharge(dateVisite));
            }
        }
        List<LigneFactureStructure> nouvelles = requete.lignesCompletees();
        nouvelles.forEach(ligne -> ligne.setDossierId(dossierId));
        tracerLignes(traces, facture, utilisateur, dossier, anciennes, nouvelles);

        int position = facture.getLignes().indexOf(anciennes.isEmpty() ? null : anciennes.get(0));
        facture.getLignes().removeIf(ligne -> dossierId.equals(ligne.getDossierId()));
        if (position < 0 || position > facture.getLignes().size()) {
            facture.getLignes().addAll(nouvelles);
        } else {
            facture.getLignes().addAll(position, nouvelles);
        }

        String statutAvant = dossier.getStatut();
        dossier.setStatut(statutDossier(regime, dossier, nouvelles.size(), requete.estPartiel()));
        if (!Objects.equals(statutAvant, dossier.getStatut())) {
            traces.add(trace(facture, utilisateur, dossier, "Statut du dossier", libelleStatutDossier(statutAvant),
                    libelleStatutDossier(dossier.getStatut()), null));
        }
        dossier.setUpdatedAt(LocalDateTime.now());
        traces.forEach(facture::tracer);
        return finaliser(facture);
    }

    /**
     * Date de prise en charge d'une visite déjà saisie : celle du dossier ; pour un dossier
     * d'avant qu'il la garde, celle de ses prestations, sinon le jour de son enregistrement.
     */
    private static LocalDate dateDeLaVisite(DossierPatient dossier, List<LigneFactureStructure> lignes) {
        if (dossier.getDatePriseEnCharge() != null) {
            return dossier.getDatePriseEnCharge();
        }
        return lignes.stream()
                .map(LigneFactureStructure::getDatePriseEnCharge)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(dossier.getCreatedAt() == null ? null : dossier.getCreatedAt().toLocalDate());
    }

    /** Retire un patient de la facture : son dossier, ses prestations et ses pièces. */
    @Transactional
    public FactureStructure supprimerDossier(String factureId, String dossierId) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        FactureStructure facture = factureModifiable(factureId);
        DossierPatient dossier = dossierDe(facture, dossierId);
        int prestations = lignesDu(facture, dossierId).size();
        facture.getLignes().removeIf(ligne -> dossierId.equals(ligne.getDossierId()));
        facture.getDossiers().remove(dossier);
        depotPieces.deleteByDossierId(dossierId);
        facture.tracer(trace(facture, utilisateur, dossier, "Patient",
                dossier.nomComplet() + " — " + prestations + " prestation(s)", null, "Patient retiré de la facture"));
        return finaliser(facture);
    }

    /** Photo d'un dossier, lue à la demande (elle n'est pas dans la facture). */
    public Map<String, String> piece(String factureId, String dossierId, String type) {
        FactureStructure facture = parId(factureId);
        dossierDe(facture, dossierId);
        PieceDossier piece = depotPieces.findFirstByDossierIdAndType(dossierId, type)
                .orElseThrow(() -> ExceptionMetier.introuvable(
                        "Aucune pièce « " + PieceDossier.libelle(type) + " » dans ce dossier"));
        return Map.of("type", piece.getType(),
                "libelle", PieceDossier.libelle(piece.getType()),
                "image", piece.getImage());
    }

    /**
     * Contrôle d'éligibilité des régimes à carte (contributifs, BSF / CEC), pour un patient
     * identifié directement par le point de soins (Espace PS, poste comme centre).
     *
     * <p>Le serveur ne se fie pas à l'écran : il interroge lui-même le système de gestion
     * des assurés avec le code d'immatriculation, refuse une carte expirée ou suspendue, et
     * remplace l'identité saisie par celle du système source, qui fait référence et se
     * verrouille. Seule dérogation : le système injoignable, que l'agent a constaté et
     * déclaré (« carte non vérifiée ») — l'identité reste alors saisie, et modifiable.</p>
     *
     * <p>Rien à faire pour une saisie sur lettre de garantie : l'agent BCSU a déjà contrôlé
     * l'éligibilité, et la lettre fait foi.</p>
     *
     * @return la photo du porteur renvoyée par le système des cartes (data URL ou URL), à
     *         ranger dans le dossier ; {@code null} sans contrôle en ligne ou sans photo
     */
    protected String controlerEligibilite(Regime regime, DossierPatient dossier) {
        if (dossier.getLettreGarantieNumero() != null) {
            return null;
        }
        if (!regime.exigeCarteCsu()) {
            dossier.setVerificationCarte(null);
            dossier.setIdentiteVerrouillee(false);
            return null;
        }
        if (VerificationCarte.NON_VERIFIEE_PANNE.equals(dossier.getVerificationCarte())) {
            dossier.setIdentiteVerrouillee(false);
            return null;
        }
        String code = dossier.getMatricule();
        if (code == null || code.isBlank()) {
            throw ExceptionMetier.invalide("Le code d'immatriculation du bénéficiaire est obligatoire pour ce régime.");
        }

        ServiceCarteCsu.CarteCsu carte;
        try {
            carte = serviceCarteCsu.verifier(code);
        } catch (ExceptionMetier e) {
            if (e.getStatut() != HttpStatus.SERVICE_UNAVAILABLE) {
                throw e;
            }
            if (!VerificationCarte.VERIFIEE.equals(dossier.getVerificationCarte())) {
                throw ExceptionMetier.invalide("La carte doit être vérifiée avant la facturation. Le système de "
                        + "vérification étant indisponible, déclarez la saisie « sans vérification ».");
            }
            // L'écran l'avait vérifiée, le serveur ne peut pas le confirmer : on le dit.
            dossier.setVerificationCarte(VerificationCarte.NON_VERIFIEE_PANNE);
            dossier.setIdentiteVerrouillee(false);
            return null;
        }

        if (!carte.carteValide()) {
            String motif;
            if (!carte.carteActive()) {
                motif = "Carte non valide (" + carte.etat() + ")";
            } else if (carte.enPeriodeObservation()) {
                motif = "Bénéficiaire en période d'observation : la prise en charge ne sera ouverte que le "
                        + carte.dateDebutDroits().format(Documents.DATE);
            } else if (carte.dateDebut() != null && carte.dateDebut().isAfter(LocalDate.now())) {
                motif = "Carte pas encore valide (adhésion le " + carte.dateDebut().format(Documents.DATE) + ")";
            } else {
                motif = "Carte expirée" + (carte.dateFin() == null ? "" : " le " + carte.dateFin().format(Documents.DATE));
            }
            throw ExceptionMetier.invalide(motif + " — La facturation ne peut pas être effectuée.");
        }

        dossier.setMatricule(carte.codeImmatriculation());
        if (carte.nom() != null) {
            dossier.setNom(carte.nom());
        }
        if (carte.prenom() != null) {
            dossier.setPrenom(carte.prenom());
        }
        // Sexe, naissance, pièce et téléphone : repris s'ils manquent, mais l'agent peut les
        // corriger — seul le nom fait référence (§5.2).
        if (dossier.getSexe() == null && carte.sexe() != null) {
            dossier.setSexe(carte.sexe());
        }
        if (dossier.getDateNaissance() == null && carte.dateNaissance() != null) {
            dossier.setDateNaissance(carte.dateNaissance());
        }
        if ((dossier.getNumeroCni() == null || dossier.getNumeroCni().isBlank()) && carte.numeroPiece() != null) {
            dossier.setNumeroCni(carte.numeroPiece());
        }
        if ((dossier.getTelephone() == null || dossier.getTelephone().isBlank()) && carte.telephone() != null) {
            dossier.setTelephone(carte.telephone());
        }
        dossier.setCategorieCarte(carte.categorie());
        dossier.setEtatCarte(carte.etat());
        dossier.setTauxCouverture(carte.tauxCouverture());
        dossier.setDateDebutValidite(carte.dateDebut());
        dossier.setDateFinValidite(carte.dateFin());
        dossier.setMutuelle(carte.mutuelle());
        dossier.setTypeAdhesion(carte.typeAdhesion());
        dossier.setDateVerification(LocalDateTime.now());
        dossier.setVerificationCarte(VerificationCarte.VERIFIEE);
        dossier.setIdentiteVerrouillee(true);
        return carte.photo();
    }

    /**
     * Photo du porteur, telle que le système des cartes l'a renvoyée au contrôle : rangée
     * dans le dossier comme une pièce, mais posée par le serveur seul — elle n'est pas dans
     * {@link PieceDossier#TYPES}, l'écran ne peut donc ni la fournir ni la remplacer.
     */
    private void enregistrerPhotoCarte(String factureId, DossierPatient dossier, String photo,
                                       UtilisateurAuthentifie utilisateur) {
        if (photo == null || photo.isBlank()) {
            return;
        }
        PieceDossier piece = depotPieces.findFirstByDossierIdAndType(dossier.getId(), PieceDossier.PHOTO_ASSURE)
                .orElseGet(() -> PieceDossier.builder().dossierId(dossier.getId()).type(PieceDossier.PHOTO_ASSURE).build());
        piece.setFactureId(factureId);
        piece.setImage(photo);
        // Le code d'immatriculation relie la photo au porteur : sa fiche patient la retrouve.
        piece.setMatricule(dossier.getMatricule());
        piece.setCreatedAt(LocalDateTime.now());
        piece.setCreatedBy(utilisateur.getId());
        depotPieces.save(piece);
        marquerPhotoCarte(dossier);
    }

    /** Le dossier ne garde que la liste des pièces présentes ; l'image se lit à la demande. */
    private static void marquerPhotoCarte(DossierPatient dossier) {
        if (dossier.getPieces() == null) {
            dossier.setPieces(new ArrayList<>());
        }
        if (!dossier.possedePiece(PieceDossier.PHOTO_ASSURE)) {
            dossier.getPieces().add(PieceDossier.PHOTO_ASSURE);
        }
    }

    /**
     * Pièces d'identification des régimes sans carte : la CNI recto verso pour le Plan
     * Sésame. La photo de l'extrait de naissance des enfants est facultative depuis le
     * 19/10/2026 : son absence ne laisse plus le dossier « à compléter ». Le ticket de caisse
     * n'en fait pas partie : facultatif au poste, il n'est exigé que des dossiers que le BCSU
     * crée dans la facture d'un centre ou d'un EPS (cf. {@link DossierPatient#exigeTicketDeCaisse()}).
     */
    protected List<String> piecesExigees(Regime regime) {
        return switch (regime) {
            case SESAME -> List.of(PieceDossier.CNI_RECTO, PieceDossier.CNI_VERSO);
            default -> List.of();
        };
    }

    /**
     * Un dossier est complet s'il a au moins une prestation et toutes ses pièces exigées.
     * Une saisie sur lettre de garantie n'exige aucune pièce d'identité — la lettre identifie
     * le patient —, mais celle du BCSU d'un centre / EPS exige un ticket de caisse.
     */
    protected String statutDossier(Regime regime, DossierPatient dossier, int nombreLignes, boolean partiel) {
        if (partiel || nombreLignes == 0 || dossier.attendTicketDeCaisse()) {
            return DossierPatient.A_COMPLETER;
        }
        boolean piecesCompletes = dossier.getLettreGarantieNumero() != null
                || piecesExigees(regime).stream().allMatch(dossier::possedePiece);
        return piecesCompletes ? DossierPatient.COMPLET : DossierPatient.A_COMPLETER;
    }

    protected DossierPatient dossierDe(FactureStructure facture, String dossierId) {
        return (facture.getDossiers() == null ? List.<DossierPatient>of() : facture.getDossiers()).stream()
                .filter(d -> Objects.equals(d.getId(), dossierId))
                .findFirst()
                .orElseThrow(() -> ExceptionMetier.introuvable("Dossier patient introuvable sur cette facture"));
    }

    protected static List<LigneFactureStructure> lignesDu(FactureStructure facture, String dossierId) {
        return (facture.getLignes() == null ? List.<LigneFactureStructure>of() : facture.getLignes()).stream()
                .filter(ligne -> Objects.equals(ligne.getDossierId(), dossierId))
                .toList();
    }

    /**
     * Reconstitue les dossiers des lignes qui n'en ont pas — factures saisies avant les
     * dossiers patients, ou par un centre depuis une lettre de garantie.
     *
     * <p>L'identifiant est <b>déterministe</b> (facture + bénéficiaire) : une lecture et
     * une écriture ultérieures retombent sur le même dossier, sans qu'il ait fallu migrer
     * quoi que ce soit en base.</p>
     */
    protected void normaliserDossiers(FactureStructure facture) {
        if (facture.getDossiers() == null) {
            facture.setDossiers(new ArrayList<>());
        }
        if (facture.getLignes() == null) {
            return;
        }
        Set<String> connus = facture.getDossiers().stream()
                .map(DossierPatient::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.HashSet::new));
        for (LigneFactureStructure ligne : facture.getLignes()) {
            if (ligne.getDossierId() == null) {
                ligne.setDossierId(UUID.nameUUIDFromBytes((facture.getId() + "|" + ligne.cleBeneficiaire())
                        .getBytes(StandardCharsets.UTF_8)).toString());
            }
            if (connus.add(ligne.getDossierId())) {
                facture.getDossiers().add(DossierPatient.builder()
                        .id(ligne.getDossierId())
                        .statut(DossierPatient.COMPLET)
                        .nom(ligne.getPatientNom())
                        .prenom(ligne.getPatientPrenom())
                        .sexe(ligne.getPatientSexe())
                        .dateNaissance(ligne.getPatientDateNaissance())
                        .telephone(ligne.getPatientTelephone())
                        .adresse(ligne.getPatientAdresse())
                        .matricule(ligne.getPatientMatricule())
                        .numeroCni(ligne.getPatientNumeroCni())
                        .numeroRegistre(ligne.getNumeroRegistre())
                        .verificationCarte(ligne.getVerificationCarte())
                        .lettreGarantieNumero(ligne.getLettreGarantieNumero())
                        .identiteVerrouillee(VerificationCarte.VERIFIEE.equals(ligne.getVerificationCarte())
                                || ligne.getLettreGarantieNumero() != null)
                        .pieces(new ArrayList<>())
                        .build());
            }
        }
    }

    /** Le matricule calculé par les règles (Plan Sésame) remonte dans le dossier. */
    private void synchroniserDossiers(FactureStructure facture) {
        if (facture.getDossiers() == null || facture.getLignes() == null) {
            return;
        }
        for (DossierPatient dossier : facture.getDossiers()) {
            if (dossier.getMatricule() != null && !dossier.getMatricule().isBlank()) {
                continue;
            }
            lignesDu(facture, dossier.getId()).stream()
                    .map(LigneFactureStructure::getPatientMatricule)
                    .filter(m -> m != null && !m.isBlank())
                    .findFirst()
                    .ifPresent(dossier::setMatricule);
        }
    }

    /**
     * Le nom validé par le contrôle d'éligibilité (ou par la lettre de garantie) ne se
     * corrige pas à la main, pas plus que l'identifiant qui a servi au contrôle. Les champs
     * absents de la requête sont tolérés (l'écran ne les renvoie pas toujours), les champs
     * différents sont refusés — les ignorer en silence laisserait croire à une correction.
     */
    private static void exigerIdentiteInchangee(DossierPatient dossier, DossierPatient saisi) {
        Map<String, Object[]> champs = new LinkedHashMap<>();
        champs.put("le nom", new Object[]{dossier.getNom(), saisi.getNom()});
        champs.put("le prénom", new Object[]{dossier.getPrenom(), saisi.getPrenom()});
        champs.put(dossier.getLettreGarantieNumero() != null ? "le matricule" : "le code d'immatriculation",
                new Object[]{dossier.getMatricule(), saisi.getMatricule()});
        for (Map.Entry<String, Object[]> champ : champs.entrySet()) {
            Object actuel = champ.getValue()[0];
            Object demande = champ.getValue()[1];
            if (demande != null && !egal(actuel, demande)) {
                throw ExceptionMetier.interdit("L'identité de « " + dossier.nomComplet() + " » a été validée par "
                        + (dossier.getLettreGarantieNumero() != null ? "la lettre de garantie" : "le contrôle d'éligibilité")
                        + " : " + champ.getKey() + " ne peut pas être modifié.");
            }
        }
    }

    private static void exigerIdentite(DossierPatient dossier) {
        if ((dossier.getNom() == null || dossier.getNom().isBlank())
                && (dossier.getPrenom() == null || dossier.getPrenom().isBlank())) {
            throw ExceptionMetier.invalide("Le nom et le prénom du patient sont obligatoires.");
        }
    }

    private static boolean egal(Object a, Object b) {
        if (a instanceof String sa && b instanceof String sb) {
            return sa.trim().equalsIgnoreCase(sb.trim());
        }
        return Objects.equals(a, b);
    }

    private <T> void changer(List<HistoriqueDossier> traces, FactureStructure facture,
                             UtilisateurAuthentifie utilisateur, DossierPatient dossier, String champ,
                             T ancien, T nouveau, java.util.function.Consumer<T> affecter) {
        if (egal(ancien, nouveau)) {
            return;
        }
        affecter.accept(nouveau);
        traces.add(trace(facture, utilisateur, dossier, champ, texte(ancien), texte(nouveau), null));
    }

    /**
     * Historise les prestations modifiées : une même prestation (même désignation) dont la
     * quantité ou le prix change est une modification, le reste est ajout ou suppression.
     */
    private void tracerLignes(List<HistoriqueDossier> traces, FactureStructure facture,
                              UtilisateurAuthentifie utilisateur, DossierPatient dossier,
                              List<LigneFactureStructure> anciennes, List<LigneFactureStructure> nouvelles) {
        List<LigneFactureStructure> restantesAnciennes = new ArrayList<>(anciennes);
        List<LigneFactureStructure> restantesNouvelles = new ArrayList<>();
        for (LigneFactureStructure nouvelle : nouvelles) {
            Optional<LigneFactureStructure> identique = restantesAnciennes.stream()
                    .filter(a -> resume(a).equals(resume(nouvelle)))
                    .findFirst();
            if (identique.isPresent()) {
                restantesAnciennes.remove(identique.get());
            } else {
                restantesNouvelles.add(nouvelle);
            }
        }
        for (LigneFactureStructure nouvelle : restantesNouvelles) {
            Optional<LigneFactureStructure> meme = restantesAnciennes.stream()
                    .filter(a -> egal(a.getDesignation(), nouvelle.getDesignation()))
                    .findFirst();
            if (meme.isPresent()) {
                restantesAnciennes.remove(meme.get());
                traces.add(trace(facture, utilisateur, dossier, "Prestation modifiée", resume(meme.get()),
                        resume(nouvelle), null));
            } else {
                traces.add(trace(facture, utilisateur, dossier, "Prestation ajoutée", null, resume(nouvelle), null));
            }
        }
        for (LigneFactureStructure supprimee : restantesAnciennes) {
            traces.add(trace(facture, utilisateur, dossier, "Prestation supprimée", resume(supprimee), null, null));
        }
    }

    private static String resume(LigneFactureStructure ligne) {
        return Documents.ouVide(ligne.getDesignation()) + " × " + Math.max(1, ligne.getQuantite())
                + (ligne.getPrixUnitaire() > 0 ? " à " + Documents.montant(ligne.getPrixUnitaire()) + " F" : "");
    }

    private static String texte(Object valeur) {
        if (valeur == null) {
            return null;
        }
        if (valeur instanceof LocalDate date) {
            return date.format(Documents.DATE);
        }
        String texte = String.valueOf(valeur);
        return texte.isBlank() ? null : texte;
    }

    private static String libelleStatutDossier(String statut) {
        if (DossierPatient.A_COMPLETER.equals(statut)) {
            return "À compléter";
        }
        return DossierPatient.COMPLET.equals(statut) ? "Complet" : statut;
    }

    private static HistoriqueDossier trace(FactureStructure facture, UtilisateurAuthentifie utilisateur,
                                           DossierPatient dossier, String champ, String ancienne,
                                           String nouvelle, String commentaire) {
        return HistoriqueDossier.builder()
                .date(LocalDateTime.now())
                .statut(facture.getStatut() == null ? null : facture.getStatut().name())
                .utilisateurId(utilisateur.getId())
                .utilisateurNom(utilisateur.nomComplet())
                .commentaire(commentaire != null ? commentaire
                        : dossier == null ? champ : champ + " — " + dossier.nomComplet())
                .dossierId(dossier == null ? null : dossier.getId())
                .champ(champ)
                .ancienneValeur(ancienne)
                .nouvelleValeur(nouvelle)
                .build();
    }

    // ----- pièces -----

    /** Types des pièces envoyées non vides, après contrôle de forme. */
    private static List<String> typesFournis(Map<String, String> pieces) {
        List<String> types = new ArrayList<>();
        if (pieces == null) {
            return types;
        }
        pieces.forEach((type, image) -> {
            if (image != null && !image.isBlank()) {
                controlerPiece(type, image);
                types.add(type);
            }
        });
        return types;
    }

    private static void controlerPiece(String type, String image) {
        if (!PieceDossier.TYPES.contains(type)) {
            throw ExceptionMetier.invalide("Type de pièce inconnu : " + type);
        }
        if (!image.startsWith("data:image/") || image.length() > TAILLE_MAX_PIECE) {
            throw ExceptionMetier.invalide("« " + PieceDossier.libelle(type)
                    + " » : la photo est illisible ou trop lourde. Reprenez-la.");
        }
    }

    private void enregistrerPieces(String factureId, String dossierId, Map<String, String> pieces,
                                   UtilisateurAuthentifie utilisateur) {
        if (pieces == null) {
            return;
        }
        pieces.forEach((type, image) -> {
            if (image != null && !image.isBlank()) {
                PieceDossier piece = depotPieces.findFirstByDossierIdAndType(dossierId, type)
                        .orElseGet(() -> PieceDossier.builder().dossierId(dossierId).type(type).build());
                piece.setFactureId(factureId);
                piece.setImage(image);
                piece.setCreatedAt(LocalDateTime.now());
                piece.setCreatedBy(utilisateur.getId());
                depotPieces.save(piece);
            }
        });
    }

    /** Ajoute, remplace ou retire (valeur vide) les pièces d'un dossier existant. */
    private void appliquerPieces(FactureStructure facture, DossierPatient dossier, Map<String, String> pieces,
                                 UtilisateurAuthentifie utilisateur, List<HistoriqueDossier> traces) {
        if (pieces == null || pieces.isEmpty()) {
            return;
        }
        if (dossier.getPieces() == null) {
            dossier.setPieces(new ArrayList<>());
        }
        typesFournis(pieces);
        for (Map.Entry<String, String> entree : pieces.entrySet()) {
            String type = entree.getKey();
            String image = entree.getValue();
            if (image == null) {
                continue;
            }
            String libelle = PieceDossier.libelle(type);
            if (image.isBlank()) {
                if (dossier.possedePiece(type)) {
                    depotPieces.deleteByDossierIdAndType(dossier.getId(), type);
                    dossier.getPieces().remove(type);
                    traces.add(trace(facture, utilisateur, dossier, "Pièce justificative", libelle, null,
                            libelle + " retiré(e) — " + dossier.nomComplet()));
                }
                continue;
            }
            boolean remplacement = dossier.possedePiece(type);
            enregistrerPieces(facture.getId(), dossier.getId(), Map.of(type, image), utilisateur);
            if (!remplacement) {
                dossier.getPieces().add(type);
            }
            traces.add(trace(facture, utilisateur, dossier, "Pièce justificative",
                    remplacement ? libelle : null, libelle,
                    libelle + (remplacement ? " remplacé(e)" : " ajouté(e)") + " — " + dossier.nomComplet()));
        }
    }

    /** Facture de la structure qui a déjà reçu la saisie portant cette clé, s'il y en a une. */
    private Optional<FactureStructure> factureAyantRecu(String structureId, String cle) {
        return depot.findByStructureSanitaireId(structureId).stream()
                .filter(f -> f.getDossiers() != null && f.getDossiers().stream()
                        .anyMatch(d -> d.getClesSynchronisation() != null && d.getClesSynchronisation().contains(cle)))
                .findFirst();
    }

    /** Facture du mois pour ce régime — récupérée, ou créée et numérotée. */
    private FactureStructure factureDuMois(String structureId, Regime regime, int mois, int annee,
                                           UtilisateurAuthentifie utilisateur) {
        Optional<FactureStructure> existante =
                depot.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(structureId, regime, mois, annee);
        if (existante.isPresent()) {
            return existante.get();
        }
        // Première saisie du mois pour ce régime. Deux saisies simultanées créeraient chacune
        // leur facture — et toutes les suivantes échoueraient, deux factures répondant au même
        // mois. La seconde attend la première, puis la trouve.
        depot.verrouiller("facture:" + structureId + ":" + regime + ":" + annee + "-" + mois);
        existante = depot.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(structureId, regime, mois, annee);
        if (existante.isPresent()) {
            return existante.get();
        }

        FactureStructure facture = new FactureStructure();
        facture.setRegime(regime);
        facture.setMois(mois);
        facture.setAnnee(annee);
        facture.setStatut(StatutFacture.BROUILLON);
        facture.setLignes(new ArrayList<>());
        facture.setHistorique(new ArrayList<>());
        facture.setDocumentsComplementaires(new ArrayList<>());
        appliquerContexteStructure(facture, structureId);
        facture.setNumero(numerotation.prochainNumero(PREFIXE_NUMERO, annee, mois));
        facture.setCreatedAt(LocalDateTime.now());
        facture.setCreatedBy(utilisateur.getId());
        facture.tracer(evenement(StatutFacture.BROUILLON, utilisateur, "Création de la facture mensuelle"));
        return facture;
    }

    /** Remplace le contenu d'une facture existante (édition d'un brouillon, correction). */
    @Transactional
    public FactureStructure remplacer(String id, RequeteFacturation requete) {
        FactureStructure facture = factureModifiable(id);
        appliquerLettreGarantie(requete);
        facture.setLignes(new ArrayList<>(requete.lignesCompletees()));
        if (requete.getTicketCaisse() != null) {
            facture.setTicketCaisse(requete.getTicketCaisse());
        }
        if (requete.getDocumentsComplementaires() != null) {
            facture.setDocumentsComplementaires(new ArrayList<>(requete.getDocumentsComplementaires()));
        }
        return finaliser(facture);
    }

    /** Retarification, application des règles, enregistrement et sorties de stock. */
    private FactureStructure finaliser(FactureStructure facture) {
        retarifer(facture);
        regles.appliquer(facture);
        synchroniserDossiers(facture);
        facture.setUpdatedAt(LocalDateTime.now());
        FactureStructure enregistree = depot.save(facture);
        declencherSortiesStock(enregistree);
        synchroniserFeuillesSoins(enregistree);
        return enregistree;
    }

    /**
     * Reporte sur la feuille de soins ce qui a réellement été facturé.
     *
     * <p>La feuille est émise par l'agent BCSU sans prestations : personne ne sait encore ce
     * qui sera délivré. C'est la facturation de la structure qui la renseigne — d'où cette
     * reprise à chaque enregistrement.</p>
     *
     * <p>Le cœur le faisait, en cinq points de son {@code FactureStructureService} ; le
     * découpage a laissé ce comportement derrière lui, et comme la passerelle confie
     * désormais {@code POST /api/factures-structure} aux microservices, plus aucune feuille
     * n'était mise à jour : elles restaient à {@code prestations: []}, montants à zéro et
     * sans lien vers leur facture.</p>
     *
     * <p>Le rapprochement se fait sur l'<b>identifiant</b> de la lettre de garantie, pas sur
     * son numéro — d'où l'importance de le renseigner, ce que fait {@link
     * #appliquerLettreGarantie}. Avec un identifiant nul, la boucle ne trouvait rien.</p>
     */
    private void synchroniserFeuillesSoins(FactureStructure facture) {
        Set<String> lettreIds = facture.getLignes().stream()
                .map(LigneFactureStructure::getLettreGarantieId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (String lettreId : lettreIds) {
            List<FeuilleSoins> feuilles = depotFeuilleSoins.findByLettreGarantieId(lettreId);
            if (feuilles.isEmpty()) {
                continue;
            }
            List<LigneFactureStructure> lignes = facture.getLignes().stream()
                    .filter(l -> Objects.equals(l.getLettreGarantieId(), lettreId))
                    .toList();

            FeuilleSoins feuille = feuilles.get(0);
            feuille.setPrestations(lignes.stream()
                    .map(l -> PrestationSoins.builder()
                            .date(l.getDatePriseEnCharge())
                            .designation(l.getDesignation())
                            .montant(l.getMontant())
                            .partAssure(l.getMontantBeneficiaire())
                            .partAssureur(l.getMontantSencsu())
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new)));
            feuille.setMontantTotal(lignes.stream().mapToDouble(LigneFactureStructure::getMontant).sum());
            feuille.setMontantTotalAssure(
                    lignes.stream().mapToDouble(LigneFactureStructure::getMontantBeneficiaire).sum());
            feuille.setMontantTotalAssureur(
                    lignes.stream().mapToDouble(LigneFactureStructure::getMontantSencsu).sum());
            feuille.setFactureStructureId(facture.getId());
            feuille.setFactureStructureNumero(facture.getNumero());
            feuille.setUpdatedAt(LocalDateTime.now());
            depotFeuilleSoins.save(feuille);
        }
    }

    /** Supprime toutes les lignes d'un bénéficiaire (n° de lettre de garantie ou identifiant patient). */
    @Transactional
    public FactureStructure supprimerPatient(String factureId, String reference) {
        FactureStructure facture = factureModifiable(factureId);
        List<LigneFactureStructure> restantes = facture.getLignes().stream()
                .filter(l -> !reference.equals(l.getLettreGarantieNumero())
                        && !reference.equals(l.getPatientId())
                        && !reference.equals(l.cleBeneficiaire()))
                .toList();
        if (restantes.size() == facture.getLignes().size()) {
            throw ExceptionMetier.introuvable("Aucune ligne ne correspond à « " + reference + " »");
        }
        facture.setLignes(new ArrayList<>(restantes));
        // Les dossiers vidés de leurs lignes partent avec elles (ils n'étaient que leur reflet).
        Set<String> dossiersRestants = restantes.stream()
                .map(LigneFactureStructure::getDossierId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<DossierPatient> retires = facture.getDossiers().stream()
                .filter(d -> !dossiersRestants.contains(d.getId()) && !d.estACompleter())
                .toList();
        facture.getDossiers().removeAll(retires);
        retires.forEach(d -> depotPieces.deleteByDossierId(d.getId()));
        facture.tracer(trace(facture, ContexteUtilisateur.courant(), null, "Patient", reference, null,
                "Patient retiré de la facture (" + reference + ")"));
        return finaliser(facture);
    }

    @Transactional
    public FactureStructure modifierLigne(String factureId, int index, LigneFactureStructure modifications) {
        FactureStructure facture = factureModifiable(factureId);
        LigneFactureStructure ligne = ligneA(facture, index);
        String avant = resume(ligne);
        if (modifications.getPrestationId() != null) {
            ligne.setPrestationId(modifications.getPrestationId());
        }
        if (modifications.getDesignation() != null) {
            ligne.setDesignation(modifications.getDesignation());
        }
        if (modifications.getDatePriseEnCharge() != null) {
            ligne.setDatePriseEnCharge(modifications.getDatePriseEnCharge());
        }
        if (modifications.getQuantite() > 0) {
            ligne.setQuantite(modifications.getQuantite());
        }
        // Le prix ne se saisit à la main que hors référentiel : sinon il serait de toute
        // façon réécrasé par la retarification.
        if (ligne.getPrestationId() == null && modifications.getPrixUnitaire() > 0) {
            ligne.setPrixUnitaire(modifications.getPrixUnitaire());
        }
        if (!avant.equals(resume(ligne))) {
            facture.tracer(trace(facture, ContexteUtilisateur.courant(), dossierDeLigne(facture, ligne),
                    "Prestation modifiée", avant, resume(ligne), null));
        }
        return finaliser(facture);
    }

    @Transactional
    public FactureStructure supprimerLigne(String factureId, int index) {
        FactureStructure facture = factureModifiable(factureId);
        LigneFactureStructure ligne = ligneA(facture, index);
        facture.getLignes().remove(index);
        facture.tracer(trace(facture, ContexteUtilisateur.courant(), dossierDeLigne(facture, ligne),
                "Prestation supprimée", resume(ligne), null, null));
        return finaliser(facture);
    }

    private DossierPatient dossierDeLigne(FactureStructure facture, LigneFactureStructure ligne) {
        return facture.getDossiers() == null ? null : facture.getDossiers().stream()
                .filter(d -> Objects.equals(d.getId(), ligne.getDossierId()))
                .findFirst()
                .orElse(null);
    }

    private LigneFactureStructure ligneA(FactureStructure facture, int index) {
        if (facture.getLignes() == null || index < 0 || index >= facture.getLignes().size()) {
            throw ExceptionMetier.introuvable("Ligne n°" + index + " inexistante sur cette facture");
        }
        return facture.getLignes().get(index);
    }

    private FactureStructure factureModifiable(String id) {
        FactureStructure facture = depot.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Facture introuvable : " + id));
        if (!ContexteUtilisateur.structureCourante().equals(facture.getStructureSanitaireId())) {
            throw ExceptionMetier.interdit("Cette facture n'appartient pas à votre structure");
        }
        if (!facture.getStatut().estModifiable()) {
            throw ExceptionMetier.conflit("Facture « " + facture.getStatut().getLibelle()
                    + " » : elle ne peut plus être modifiée.");
        }
        normaliserDossiers(facture);
        return facture;
    }

    /**
     * Réapplique les prix du référentiel sur chaque ligne qui cite une prestation.
     *
     * <p>Les lignes sans {@code prestationId} — saisie libre, actes hors nomenclature, et
     * toutes les factures créées par le cœur avant l'arrivée du référentiel — conservent
     * leur prix saisi.</p>
     */
    protected void retarifer(FactureStructure facture) {
        List<LigneFactureStructure> lignes = facture.getLignes();
        if (lignes == null || lignes.isEmpty()) {
            return;
        }
        List<String> ids = lignes.stream()
                .map(LigneFactureStructure::getPrestationId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<String, ClientReferentiel.PrestationTarifee> tarifs = clientReferentiel.tarifs(ids);
        for (LigneFactureStructure ligne : lignes) {
            ClientReferentiel.PrestationTarifee tarif = tarifs.get(ligne.getPrestationId());
            if (tarif == null) {
                continue;
            }
            if (!tarif.actif()) {
                throw ExceptionMetier.invalide("La prestation « " + tarif.designation()
                        + " » est désactivée : elle ne peut plus être facturée.");
            }
            ligne.setPrixUnitaire(tarif.prix());
            ligne.setDesignation(tarif.designation());
            ligne.setCodeActe(tarif.code());
            if (ligne.getService() == null || ligne.getService().isBlank()) {
                ligne.setService(tarif.serviceNom());
            }
            ligne.setDepositaire(tarif.depositaire());
            ligne.setMedicamentId(tarif.medicamentId());
        }
    }

    /** Recopie l'organisation sanitaire dans la facture, pour qu'elle reste lisible plus tard. */
    protected void appliquerContexteStructure(FactureStructure facture, String structureId) {
        StructureDto structure = clientIdentite.structure(structureId);
        facture.setStructureSanitaireId(structure.getId());
        facture.setStructureNom(structure.getNom());
        facture.setTypeStructure(structure.getTypeStructure());
        facture.setStructureRattachementId(structure.getStructureRattachementId());
        facture.setDistrictId(structure.getDistrictId());
        facture.setDistrictNom(structure.getDistrictNom());
        facture.setRegionId(structure.getRegionId());
    }

    /**
     * Sortie de stock automatique des lignes dépositaires (§14).
     *
     * <p>Volontairement non bloquant : si la pharmacie est injoignable, la facture reste
     * enregistrée et le mouvement pourra être rejoué — la clé d'idempotence empêche le
     * double décrément.</p>
     */
    protected void declencherSortiesStock(FactureStructure facture) {
        if (facture.getLignes() == null) {
            return;
        }
        boolean modifie = false;
        for (int i = 0; i < facture.getLignes().size(); i++) {
            LigneFactureStructure ligne = facture.getLignes().get(i);
            if (!ligne.isDepositaire() || ligne.getMouvementStockId() != null
                    || ligne.getMedicamentId() == null) {
                continue;
            }
            String mouvement = clientPharmacie.sortieStock(SortieStockDto.builder()
                    .structureId(facture.getStructureSanitaireId())
                    .medicamentId(ligne.getMedicamentId())
                    .prestationId(ligne.getPrestationId())
                    .designation(ligne.getDesignation())
                    .quantite(Math.max(1, ligne.getQuantite()))
                    .date(ligne.getDatePriseEnCharge() == null ? LocalDate.now() : ligne.getDatePriseEnCharge())
                    .referenceFacture(facture.getNumero())
                    .referenceLigne(facture.getNumero() + "#" + i)
                    .motif("Facturation CSU — " + facture.periodeLibelle())
                    .build());
            if (mouvement != null) {
                ligne.setMouvementStockId(mouvement);
                modifie = true;
            }
        }
        if (modifie) {
            depot.save(facture);
        }
    }

    @Transactional
    public void supprimer(String id) {
        FactureStructure facture = depot.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Facture introuvable : " + id));
        if (!ContexteUtilisateur.structureCourante().equals(facture.getStructureSanitaireId())) {
            throw ExceptionMetier.interdit("Cette facture n'appartient pas à votre structure");
        }
        if (facture.getStatut() != StatutFacture.BROUILLON) {
            throw ExceptionMetier.conflit("Seul un brouillon peut être supprimé. "
                    + "Cette facture est « " + facture.getStatut().getLibelle() + " ».");
        }
        depotPieces.deleteByFactureId(id);
        depot.delete(facture);
    }

    // ══════════════════════════ circuit de validation ══════════════════════════

    /**
     * Transmission par la structure émettrice. Le statut d'arrivée dépend de son type,
     * et c'est toute la logique du §6 : un poste passe d'abord par son centre de
     * rattachement, un centre ou un EPS transmet directement au service régional.
     */
    @Transactional
    public FactureStructure envoyer(String id) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        FactureStructure facture = depot.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Facture introuvable : " + id));

        if (!ContexteUtilisateur.structureCourante().equals(facture.getStructureSanitaireId())) {
            throw ExceptionMetier.interdit("Cette facture n'appartient pas à votre structure");
        }
        if (!facture.getStatut().estModifiable()) {
            throw ExceptionMetier.conflit("Facture déjà transmise (« "
                    + facture.getStatut().getLibelle() + " »).");
        }
        if (facture.getLignes() == null || facture.getLignes().isEmpty()) {
            throw ExceptionMetier.invalide("Une facture sans aucune prestation ne peut pas être transmise.");
        }
        List<DossierPatient> incomplets = facture.getDossiers() == null ? List.of()
                : facture.getDossiers().stream().filter(d -> d.estACompleter() || d.attendTicketDeCaisse()).toList();
        if (!incomplets.isEmpty()) {
            throw ExceptionMetier.conflit(incomplets.size() + " dossier(s) patient encore « à compléter » ("
                    + incomplets.stream().map(DossierPatient::nomComplet).limit(5).collect(Collectors.joining(", "))
                    + (incomplets.size() > 5 ? "…" : "")
                    + ") : complétez-les depuis « Mes factures → Modifier » avant d'envoyer la facture.");
        }

        StatutFacture cible = statutApresEnvoi(facture);
        facture.setStatut(cible);
        facture.setCommentaireRejet(null);
        // Renvoi après correction : on efface les rejets par patient de la passe précédente.
        if (facture.getDossiers() != null) {
            facture.getDossiers().forEach(DossierPatient::effacerRejet);
        }
        facture.setUpdatedAt(LocalDateTime.now());
        facture.tracer(evenement(cible, utilisateur, cible == StatutFacture.SOUMISE_CS
                ? "Soumise au district (Enfants de moins de 5 ans)" : "Transmise au service régional"));
        return depot.save(facture);
    }

    /** Envoi groupé de fin de mois : tous les brouillons de la période partent ensemble. */
    @Transactional
    public List<FactureStructure> envoyerGroupe(Regime regime, Integer mois, Integer annee) {
        LocalDate aujourdhui = LocalDate.now();
        int m = mois == null || mois <= 0 ? aujourdhui.getMonthValue() : mois;
        int a = annee == null || annee <= 0 ? aujourdhui.getYear() : annee;

        List<FactureStructure> candidates = mesFactures(m, a, regime, null).stream()
                .filter(f -> f.getStatut().estModifiable())
                .filter(f -> f.getLignes() != null && !f.getLignes().isEmpty())
                .toList();
        if (candidates.isEmpty()) {
            throw ExceptionMetier.invalide("Aucune facture à transmettre pour "
                    + FactureStructure.MOIS[m] + " " + a + ".");
        }
        List<FactureStructure> envoyees = new ArrayList<>();
        for (FactureStructure facture : candidates) {
            envoyees.add(envoyer(facture.getId()));
        }
        return envoyees;
    }

    /**
     * Destination de l'envoi. Le régime « Enfants de moins de 5 ans » d'un poste (09/10/2026)
     * et d'un centre de santé (spécification « Facturation CS / EPS », §8 — arbitrage du
     * 12/10/2026) passe d'abord par le DISTRICT ; tout le reste, et tout ce qu'émet un EPS, va
     * directement au service régional. Le type est celui figé sur la facture, à défaut celui de
     * la structure.
     */
    protected StatutFacture statutApresEnvoi(FactureStructure facture) {
        TypeStructure type = facture.getTypeStructure();
        if (type == null) {
            type = clientIdentite.structure(facture.getStructureSanitaireId()).getTypeStructure();
            facture.setTypeStructure(type);
        }
        if (type != null && type.passeParLeDistrict(facture.getRegime())) {
            // Le district doit être connu avant l'envoi : c'est lui qui contrôle.
            String district = districtDe(facture);
            if (district == null) {
                throw ExceptionMetier.invalide("Votre structure n'est rattachée à aucun district. "
                        + "Demandez à votre service régional de renseigner le district avant de transmettre.");
            }
            facture.setDistrictId(district);
            return StatutFacture.SOUMISE_CS;
        }
        return StatutFacture.ENVOYEE;
    }

    /**
     * Validation par le district : {@code SOUMISE_CS → ENVOYEE}. (Le nom du statut est
     * historique : il désignait la soumission au centre ; son libellé dit désormais « district ».)
     */
    @Transactional
    public FactureStructure validerParDistrict(String id) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        FactureStructure facture = chargerPourDistrict(id, utilisateur);
        exigerStatut(facture, StatutFacture.SOUMISE_CS, "validée");
        facture.setStatut(StatutFacture.ENVOYEE);
        facture.setCommentaireRejet(null);
        facture.setUpdatedAt(LocalDateTime.now());
        facture.tracer(evenement(StatutFacture.ENVOYEE, utilisateur,
                "Validée par le district et transmise au service régional"));
        return depot.save(facture);
    }

    /**
     * Rejet par le district : {@code SOUMISE_CS → REJETEE_CS}, renvoyée au poste. Le motif vient
     * des patients rejetés un à un (cf. {@link #rejeterDossiers}) ; {@code motif} n'est plus
     * qu'une note générale facultative. Il faut au moins un patient rejeté ou une note.
     */
    @Transactional
    public FactureStructure rejeterParDistrict(String id, String motif) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        FactureStructure facture = chargerPourDistrict(id, utilisateur);
        exigerStatut(facture, StatutFacture.SOUMISE_CS, "rejetée");
        String commentaire = commentaireRejet(facture, true, motif);
        facture.setStatut(StatutFacture.REJETEE_CS);
        facture.setCommentaireRejet(commentaire);
        facture.setUpdatedAt(LocalDateTime.now());
        HistoriqueDossier evenement = evenement(StatutFacture.REJETEE_CS, utilisateur,
                "Rejetée par le district");
        evenement.setCommentaire(commentaire);
        facture.tracer(evenement);
        return depot.save(facture);
    }

    /** Facture d'un poste ou d'un centre du district de l'appelant — seul le district la contrôle. */
    private FactureStructure chargerPourDistrict(String id, UtilisateurAuthentifie utilisateur) {
        FactureStructure facture = depot.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Facture introuvable : " + id));
        if (!utilisateur.aCapaciteDistrict()) {
            throw ExceptionMetier.interdit("Seul le district (ou le centre siège du district) contrôle "
                    + "les factures 0-5 ans des postes et des centres de santé.");
        }
        String district = districtDe(facture);
        if (utilisateur.getDistrictId() == null || !utilisateur.getDistrictId().equals(district)) {
            throw ExceptionMetier.interdit("Cette facture provient d'une structure qui n'est pas dans votre district.");
        }
        return facture;
    }

    /** Validation par le service régional : {@code ENVOYEE → VALIDEE_SR}. */
    @Transactional
    public FactureStructure validerParServiceRegional(String id, String commentaire) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        FactureStructure facture = chargerPourRegion(id, utilisateur);
        exigerStatut(facture, StatutFacture.ENVOYEE, "validée");
        facture.setStatut(StatutFacture.VALIDEE_SR);
        facture.setCommentaireRejet(null);
        facture.setUpdatedAt(LocalDateTime.now());
        HistoriqueDossier evenement = evenement(StatutFacture.VALIDEE_SR, utilisateur,
                "Validée par le service régional");
        evenement.setCommentaire(commentaire);
        facture.tracer(evenement);
        return depot.save(facture);
    }

    /**
     * Rejet par le service régional : {@code ENVOYEE → REJETEE_SR}, renvoyée à la structure. Le
     * motif vient des patients rejetés un à un ; {@code motif} n'est plus qu'une note générale.
     */
    @Transactional
    public FactureStructure rejeterParServiceRegional(String id, String motif) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        FactureStructure facture = chargerPourRegion(id, utilisateur);
        exigerStatut(facture, StatutFacture.ENVOYEE, "rejetée");
        String commentaire = commentaireRejet(facture, false, motif);
        facture.setStatut(StatutFacture.REJETEE_SR);
        facture.setCommentaireRejet(commentaire);
        facture.setUpdatedAt(LocalDateTime.now());
        HistoriqueDossier evenement = evenement(StatutFacture.REJETEE_SR, utilisateur,
                "Rejetée par le service régional");
        evenement.setCommentaire(commentaire);
        facture.tracer(evenement);
        return depot.save(facture);
    }

    /**
     * Marque (ou annule) le rejet de patients précis pendant le contrôle — « rejet par ligne » :
     * le district (1er échelon) ou le service régional en désigne un ou plusieurs avec un motif,
     * sans trancher sur toute la facture. Le renvoi effectif se fait ensuite par
     * {@link #rejeterParDistrict}/{@link #rejeterParServiceRegional}, qui agrège les motifs.
     */
    @Transactional
    public FactureStructure rejeterDossiers(String factureId, List<String> dossierIds, String motif, boolean rejete) {
        if (dossierIds == null || dossierIds.isEmpty()) {
            throw ExceptionMetier.invalide("Aucun patient à rejeter.");
        }
        if (rejete && (motif == null || motif.isBlank())) {
            throw ExceptionMetier.invalide("Le motif du rejet du patient est obligatoire.");
        }
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        Role role = utilisateur.getRole();
        boolean regional = role == Role.SERVICE_REGIONAL || role == Role.SERVICE_CENTRAL || role == Role.ADMIN;

        FactureStructure facture;
        StatutFacture etape;
        if (regional) {
            facture = chargerPourRegion(factureId, utilisateur);
            etape = StatutFacture.ENVOYEE;
        } else {
            facture = chargerPourDistrict(factureId, utilisateur);
            etape = StatutFacture.SOUMISE_CS;
        }
        if (facture.getStatut() != etape) {
            throw ExceptionMetier.conflit("Le rejet des patients se fait pendant le contrôle de la facture, "
                    + "quand elle est « " + etape.getLibelle() + " ». Celle-ci est « "
                    + facture.getStatut().getLibelle() + " ».");
        }

        normaliserDossiers(facture);
        LocalDateTime le = rejete ? LocalDateTime.now() : null;
        String par = rejete ? utilisateur.nomComplet() : null;
        String m = rejete ? motif.trim() : null;
        for (String dossierId : dossierIds) {
            DossierPatient dossier = dossierDe(facture, dossierId);
            if (regional) {
                dossier.setRejeteRegionLe(le);
                dossier.setRejeteRegionPar(par);
                dossier.setRejeteRegionMotif(m);
            } else {
                dossier.setRejeteCentreLe(le);
                dossier.setRejeteCentrePar(par);
                dossier.setRejeteCentreMotif(m);
            }
        }
        // Même raison que le pointage : un champ muté DANS le jsonb n'est pas vu par Hibernate.
        facture.setDossiers(new ArrayList<>(facture.getDossiers()));
        facture.setUpdatedAt(LocalDateTime.now());
        return depot.save(facture);
    }

    /**
     * Commentaire de rejet d'une facture, construit à partir des patients rejetés un à un à
     * l'échelon courant (motif par patient). {@code note} est une remarque générale facultative.
     * Faute de patient rejeté et de note, le renvoi est refusé.
     */
    private String commentaireRejet(FactureStructure facture, boolean district, String note) {
        List<DossierPatient> rejetes = facture.getDossiers() == null ? List.of()
                : facture.getDossiers().stream()
                .filter(d -> district ? d.getRejeteCentreLe() != null : d.getRejeteRegionLe() != null)
                .toList();
        if (rejetes.isEmpty()) {
            if (note == null || note.isBlank()) {
                throw ExceptionMetier.invalide("Rejetez au moins un patient (avec son motif) ou indiquez "
                        + "un motif général avant de renvoyer la facture.");
            }
            return note.trim();
        }
        StringBuilder sb = new StringBuilder();
        if (note != null && !note.isBlank()) {
            sb.append(note.trim()).append('\n');
        }
        sb.append(rejetes.size()).append(" patient(s) rejeté(s) à corriger :");
        for (DossierPatient d : rejetes) {
            String m = district ? d.getRejeteCentreMotif() : d.getRejeteRegionMotif();
            sb.append("\n• ").append(d.nomComplet()).append(m == null || m.isBlank() ? "" : " — " + m);
        }
        return sb.toString();
    }

    /**
     * Pointage « patient vérifié » pendant le contrôle d'une facture : une facture peut
     * compter cent patients et plus, que l'échelon de contrôle coche un à un à mesure qu'il
     * les juge conformes.
     *
     * <p>Deux échelons, deux pointages indépendants : le centre de rattachement sur la
     * facture d'un poste qu'il polarise (« Soumise au centre »), le service régional — ou
     * le niveau national — sur une facture reçue (« Transmise au service régional »). Hors
     * de ces étapes, le pointage est figé : il témoigne du contrôle fait.</p>
     *
     * <p>Un patient venu plusieurs fois a plusieurs dossiers : l'écran les pointe ensemble.
     * Seuls les champs de pointage changent — ni montants, ni statut, ni historique (cent
     * coches n'ont pas leur place dans le parcours de la facture ; qui et quand restent sur
     * le dossier).</p>
     */
    @Transactional
    public FactureStructure pointerDossiers(String factureId, List<String> dossierIds, boolean verifie) {
        if (dossierIds == null || dossierIds.isEmpty()) {
            throw ExceptionMetier.invalide("Aucun patient à pointer.");
        }
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        Role role = utilisateur.getRole();
        boolean regional = role == Role.SERVICE_REGIONAL || role == Role.SERVICE_CENTRAL || role == Role.ADMIN;

        FactureStructure facture;
        StatutFacture etape;
        if (regional) {
            facture = chargerPourRegion(factureId, utilisateur);
            etape = StatutFacture.ENVOYEE;
        } else {
            // Premier échelon de contrôle d'une facture de poste : le district (champs
            // « verifieCentre* » conservés tels quels en base, leur sens est « 1er échelon »).
            facture = chargerPourDistrict(factureId, utilisateur);
            etape = StatutFacture.SOUMISE_CS;
        }
        if (facture.getStatut() != etape) {
            throw ExceptionMetier.conflit("Le pointage des patients se fait pendant le contrôle de la facture, "
                    + "quand elle est « " + etape.getLibelle() + " ». Celle-ci est « "
                    + facture.getStatut().getLibelle() + " ».");
        }

        normaliserDossiers(facture);
        LocalDateTime le = verifie ? LocalDateTime.now() : null;
        String par = verifie ? utilisateur.nomComplet() : null;
        for (String dossierId : dossierIds) {
            DossierPatient dossier = dossierDe(facture, dossierId);
            if (regional) {
                dossier.setVerifieRegionLe(le);
                dossier.setVerifieRegionPar(par);
            } else {
                dossier.setVerifieCentreLe(le);
                dossier.setVerifieCentrePar(par);
            }
        }
        // Le pointage ne change qu'un champ À L'INTÉRIEUR de la colonne JSON des dossiers :
        // Hibernate ne voit pas ce changement, et faute d'un autre champ modifié il
        // n'écrivait rien. Chaque réponse montrait le patient coché (en mémoire) mais rien
        // n'était enregistré : au clic suivant, la facture relue avait perdu la coche
        // précédente. Une nouvelle liste et la date de mise à jour forcent l'écriture.
        facture.setDossiers(new ArrayList<>(facture.getDossiers()));
        facture.setUpdatedAt(LocalDateTime.now());
        return depot.save(facture);
    }

    /**
     * Un service régional ne tranche que sur sa région — facture sans région figée rattrapée
     * par sa structure, compte sans région refusé ; les rôles nationaux ne sont pas limités.
     */
    private FactureStructure chargerPourRegion(String id, UtilisateurAuthentifie utilisateur) {
        FactureStructure facture = depot.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Facture introuvable : " + id));
        verifierRegion(facture, utilisateur);
        return facture;
    }

    private static void exigerStatut(FactureStructure facture, StatutFacture attendu, String action) {
        if (facture.getStatut() != attendu) {
            throw ExceptionMetier.conflit("Seule une facture « " + attendu.getLibelle()
                    + " » peut être " + action + ". Celle-ci est « " + facture.getStatut().getLibelle() + " ».");
        }
    }


    private static HistoriqueDossier evenement(StatutFacture statut, UtilisateurAuthentifie utilisateur,
                                               String action) {
        return HistoriqueDossier.builder()
                .date(LocalDateTime.now())
                .statut(statut.name())
                .utilisateurId(utilisateur.getId())
                .utilisateurNom(utilisateur.nomComplet())
                .commentaire(action)
                .build();
    }

    // ══════════════════════════ documents (§7) ══════════════════════════

    public byte[] pdfFactureRecapitulative(String id) {
        FactureStructure facture = parId(id);
        return generateurPdf.factureRecapitulative(facture, entete(facture, "Facture récapitulative"));
    }

    public byte[] pdfListeNominative(String id) {
        FactureStructure facture = parId(id);
        return generateurPdf.listeNominative(facture, entete(facture, "Liste nominative"));
    }

    public String nomFichierFacture(String id, String suffixe, String extension) {
        FactureStructure facture = parId(id);
        return Documents.nomFichier(
                facture.getNumero() + "_" + suffixe + "_" + facture.periodeLibelle(), extension);
    }

    /** Attestation de soins d'un patient : pièce du dossier patient, quel que soit le régime. */
    public byte[] pdfAttestationSoins(String factureId, String dossierId) {
        FactureStructure facture = parId(factureId);
        DossierPatient dossier = dossierDe(facture, dossierId);
        return generateurPdf.attestationSoins(facture, dossier, lignesDu(facture, dossierId),
                entete(facture, "Attestation de soins"));
    }

    /**
     * Fiche de l'assuré : ce que le contrôle d'éligibilité a retourné. N'a de sens que pour
     * un régime à carte ; les autres s'identifient par une pièce photographiée.
     */
    public byte[] pdfFicheAssure(String factureId, String dossierId) {
        FactureStructure facture = parId(factureId);
        DossierPatient dossier = dossierDe(facture, dossierId);
        if (facture.getRegime() == null || !facture.getRegime().exigeCarteCsu()) {
            throw ExceptionMetier.invalide("La fiche assuré n'existe que pour les régimes identifiés par carte CSU.");
        }
        EnteteDocument entete = entete(facture, "Fiche de l'assuré");
        entete.setNumero(null);
        String photo = depotPieces.findFirstByDossierIdAndType(dossierId, PieceDossier.PHOTO_ASSURE)
                .map(PieceDossier::getImage)
                .orElse(null);
        return generateurPdf.ficheAssure(facture, dossier, photo, entete);
    }

    public String nomFichierDossier(String factureId, String dossierId, String suffixe) {
        FactureStructure facture = parId(factureId);
        DossierPatient dossier = dossierDe(facture, dossierId);
        return Documents.nomFichier(suffixe + "_" + dossier.nomComplet() + "_" + facture.periodeLibelle(), "pdf");
    }

    protected EnteteDocument entete(FactureStructure facture, String titre) {
        return EnteteDocument.builder()
                .titre(titre)
                .numero(facture.getNumero())
                .structureNom(facture.getStructureNom())
                .typeStructureLibelle(facture.getTypeStructure() == null ? null
                        : facture.getTypeStructure().getLibelle())
                .districtNom(facture.getDistrictNom())
                .periode(facture.periodeLibelle())
                .regimeLibelle(facture.getRegime() == null ? null : facture.getRegime().getLibelle())
                .build();
    }

    /** Période décroissante puis structure : l'ordre attendu dans « Mes factures ». */
    protected Comparator<FactureStructure> comparateurPeriode() {
        return Comparator.<FactureStructure>comparingInt(FactureStructure::getAnnee).reversed()
                .thenComparing(Comparator.<FactureStructure>comparingInt(FactureStructure::getMois).reversed())
                .thenComparing(f -> f.getStructureNom() == null ? "" : f.getStructureNom());
    }
}
