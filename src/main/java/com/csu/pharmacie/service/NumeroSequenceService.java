package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.CompteurNumero;
import com.csu.pharmacie.entity.Region;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.repository.CompteurNumeroRepository;
import com.csu.pharmacie.repository.RegionRepository;
import com.csu.pharmacie.repository.StructureSanitaireRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Numérotation codifiée, propre à chaque structure sanitaire.
 *
 * Code structure (généré à la création) : {REGION}{TYPE}{NN} — ex: DKCS01.
 * Les initiales de la région (code Region : DK, TH…) distinguent deux structures de même
 * type dans des régions différentes ; le type porte l'établissement (CS = centre de santé,
 * EPS = hôpital…) ; NN est une séquence par région+type.
 *
 * Numéro de document : {TYPE_DOC}-{INITIALES}-{LETTRES}{4 chiffres} — ex: G-DKCS-0001, G-DKCS-A0001.
 * Les INITIALES = code structure SANS son numéro de séquence (DKCS01 -> DKCS = région + type).
 * La SEQUENCE qui suit est COMMUNE à toutes les structures d'un même (région, type) et
 * PERMANENTE (jamais remise à zéro) : un bloc de 4 chiffres (0001→9999) puis, quand il boucle,
 * un préfixe de lettres MAJUSCULES placé AVANT les chiffres, qui s'incrémente (A→Z, AA→ZZ) :
 * 0001…9999, A0001…Z9999, AA0001…ZZ9999 (2 lettres ≈ 7 millions), extensible au-delà (AAA…).
 */
@Service
@RequiredArgsConstructor
public class NumeroSequenceService {

    /** Code utilisé quand l'agent n'est rattaché à aucune structure (ex: ADMIN). */
    public static final String CODE_STRUCTURE_DEFAUT = "CSU";
    /** Initiales utilisées quand la structure n'a pas de région renseignée. */
    public static final String CODE_REGION_DEFAUT = "SN";
    /** Type d'établissement par défaut (centre de santé). */
    public static final String TYPE_STRUCTURE_DEFAUT = "CS";
    /** Taille d'un bloc de chiffres (0001..9999) avant d'incrémenter le préfixe de lettres. */
    private static final int TAILLE_BLOC = 9999;

    private final CompteurNumeroRepository compteurNumeroRepository;
    private final StructureSanitaireRepository structureSanitaireRepository;
    private final RegionRepository regionRepository;

    /**
     * Structure sanitaire de rattachement d'un agent : celle de son compte
     * (User.structureSanitaireId), sinon la première structure dont il est le BCSU référent.
     */
    public Optional<StructureSanitaire> structureDeLAgent(User agent) {
        if (agent.getStructureSanitaireId() != null) {
            Optional<StructureSanitaire> structure =
                    structureSanitaireRepository.findById(agent.getStructureSanitaireId());
            if (structure.isPresent()) {
                return structure;
            }
        }
        return structureSanitaireRepository.findByBcsuId(agent.getId()).stream().findFirst();
    }

    /**
     * Code de la prochaine structure d'une région et d'un type donnés (ex: DKCS01, DKEPS01).
     * La séquence est permanente (pas de remise à zéro annuelle) : un code identifie la
     * structure pour toujours.
     */
    @Transactional
    public String prochainCodeStructure(String regionId, String typeStructure) {
        String region = CODE_REGION_DEFAUT;
        if (regionId != null) {
            region = normaliser(regionRepository.findById(regionId)
                    .map(Region::getCode).orElse(null), CODE_REGION_DEFAUT);
        }
        String type = normaliser(typeStructure, TYPE_STRUCTURE_DEFAUT);
        long valeur = incrementer("ST:" + region + ":" + type);
        return String.format("%s%s%02d", region, type, valeur);
    }

    /**
     * Prochain numéro codifié SÉQUENTIEL et PERMANENT pour un type de document
     * ("G" = lettre de garantie, "C" = bon de commande…) :
     * {TYPE_DOC}-{INITIALES}-{LETTRES}{4 chiffres} — ex: G-DKCS-0001, puis G-DKCS-A0001.
     *
     * Les INITIALES = code de la structure SANS son numéro de séquence (DKCS01 -> DKCS),
     * soit région + type. Le compteur est COMMUN à toutes les structures d'un même couple
     * (région, type), JAMAIS remis à zéro : voir {@link #sequenceLettresChiffres(long)}.
     */
    @Transactional
    public String prochainNumeroCodifie(String typeDocument, User agent) {
        String codeStructure = structureDeLAgent(agent)
                .map(StructureSanitaire::getCode).orElse(null);
        String code = normaliser(codeStructure, CODE_STRUCTURE_DEFAUT);
        // On retire le numéro de séquence de la structure (DKCS01 -> DKCS) : on ne garde
        // que les initiales région + type. Choix explicite : compteur COMMUN à toutes les
        // structures de ce couple (numéros courts, sans le n° de structure dans le numéro).
        String initiales = code.replaceAll("\\d+$", "");
        if (initiales.isEmpty()) {
            initiales = code;
        }
        long valeur = incrementer(typeDocument + ":" + initiales);
        return String.format("%s-%s-%s", typeDocument, initiales, sequenceLettresChiffres(valeur));
    }

    /**
     * Représente la valeur du compteur (1, 2, 3…) sous la forme {LETTRES}{4 chiffres} :
     * le bloc de 4 chiffres tourne 0001→9999, puis un préfixe de lettres MAJUSCULES
     * (placé AVANT les chiffres) s'incrémente en repartant de A.
     * Progression : 0001…9999, A0001…A9999, B0001…, Z9999, AA0001…, jusqu'à ZZ9999
     * (2 lettres ≈ 7 millions de numéros par région+type). Au-delà, le préfixe s'étend
     * naturellement (AAA…) sans jamais bloquer la génération.
     */
    private String sequenceLettresChiffres(long valeur) {
        long index = valeur - 1;                          // base 0
        int chiffres = (int) (index % TAILLE_BLOC) + 1;   // 1..9999
        long bloc = index / TAILLE_BLOC;                  // 0 = aucune lettre, 1 = A, 27 = AA…
        return prefixeLettres(bloc) + String.format("%04d", chiffres);
    }

    /** Préfixe de lettres en base 26 bijective (majuscules) : 0→"", 1→"A", 26→"Z", 27→"AA"… */
    private String prefixeLettres(long bloc) {
        StringBuilder sb = new StringBuilder();
        while (bloc > 0) {
            bloc--;
            sb.insert(0, (char) ('A' + (int) (bloc % 26)));
            bloc /= 26;
        }
        return sb.toString();
    }

    /** Incrémente (sous verrou pessimiste) et retourne la valeur du compteur identifié par la clé. */
    private long incrementer(String cle) {
        CompteurNumero compteur = compteurNumeroRepository.findByCleForUpdate(cle)
                .orElseGet(() -> compteurNumeroRepository.saveAndFlush(new CompteurNumero(cle, 0L)));
        compteur.setValeur(compteur.getValeur() + 1);
        compteurNumeroRepository.save(compteur);
        return compteur.getValeur();
    }

    /** Code en majuscules, alphanumérique strict (les tirets/espaces casseraient le format du numéro). */
    private String normaliser(String code, String defaut) {
        if (code == null) {
            return defaut;
        }
        String normalise = code.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        return normalise.isEmpty() ? defaut : normalise;
    }
}
