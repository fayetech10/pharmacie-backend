package com.csu.pharmacie.utils;

import com.csu.pharmacie.entity.DossierPatient;
import com.csu.pharmacie.entity.Regime;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Clé de rapprochement d'une ligne de facture d'un centre de santé ou d'un EPS, entre la
 * saisie du BCSU et le fichier que la structure importe en fin de mois (spécification
 * « Facturation CS / EPS », §3 et §4).
 *
 * <p>Chaque régime a son identifiant : le n° d'immatriculation pour les régimes à carte
 * (Classique, BSF, CEC), la CNI pour le Plan Sésame et la césarienne, la CNI de
 * l'accompagnant pour les enfants de moins de 5 ans. Une ligne est la <b>visite</b> d'un
 * patient : cet identifiant et la date de prise en charge — et, pour les enfants, l'enfant
 * lui-même, une mère pouvant en amener deux le même jour (arbitrage du 12/10/2026).</p>
 *
 * <p>Dans {@code commun} et non dans un service : le cœur (saisie BCSU) et le centre de
 * santé (import) doivent produire exactement la même clé.</p>
 */
public final class CleRapprochement {

    /** Régimes du module : les autres gardent la facturation sur lettre de garantie. */
    private static final Set<Regime> REGIMES = EnumSet.of(Regime.CONTRIBUTIF, Regime.BSF, Regime.CEC,
            Regime.SESAME, Regime.CESARIENNE, Regime.ZERO_CINQ_ANS);

    private CleRapprochement() {
    }

    public static boolean regimeConcerne(Regime regime) {
        return regime != null && REGIMES.contains(regime);
    }

    /**
     * Identifiant en majuscules, sans espaces ni séparateurs : une CNI tapée d'un bloc ou
     * par groupes de chiffres donne la même clé. {@code null} s'il ne reste rien.
     */
    public static String normaliser(String valeur) {
        if (valeur == null) {
            return null;
        }
        String normalise = valeur.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return normalise.isEmpty() ? null : normalise;
    }

    /** Identifiant attendu pour le régime, tel qu'il se nomme dans les messages. */
    public static String libelleIdentifiant(Regime regime) {
        if (regime == null) {
            return "identifiant";
        }
        return switch (regime) {
            case ZERO_CINQ_ANS -> "n° de CNI de l'accompagnant";
            case SESAME, CESARIENNE -> "n° de CNI";
            default -> "n° d'immatriculation";
        };
    }

    /** Identifiant du régime, normalisé ; {@code null} s'il manque ou si le régime n'est pas du module. */
    public static String identifiant(Regime regime, String immatriculation, String cni, String cniAccompagnant) {
        if (regime == null) {
            return null;
        }
        return switch (regime) {
            case CONTRIBUTIF, BSF, CEC -> normaliser(immatriculation);
            case SESAME, CESARIENNE -> normaliser(cni);
            case ZERO_CINQ_ANS -> normaliser(cniAccompagnant);
            default -> null;
        };
    }

    /**
     * Identifiant (normalisé) au format attendu ? Une CNI ne compte que des chiffres ; un n°
     * d'immatriculation, des lettres et des chiffres. Au moins 5 caractères dans les deux cas :
     * en deçà, c'est une saisie tronquée.
     */
    public static boolean formatValide(Regime regime, String cle) {
        if (cle == null || cle.length() < 5) {
            return false;
        }
        return switch (regime) {
            case SESAME, CESARIENNE, ZERO_CINQ_ANS -> cle.chars().allMatch(Character::isDigit);
            default -> true;
        };
    }

    /**
     * La ligne d'un fichier peut-elle désigner l'enfant de ce dossier ? Plus tolérant que
     * {@link #memeEnfant}, fait pour refuser un doublon à la saisie : ici, une information
     * absente d'un côté ne départage pas — elle n'exclut pas non plus. Le n° d'extrait, quand
     * les deux l'ont, décide ; sinon la date de naissance et le prénom, s'ils sont connus des deux.
     */
    public static boolean enfantCompatible(String extrait, LocalDate naissance, String prenom, DossierPatient dossier) {
        String a = normaliser(extrait);
        String b = normaliser(dossier.getMatricule());
        if (a != null && b != null) {
            return a.equals(b);
        }
        if (naissance != null && dossier.getDateNaissance() != null && !naissance.equals(dossier.getDateNaissance())) {
            return false;
        }
        String prenomA = normaliserNom(prenom);
        String prenomB = normaliserNom(dossier.getPrenom());
        return prenomA == null || prenomB == null || prenomA.equals(prenomB);
    }

    /** Clé d'un dossier : celle qu'il porte, à défaut celle de ses identifiants. */
    public static String cleDe(Regime regime, DossierPatient dossier) {
        if (dossier.getCleRapprochement() != null && !dossier.getCleRapprochement().isBlank()) {
            return dossier.getCleRapprochement();
        }
        return identifiant(regime, dossier.getMatricule(), dossier.getNumeroCni(), dossier.getCniAccompagnant());
    }

    /**
     * Deux dossiers sont-ils la même visite ? Même clé, même date de prise en charge et,
     * pour les enfants, même enfant. Un dossier sans clé ou sans date n'est jamais rapproché.
     */
    public static boolean memeVisite(Regime regime, DossierPatient a, DossierPatient b) {
        String cle = cleDe(regime, a);
        if (cle == null || a.getDatePriseEnCharge() == null
                || !cle.equals(cleDe(regime, b)) || !a.getDatePriseEnCharge().equals(b.getDatePriseEnCharge())) {
            return false;
        }
        return regime != Regime.ZERO_CINQ_ANS || memeEnfant(a.getMatricule(), a.getDateNaissance(), a.getPrenom(),
                b.getMatricule(), b.getDateNaissance(), b.getPrenom());
    }

    /**
     * Même enfant ? Quand les deux n° d'extrait de naissance sont connus, ils décident. Sinon,
     * la date de naissance et le prénom : des jumeaux partagent la première, pas le second.
     */
    public static boolean memeEnfant(String extraitA, LocalDate naissanceA, String prenomA,
                                     String extraitB, LocalDate naissanceB, String prenomB) {
        String a = normaliser(extraitA);
        String b = normaliser(extraitB);
        if (a != null && b != null) {
            return a.equals(b);
        }
        return naissanceA != null && naissanceA.equals(naissanceB)
                && Objects.equals(normaliserNom(prenomA), normaliserNom(prenomB));
    }

    /** Prénom comparable : sans accents ni casse, lettres seules (« Aïssatou » = « AISSATOU »). */
    private static String normaliserNom(String nom) {
        if (nom == null) {
            return null;
        }
        String sansAccents = Normalizer.normalize(nom, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String normalise = sansAccents.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z]", "");
        return normalise.isEmpty() ? null : normalise;
    }
}
