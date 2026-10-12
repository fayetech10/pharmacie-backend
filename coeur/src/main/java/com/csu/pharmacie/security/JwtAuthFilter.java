package com.csu.pharmacie.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.csu.pharmacie.repository.UserRepository;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;
    private final UserRepository userRepository;

    /**
     * Seuls chemins ouverts à un compte dont le mot de passe n'a pas encore été choisi.
     *
     * <p>Le cœur applique la même règle que les microservices, mais il la vérifie en base
     * plutôt que dans le jeton : il accepte aussi bien ses propres jetons que ceux de
     * service-identite, et un jeton émis avant la régularisation ne doit pas rouvrir
     * l'application pendant ses 24 heures de validité.</p>
     */
    private static final List<String> CHEMINS_AUTORISES_SANS_REGULARISATION = List.of(
            "/api/auth/login",
            "/api/auth/change-password",
            "/api/health");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String userEmail;

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            jwt = authHeader.substring(7);
            userEmail = jwtUtil.extractUsername(jwt);

            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);

                if (motDePasseAChanger(userEmail) && !cheminAutorise(request)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"message\":\"Mot de passe provisoire :"
                            + " choisissez votre mot de passe avant d'utiliser l'application\"}");
                    return;
                }

                if (jwtUtil.validateToken(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (ExpiredJwtException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Session expirée");
            return;
        } catch (JwtException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Token invalide");
            return;
        }
        
        filterChain.doFilter(request, response);
    }

    private boolean motDePasseAChanger(String email) {
        return userRepository.findByEmail(email)
                .map(utilisateur -> Boolean.TRUE.equals(utilisateur.getMustChangePassword()))
                .orElse(false);
    }

    private static boolean cheminAutorise(HttpServletRequest requete) {
        String chemin = requete.getRequestURI();
        return CHEMINS_AUTORISES_SANS_REGULARISATION.stream().anyMatch(chemin::startsWith);
    }
}
