package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.HistoriqueDossier;
import com.csu.pharmacie.entity.LettreGarantie;
import com.csu.pharmacie.entity.PieceDossier;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.StatutLettreGarantie;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.TypeStructure;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.BusinessException;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.repository.DistrictRepository;
import com.csu.pharmacie.repository.FactureStructureRepository;
import com.csu.pharmacie.repository.PieceDossierRepository;
import com.csu.pharmacie.utils.CleRapprochement;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Place la saisie du BCSU dans la facture mensuelle de sa structure — centres de santé et
 * EPS (spécification « Facturation CS / EPS », §1 et §5).
 *
 * <p>La facture d'un centre se remplit en deux temps : le BCSU enregistre ses patients au fil
 * du mois, la structure importe ses prestations en fin de mois. Chaque lettre de garantie
 * devient donc, dès sa création, un dossier « à compléter » de la facture du mois de sa date
 * de prise en charge : identité, clé de rapprochement, pièces photographiées. L'import
 * viendra compléter ces dossiers.</p>
 *
 * <p>Le cœur écrit directement dans {@code factures_structure}, la table que lit et écrit
 * service-centre-sante (base partagée, cf. ARCHITECTURE.md). La facture et son dossier y
 * prennent la forme que leur donne le socle : même numérotation, même contexte de structure,
 * mêmes champs. Les deux écrivains sont départagés par le verrou optimiste de la facture.</p>
 */
@Service
@RequiredArgsConstructor
public class FactureMensuelleBcsuService {

    private static final Logger log = LoggerFactory.getLogger(FactureMensuelleBcsuService.class);

    /** Préfixe des factures mensuelles des microservices ({@code FS-AAAAMM-NNNNN}). */
    private static final String PREFIXE_NUMERO = "FS";
    private static final int TENTATIVES_NUMERO = 5;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final FactureStructureRepository factureRepository;
    private final PieceDossierRepository pieceRepository;
    private final DistrictRepository districtRepository;
    private final CompteurFactureService compteur;

    /**
     * Mise en service : premier mois (format {@code AAAA-MM}) dont les saisies BCSU entrent
     * dans la facture de la structure. Les prises en charge antérieures gardent l'ancien
     * circuit (la structure reprend la lettre pour facturer). Vide = actif sans plancher.
     */
    @Value("${app.facturation.cs-eps.a-partir-de:}")
    private String aPartirDe;

    /** Dossier d'une lettre, dans la facture qui le porte. */
    private record Rattachement(FactureStructure facture, DossierPatient dossier) {
    }

    /**
     * La saisie relève-t-elle du module ? Centre de santé ou EPS, régime à clé de
     * rapprochement (Classique, BSF, CEC, Sésame, Césarienne, 0-5 ans), prise en charge à
     * partir du mois de mise en service.
     */
    public boolean concerne(StructureSanitaire structure, Regime regime, LocalDate datePriseEnCharge) {
        if (structure == null) {
            return false;
        }
        LocalDate date = datePriseEnCharge == null ? LocalDate.now() : datePriseEnCharge;
        return concerne(TypeStructure.depuisCode(structure.getTypeStructure()), regime, date.getMonthValue(), date.getYear());
    }

    /**
     * Même règle, pour une facture déjà ouverte : elle se complète par l'import de fin de mois
     * et c'est la structure qui la transmet — jamais la clôture mensuelle automatique
     * (arbitrage du 12/10/2026).
     */
    public boolean concerne(TypeStructure type, Regime regime, int mois, int annee) {
        if (type == null || !type.polarise() || !CleRapprochement.regimeConcerne(regime)) {
            return false;
        }
        YearMonth plancher = plancher();
        return plancher == null || !YearMonth.of(annee, mois).isBefore(plancher);
    }

    /**
     * Crée ou met à jour le dossier de la lettre dans la facture du mois de sa date de prise
     * en charge — après chaque création ou modification de la lettre. Une lettre qui change
     * de régime ou de mois change de facture ; une lettre qui sort du module (régime, date)
     * quitte la sienne. Renseigne sur la lettre la facture et le dossier qui la portent.
     *
     * @throws ConflictException la même visite (clé, date, enfant) est déjà dans la facture
     * @throws BusinessException la facture du mois est déjà transmise, ou la structure a déjà
     *                           saisi des prestations sur le dossier qu'il faudrait déplacer
     */
    @Transactional
    public void rattacher(LettreGarantie lettre, StructureSanitaire structure, User agent) {
        Rattachement actuel = rattachementDe(lettre);
        Regime regime = lettre.getRegime();
        LocalDate date = lettre.getDatePriseEnCharge() != null ? lettre.getDatePriseEnCharge() : LocalDate.now();

        if (lettre.getStatut() == StatutLettreGarantie.ANNULEE || !concerne(structure, regime, date)) {
            if (actuel != null) {
                retirer(actuel, lettre, agent, "modifiée");
            }
            return;
        }

        FactureStructure cible = factureDuMois(structure, regime, date.getMonthValue(), date.getYear(), agent);
        if (!cible.getStatut().estModifiable()) {
            throw new BusinessException("La facture « " + regime.getLibelle() + " » de " + cible.periodeLibelle()
                    + " est « " + cible.getStatut().getLibelle() + " » : un patient pris en charge le "
                    + date.format(DATE) + " ne peut plus y être ajouté.");
        }

        DossierPatient dossier = null;
        if (actuel != null) {
            if (actuel.facture() == cible || Objects.equals(actuel.facture().getId(), cible.getId())) {
                dossier = actuel.dossier();
            } else {
                // Régime ou mois changé : le patient suit sa lettre dans l'autre facture.
                retirer(actuel, lettre, agent, "déplacée");
            }
        }

        preparer(cible);
        LocalDateTime maintenant = LocalDateTime.now();
        boolean nouveau = dossier == null;
        if (nouveau) {
            dossier = DossierPatient.builder()
                    .id(UUID.randomUUID().toString())
                    .createdAt(maintenant)
                    .createdBy(agent.getId())
                    .build();
        }
        remplir(dossier, lettre, date);
        exigerVisiteUnique(cible, dossier);

        Map<String, String> photos = photosDe(lettre);
        List<String> pieces = new ArrayList<>(dossier.getPieces() == null ? List.of() : dossier.getPieces());
        pieces.removeIf(TYPES_BCSU::contains);
        pieces.addAll(photos.entrySet().stream().filter(e -> e.getValue() != null).map(Map.Entry::getKey).toList());
        dossier.setPieces(pieces);
        // Complet avec des prestations (l'import de la structure) et un ticket de caisse : la
        // même règle que la facturation (ServiceFacturation.statutDossier).
        boolean complet = nombreLignes(cible, dossier.getId()) > 0 && !dossier.attendTicketDeCaisse();
        dossier.setStatut(complet ? DossierPatient.COMPLET : DossierPatient.A_COMPLETER);
        dossier.setUpdatedAt(maintenant);

        if (nouveau) {
            cible.getDossiers().add(dossier);
            cible.tracer(trace(cible, agent, dossier, null, dossier.nomComplet(),
                    "Patient enregistré par le BCSU (lettre " + lettre.getNumero() + ", prise en charge du "
                            + date.format(DATE) + ") — prestations à compléter par la structure"));
        } else {
            cible.tracer(trace(cible, agent, dossier, null, null,
                    "Dossier mis à jour depuis la lettre de garantie " + lettre.getNumero()));
        }
        cible.setUpdatedAt(maintenant);
        FactureStructure enregistree = factureRepository.save(cible);

        // Après l'enregistrement : la pièce référence la facture, qui vient peut-être d'être créée.
        enregistrerPieces(enregistree.getId(), dossier.getId(), photos, agent);

        lettre.setFactureStructureId(enregistree.getId());
        lettre.setFactureStructureNumero(enregistree.getNumero());
        lettre.setDossierId(dossier.getId());
    }

    /**
     * Retire de sa facture le dossier d'une lettre annulée ou supprimée.
     *
     * @param action « annulée », « supprimée » : complète les messages
     * @throws BusinessException la facture est transmise, ou porte déjà des prestations pour ce patient
     */
    @Transactional
    public void detacher(LettreGarantie lettre, User agent, String action) {
        Rattachement actuel = rattachementDe(lettre);
        if (actuel != null) {
            retirer(actuel, lettre, agent, action);
        }
    }

    // ══════════════════════════ dossier ══════════════════════════

    /**
     * Pièces que le BCSU photographie : ce sont les siennes, qu'il remplace ou retire. Le ticket
     * de caisse est celui de la structure : la saisie du BCSU n'y touche jamais.
     */
    private static final List<String> TYPES_BCSU = List.of(PieceDossier.CARTE_ASSURE, PieceDossier.CNI_RECTO,
            PieceDossier.CNI_VERSO, PieceDossier.EXTRAIT_NAISSANCE);

    /** Identité, clé et lien de la lettre : la saisie du BCSU fait foi pour le bloc patient. */
    private static void remplir(DossierPatient dossier, LettreGarantie lettre, LocalDate date) {
        Regime regime = lettre.getRegime();
        boolean enfant = regime == Regime.ZERO_CINQ_ANS;
        dossier.setNom(texte(lettre.getPatientNom()));
        dossier.setPrenom(texte(lettre.getPatientPrenom()));
        dossier.setSexe(texte(lettre.getPatientSexe()));
        dossier.setDateNaissance(lettre.getPatientDateNaissance());
        dossier.setTelephone(texte(lettre.getPatientTelephone()));
        // Convention du dossier : le matricule d'un enfant est son n° d'extrait de naissance.
        dossier.setMatricule(enfant ? texte(lettre.getNumeroExtraitNaissance()) : texte(lettre.getPatientNumeroAssure()));
        dossier.setNumeroCni(enfant ? null : texte(lettre.getCniNumeroOcr()));
        dossier.setNomAccompagnant(enfant ? texte(lettre.getAccompagnantNom()) : null);
        dossier.setCniAccompagnant(enfant ? texte(lettre.getAccompagnantCni()) : null);
        dossier.setDatePriseEnCharge(date);
        dossier.setCleRapprochement(CleRapprochement.identifiant(regime, lettre.getPatientNumeroAssure(),
                lettre.getCniNumeroOcr(), lettre.getAccompagnantCni()));
        dossier.setLettreGarantieId(lettre.getId());
        dossier.setLettreGarantieNumero(lettre.getNumero());
        // La lettre fait foi : le contrôle d'éligibilité a été fait par le BCSU.
        dossier.setIdentiteVerrouillee(true);
        dossier.setVerificationCarte(lettre.getVerificationCarte());
    }

    /** Une visite ne fait qu'une ligne de facture : même clé, même date et, pour un enfant, même enfant. */
    private static void exigerVisiteUnique(FactureStructure facture, DossierPatient dossier) {
        Regime regime = facture.getRegime();
        for (DossierPatient autre : facture.getDossiers()) {
            if (Objects.equals(autre.getId(), dossier.getId()) || !CleRapprochement.memeVisite(regime, dossier, autre)) {
                continue;
            }
            throw new ConflictException("Visite déjà enregistrée : " + autre.nomComplet() + ", même "
                    + CleRapprochement.libelleIdentifiant(regime) + " et même date de prise en charge ("
                    + dossier.getDatePriseEnCharge().format(DATE) + ")"
                    + (autre.getLettreGarantieNumero() == null ? "" : ", lettre " + autre.getLettreGarantieNumero())
                    + ". Une visite ne fait qu'une ligne de facture : reprenez la lettre existante plutôt que d'en créer une seconde.");
        }
    }

    /**
     * Retire le dossier d'une lettre de sa facture, avec ses pièces — tant que la facture est
     * modifiable et que la structure n'y a saisi aucune prestation pour ce patient.
     */
    private void retirer(Rattachement rattachement, LettreGarantie lettre, User agent, String action) {
        FactureStructure facture = rattachement.facture();
        DossierPatient dossier = rattachement.dossier();
        if (!facture.getStatut().estModifiable()) {
            throw new BusinessException("Ce patient figure dans la facture " + facture.getNumero() + " ("
                    + facture.periodeLibelle() + "), déjà « " + facture.getStatut().getLibelle()
                    + " » : la lettre de garantie ne peut plus être " + action + ".");
        }
        long prestations = nombreLignes(facture, dossier.getId());
        if (prestations > 0) {
            throw new BusinessException("La structure a déjà saisi " + prestations + " prestation(s) pour ce patient "
                    + "dans la facture " + facture.getNumero() + " : elle doit les retirer avant que la lettre de "
                    + "garantie puisse être " + action + ".");
        }
        facture.getDossiers().removeIf(d -> Objects.equals(d.getId(), dossier.getId()));
        pieceRepository.deleteByDossierId(dossier.getId());
        facture.tracer(trace(facture, agent, dossier, dossier.nomComplet(), null,
                "Patient retiré de la facture : lettre de garantie " + lettre.getNumero() + " " + action));
        facture.setUpdatedAt(LocalDateTime.now());
        factureRepository.save(facture);

        lettre.setFactureStructureId(null);
        lettre.setFactureStructureNumero(null);
        lettre.setDossierId(null);
    }

    private Rattachement rattachementDe(LettreGarantie lettre) {
        if (lettre.getFactureStructureId() == null) {
            return null;
        }
        return factureRepository.findById(lettre.getFactureStructureId())
                .flatMap(facture -> (facture.getDossiers() == null ? List.<DossierPatient>of() : facture.getDossiers())
                        .stream()
                        .filter(d -> Objects.equals(d.getId(), lettre.getDossierId())
                                || (lettre.getId() != null && Objects.equals(d.getLettreGarantieId(), lettre.getId())))
                        .findFirst()
                        .map(d -> new Rattachement(facture, d)))
                .orElse(null);
    }

    private static long nombreLignes(FactureStructure facture, String dossierId) {
        return facture.getLignes() == null ? 0 : facture.getLignes().stream()
                .filter(l -> Objects.equals(l.getDossierId(), dossierId))
                .count();
    }

    // ══════════════════════════ pièces ══════════════════════════

    /** Photos de la lettre, par type de pièce du dossier (valeur nulle = pièce absente). */
    private static Map<String, String> photosDe(LettreGarantie lettre) {
        Map<String, String> photos = new LinkedHashMap<>();
        photos.put(PieceDossier.CARTE_ASSURE, texte(lettre.getCarteAssureRecto()));
        photos.put(PieceDossier.CNI_RECTO, texte(lettre.getCniRecto()));
        photos.put(PieceDossier.CNI_VERSO, texte(lettre.getCniVerso()));
        // Le « document libre » de la lettre n'est l'extrait de naissance que pour un enfant.
        photos.put(PieceDossier.EXTRAIT_NAISSANCE,
                lettre.getRegime() == Regime.ZERO_CINQ_ANS ? texte(lettre.getAutreDocument()) : null);
        return photos;
    }

    private void enregistrerPieces(String factureId, String dossierId, Map<String, String> photos, User agent) {
        photos.forEach((type, image) -> {
            if (image == null) {
                pieceRepository.deleteByDossierIdAndType(dossierId, type);
                return;
            }
            PieceDossier piece = pieceRepository.findFirstByDossierIdAndType(dossierId, type)
                    .orElseGet(() -> PieceDossier.builder().dossierId(dossierId).type(type).build());
            piece.setFactureId(factureId);
            piece.setImage(image);
            piece.setCreatedAt(LocalDateTime.now());
            piece.setCreatedBy(agent.getId());
            pieceRepository.save(piece);
        });
    }

    // ══════════════════════════ facture du mois ══════════════════════════

    /** Facture du mois pour ce régime — celle de la structure s'il y en a une, sinon une nouvelle (non enregistrée). */
    private FactureStructure factureDuMois(StructureSanitaire structure, Regime regime, int mois, int annee, User agent) {
        List<FactureStructure> existantes =
                factureRepository.findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(structure.getId(), regime, mois, annee);
        if (!existantes.isEmpty()) {
            return existantes.stream()
                    .filter(f -> f.getStatut() != null && f.getStatut().estModifiable())
                    .findFirst()
                    .orElse(existantes.get(0));
        }

        // Même contexte que le socle (appliquerContexteStructure) : la facture doit rester
        // lisible, et le district la retrouver, quelle que soit l'application qui l'a ouverte.
        FactureStructure facture = FactureStructure.builder()
                .numero(numeroter(annee, mois))
                .structureSanitaireId(structure.getId())
                .structureNom(structure.getNom())
                .typeStructure(TypeStructure.depuisCode(structure.getTypeStructure()))
                .structureRattachementId(structure.getStructureRattachementId())
                .districtId(structure.getDistrictId())
                .districtNom(structure.getDistrictId() == null ? null
                        : districtRepository.findById(structure.getDistrictId()).map(d -> d.getNom()).orElse(null))
                .regionId(structure.getRegionId())
                .bcsuId(structure.getBcsuId())
                .regime(regime)
                .mois(mois)
                .annee(annee)
                .statut(StatutFacture.BROUILLON)
                .lignes(new ArrayList<>())
                .dossiers(new ArrayList<>())
                .historique(new ArrayList<>())
                .documentsComplementaires(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(agent.getId())
                .build();
        facture.tracer(HistoriqueDossier.builder()
                .date(LocalDateTime.now())
                .statut(StatutFacture.BROUILLON.name())
                .utilisateurId(agent.getId())
                .utilisateurNom(nomComplet(agent))
                .commentaire("Création de la facture mensuelle (saisie BCSU)")
                .build());
        return facture;
    }

    /** Prochain numéro de la séquence partagée avec le socle ; un incrément concurrent est rejoué. */
    private String numeroter(int annee, int mois) {
        String cle = "%s-%d%02d".formatted(PREFIXE_NUMERO, annee, mois);
        for (int tentative = 1; ; tentative++) {
            try {
                return "%s-%05d".formatted(cle, compteur.incrementer(cle));
            } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
                if (tentative == TENTATIVES_NUMERO) {
                    throw new ConflictException("Numérotation de la facture momentanément indisponible : réessayez.");
                }
            }
        }
    }

    /** Listes JSON jamais nulles (factures anciennes, ou ouvertes par le cœur avant les dossiers). */
    private static void preparer(FactureStructure facture) {
        if (facture.getDossiers() == null) {
            facture.setDossiers(new ArrayList<>());
        }
        if (facture.getLignes() == null) {
            facture.setLignes(new ArrayList<>());
        }
        if (facture.getHistorique() == null) {
            facture.setHistorique(new ArrayList<>());
        }
    }

    // ══════════════════════════ utilitaires ══════════════════════════

    private static HistoriqueDossier trace(FactureStructure facture, User agent, DossierPatient dossier,
                                           String ancienne, String nouvelle, String commentaire) {
        return HistoriqueDossier.builder()
                .date(LocalDateTime.now())
                .statut(facture.getStatut() == null ? null : facture.getStatut().name())
                .utilisateurId(agent.getId())
                .utilisateurNom(nomComplet(agent))
                .commentaire(commentaire)
                .dossierId(dossier.getId())
                .champ("Patient")
                .ancienneValeur(ancienne)
                .nouvelleValeur(nouvelle)
                .build();
    }

    private static String nomComplet(User agent) {
        return ((agent.getPrenom() == null ? "" : agent.getPrenom()) + " "
                + (agent.getNom() == null ? "" : agent.getNom())).trim();
    }

    private static String texte(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }

    /** Plancher « AAAA-MM » ou {@code null} (vide, ou valeur illisible — alors on prévient). */
    private YearMonth plancher() {
        if (aPartirDe == null || aPartirDe.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(aPartirDe.trim());
        } catch (DateTimeParseException e) {
            log.warn("app.facturation.cs-eps.a-partir-de illisible (« {} », attendu AAAA-MM) : "
                    + "saisies BCSU placées dans la facture sans plancher.", aPartirDe);
            return null;
        }
    }
}
