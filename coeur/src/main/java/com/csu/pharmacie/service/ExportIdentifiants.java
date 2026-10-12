package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.User;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Colonnes « mot de passe » des exports d'identifiants (pharmacies, structures).
 *
 * <p>Un mot de passe n'est connu en clair qu'au moment où il est posé : la base n'en garde
 * que l'empreinte. Ces exports affichaient le générique pour tout compte « à changer », or
 * ce drapeau dit seulement qu'un tiers a posé le mot de passe — le générique, un mot de
 * passe saisi à la création, ou celui d'une réinitialisation. Seule la vérification de
 * l'empreinte établit qu'il s'agit encore du générique ; sinon on ne l'invente pas.</p>
 */
final class ExportIdentifiants {

    private ExportIdentifiants() {
    }

    /**
     * Identifiants des comptes dont le mot de passe provisoire est encore le générique.
     * BCrypt coûte plusieurs dizaines de millisecondes par vérification : elles sont faites
     * en parallèle, et seulement pour les comptes à régulariser.
     */
    static Set<String> auMotDePasseGenerique(Collection<User> comptes, PasswordEncoder encodeur) {
        return comptes.parallelStream()
                .filter(Objects::nonNull)
                .filter(compte -> Boolean.TRUE.equals(compte.getMustChangePassword()) && compte.getPassword() != null)
                .filter(compte -> encodeur.matches(PharmacieService.MOT_DE_PASSE_GENERIQUE, compte.getPassword()))
                .map(User::getId)
                .collect(Collectors.toSet());
    }

    static String motDePasse(User compte, Set<String> generiques) {
        if (compte == null) {
            return "(aucun compte)";
        }
        if (!Boolean.TRUE.equals(compte.getMustChangePassword())) {
            return "(personnalisé)";
        }
        return generiques.contains(compte.getId())
                ? PharmacieService.MOT_DE_PASSE_GENERIQUE
                : "(provisoire, communiqué à part)";
    }

    static String statut(User compte) {
        if (compte == null) {
            return "Pas de compte de connexion";
        }
        return Boolean.TRUE.equals(compte.getMustChangePassword())
                ? "À changer à la première connexion"
                : "Déjà changé";
    }
}
