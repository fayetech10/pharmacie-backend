package com.csu.pharmacie.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {

    /** Longueur minimale du secret pour HS256 (256 bits = 32 octets). */
    private static final int MIN_SECRET_LENGTH = 32;

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration}")
    private long jwtExpiration;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtUtil.class);

    /**
     * Secret absent → génération d'un secret éphémère aléatoire (dev local) : aucun
     * secret prévisible ne doit exister dans le dépôt. Les tokens émis deviennent
     * invalides à chaque redémarrage, d'où l'avertissement. Secret fourni mais trop
     * court → échec immédiat du démarrage (déploiement mal configuré).
     */
    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[48];
            new java.security.SecureRandom().nextBytes(random);
            secret = java.util.Base64.getEncoder().encodeToString(random);
            log.warn("APP_JWT_SECRET non défini : secret JWT ÉPHÉMÈRE généré pour cette exécution. "
                    + "Les sessions seront invalidées au prochain redémarrage. "
                    + "En production, définissez APP_JWT_SECRET (>= {} caractères aléatoires).", MIN_SECRET_LENGTH);
            return;
        }
        if (secret.trim().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "APP_JWT_SECRET trop court (>= " + MIN_SECRET_LENGTH
                            + " caractères requis). Définissez une variable d'environnement APP_JWT_SECRET robuste.");
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        return createToken(claims, userDetails.getUsername());
    }

    private String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }
}
