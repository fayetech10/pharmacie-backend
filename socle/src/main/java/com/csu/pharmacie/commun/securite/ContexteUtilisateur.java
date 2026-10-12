package com.csu.pharmacie.commun.securite;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.commun.web.ExceptionMetier;

import java.util.Arrays;

/**
 * Accès à l'utilisateur courant depuis les couches service, sans avoir à faire
 * remonter un paramètre {@code Principal} dans toutes les signatures.
 *
 * <p>Les contrôles fins (« ce poste peut-il toucher cette facture ? ») se font ici et
 * dans les services, jamais uniquement dans la configuration de sécurité : les règles
 * de chemin ne savent pas à quelle structure appartient une ressource.</p>
 */
public final class ContexteUtilisateur {

    private ContexteUtilisateur() {
    }

    /** Utilisateur courant, ou {@code null} si la requête n'est pas authentifiée. */
    public static UtilisateurAuthentifie courantOuNull() {
        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        if (authentification == null || !(authentification.getPrincipal() instanceof UtilisateurAuthentifie u)) {
            return null;
        }
        return u;
    }

    /** Utilisateur courant — lève une 401 si la requête n'est pas authentifiée. */
    public static UtilisateurAuthentifie courant() {
        UtilisateurAuthentifie utilisateur = courantOuNull();
        if (utilisateur == null) {
            throw ExceptionMetier.nonAuthentifie("Authentification requise");
        }
        return utilisateur;
    }

    /** Exige l'un des rôles indiqués (le rôle effectif est accepté), sinon 403. */
    public static UtilisateurAuthentifie exigerRole(Role... roles) {
        UtilisateurAuthentifie utilisateur = courant();
        Role declare = utilisateur.getRole();
        Role effectif = utilisateur.roleEffectif();
        boolean autorise = Arrays.stream(roles).anyMatch(r -> r == declare || r == effectif);
        if (!autorise) {
            throw ExceptionMetier.interdit("Accès réservé aux rôles " + Arrays.toString(roles));
        }
        return utilisateur;
    }

    /**
     * Exige que l'utilisateur soit rattaché à une structure et renvoie son identifiant.
     * Un compte de structure sans {@code structureId} est une donnée incohérente : mieux
     * vaut une erreur explicite qu'une requête qui renverrait silencieusement tout.
     */
    public static String structureCourante() {
        UtilisateurAuthentifie utilisateur = courant();
        if (utilisateur.getStructureId() == null || utilisateur.getStructureId().isBlank()) {
            throw ExceptionMetier.interdit("Votre compte n'est rattaché à aucune structure sanitaire");
        }
        return utilisateur.getStructureId();
    }
}
