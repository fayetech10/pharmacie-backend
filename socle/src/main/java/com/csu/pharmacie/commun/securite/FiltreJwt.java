package com.csu.pharmacie.commun.securite;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import com.csu.pharmacie.entity.Role;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Authentifie chaque requête à partir du seul en-tête {@code Authorization: Bearer …}.
 *
 * <p>Aucun accès base : la validation est locale (signature + expiration), l'identité vient
 * des claims. Deux autorités sont posées — le rôle déclaré et le rôle effectif — afin que
 * les comptes historiques {@code STRUCTURE_SANITAIRE} continuent de fonctionner tout en
 * bénéficiant des règles écrites pour {@code POSTE_SANTE} / {@code CENTRE_SANTE}.</p>
 */
@RequiredArgsConstructor
public class FiltreJwt extends OncePerRequestFilter {

    private final JetonService jetonService;

    /**
     * Seuls chemins ouverts à un compte dont le mot de passe n'a pas encore été choisi :
     * de quoi se connecter, lire son propre contexte, et régulariser. Tout le reste est
     * refusé — sans quoi l'obligation ne vaudrait que pour l'interface Angular, qu'un
     * simple appel direct à l'API contournerait.
     */
    private static final List<String> CHEMINS_AUTORISES_SANS_REGULARISATION = List.of(
            "/api/auth/login",
            "/api/auth/moi",
            "/api/auth/changer-mot-de-passe",
            "/api/auth/change-password",
            "/api/health");

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse, FilterChain chaine)
            throws ServletException, IOException {

        String entete = requete.getHeader("Authorization");
        if (entete == null || !entete.startsWith("Bearer ")) {
            chaine.doFilter(requete, reponse);
            return;
        }

        try {
            UtilisateurAuthentifie utilisateur = jetonService.lire(entete.substring(7));

            if (utilisateur.isMotDePasseAChanger() && !cheminAutorise(requete)) {
                refuser(reponse, HttpServletResponse.SC_FORBIDDEN,
                        "Mot de passe provisoire : choisissez votre mot de passe avant d'utiliser l'application");
                return;
            }

            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                var authentification = new UsernamePasswordAuthenticationToken(
                        utilisateur, null, autorites(utilisateur));
                authentification.setDetails(new WebAuthenticationDetailsSource().buildDetails(requete));
                SecurityContextHolder.getContext().setAuthentication(authentification);
            }
        } catch (ExpiredJwtException e) {
            refuser(reponse, HttpServletResponse.SC_UNAUTHORIZED, "Session expirée");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            refuser(reponse, HttpServletResponse.SC_UNAUTHORIZED, "Jeton invalide");
            return;
        }

        chaine.doFilter(requete, reponse);
    }

    private static List<SimpleGrantedAuthority> autorites(UtilisateurAuthentifie utilisateur) {
        List<SimpleGrantedAuthority> autorites = new ArrayList<>(2);
        Role declare = utilisateur.getRole();
        if (declare == null) {
            return autorites;
        }
        autorites.add(new SimpleGrantedAuthority("ROLE_" + declare.name()));
        Role effectif = utilisateur.roleEffectif();
        if (effectif != declare) {
            autorites.add(new SimpleGrantedAuthority("ROLE_" + effectif.name()));
        }
        // Centre siège du district : double casquette — les règles « DISTRICT » s'appliquent
        // aussi à lui (le périmètre reste son district, revérifié en service).
        if (utilisateur.aCapaciteDistrict() && declare != Role.DISTRICT) {
            autorites.add(new SimpleGrantedAuthority("ROLE_" + Role.DISTRICT.name()));
        }
        return autorites;
    }

    /**
     * La passerelle transmet le chemin d'origine : {@code getRequestURI()} porte donc bien
     * {@code /api/…} aussi bien en appel direct qu'à travers elle.
     */
    private static boolean cheminAutorise(HttpServletRequest requete) {
        String chemin = requete.getRequestURI();
        return CHEMINS_AUTORISES_SANS_REGULARISATION.stream().anyMatch(chemin::startsWith);
    }

    /** Réponse JSON homogène avec {@code GestionnaireErreurs} (le front lit {@code message}). */
    private static void refuser(HttpServletResponse reponse, int statut, String message) throws IOException {
        reponse.setStatus(statut);
        reponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        reponse.setCharacterEncoding("UTF-8");
        reponse.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
