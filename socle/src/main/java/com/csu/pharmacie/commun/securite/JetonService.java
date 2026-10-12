package com.csu.pharmacie.commun.securite;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.TypeStructure;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Émission et lecture des jetons JWT.
 *
 * <p>Seul service-identite <em>émet</em> des jetons ; tous les autres se contentent de les
 * <em>lire</em>. Le secret est donc partagé par l'ensemble des services (variable
 * {@code APP_JWT_SECRET}) : c'est ce qui permet une validation purement locale, sans
 * aller-retour réseau à chaque requête.</p>
 *
 * <p>En l'absence de secret, un secret éphémère est tiré au hasard : pratique pour lancer
 * un service isolé, mais les jetons ne sont alors valables que pour ce service et jusqu'au
 * prochain redémarrage. Un démarrage multi-services sans {@code APP_JWT_SECRET} est signalé
 * par un avertissement explicite.</p>
 */
public class JetonService {

    private static final Logger log = LoggerFactory.getLogger(JetonService.class);

    /** Longueur minimale du secret pour HS256 (256 bits = 32 octets). */
    private static final int LONGUEUR_MINIMALE_SECRET = 32;

    public static final String CLAIM_ID = "uid";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_NOM = "nom";
    public static final String CLAIM_PRENOM = "prenom";
    public static final String CLAIM_STRUCTURE = "structureId";
    public static final String CLAIM_STRUCTURE_NOM = "structureNom";
    public static final String CLAIM_TYPE_STRUCTURE = "typeStructure";
    public static final String CLAIM_RATTACHEMENT = "rattachementId";
    public static final String CLAIM_DISTRICT = "districtId";
    public static final String CLAIM_REGION = "regionId";
    public static final String CLAIM_PHARMACIE = "pharmacieId";
    /**
     * Compte dont le mot de passe a été posé par un tiers (création par un administrateur,
     * réinitialisation, création en masse) et qui n'a pas encore choisi le sien.
     * Porté par le jeton pour que chaque service tranche sans requête en base — voir
     * {@code FiltreJwt}, qui refuse tout sauf la régularisation.
     */
    public static final String CLAIM_MOT_DE_PASSE_A_CHANGER = "mcp";
    /** Centre de santé siège de son district : porte aussi les droits du district. */
    public static final String CLAIM_SIEGE_DISTRICT = "siegeDistrict";

    @Value("${app.jwt.secret:}")
    private String secret;

    @Value("${app.jwt.expiration:86400000}")
    private long dureeValiditeMs;

    @PostConstruct
    void verifierSecret() {
        if (secret == null || secret.isBlank()) {
            byte[] aleatoire = new byte[48];
            new SecureRandom().nextBytes(aleatoire);
            secret = Base64.getEncoder().encodeToString(aleatoire);
            log.warn("APP_JWT_SECRET non défini : secret JWT ÉPHÉMÈRE généré pour cette exécution. "
                    + "En architecture microservices ce secret DOIT être identique sur tous les services, "
                    + "sinon les jetons émis par service-identite seront rejetés partout ailleurs.");
            return;
        }
        if (secret.trim().length() < LONGUEUR_MINIMALE_SECRET) {
            throw new IllegalStateException("APP_JWT_SECRET trop court (" + LONGUEUR_MINIMALE_SECRET
                    + " caractères minimum requis pour HS256).");
        }
    }

    private SecretKey cle() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Durée de validité configurée, en millisecondes — exposée pour l'affichage côté client. */
    public long dureeValiditeMs() {
        return dureeValiditeMs;
    }

    /** Émet un jeton portant tout le contexte de l'utilisateur (réservé à service-identite). */
    public String emettre(UtilisateurAuthentifie utilisateur) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_ID, utilisateur.getId());
        claims.put(CLAIM_ROLE, utilisateur.getRole() == null ? null : utilisateur.getRole().name());
        claims.put(CLAIM_NOM, utilisateur.getNom());
        claims.put(CLAIM_PRENOM, utilisateur.getPrenom());
        claims.put(CLAIM_STRUCTURE, utilisateur.getStructureId());
        claims.put(CLAIM_STRUCTURE_NOM, utilisateur.getStructureNom());
        claims.put(CLAIM_TYPE_STRUCTURE,
                utilisateur.getTypeStructure() == null ? null : utilisateur.getTypeStructure().name());
        claims.put(CLAIM_RATTACHEMENT, utilisateur.getStructureRattachementId());
        claims.put(CLAIM_DISTRICT, utilisateur.getDistrictId());
        claims.put(CLAIM_REGION, utilisateur.getRegionId());
        claims.put(CLAIM_PHARMACIE, utilisateur.getPharmacieId());
        claims.put(CLAIM_MOT_DE_PASSE_A_CHANGER, utilisateur.isMotDePasseAChanger());
        claims.put(CLAIM_SIEGE_DISTRICT, utilisateur.isSiegeDistrict());

        long maintenant = System.currentTimeMillis();
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(utilisateur.getEmail())
                .setIssuedAt(new Date(maintenant))
                .setExpiration(new Date(maintenant + dureeValiditeMs))
                .signWith(cle(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Vérifie la signature et l'expiration, puis reconstruit l'identité.
     * Lève une {@code JwtException} (ou {@code ExpiredJwtException}) si le jeton est invalide.
     */
    public UtilisateurAuthentifie lire(String jeton) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(cle())
                .build()
                .parseClaimsJws(jeton)
                .getBody();

        return UtilisateurAuthentifie.builder()
                .id(claims.get(CLAIM_ID, String.class))
                .email(claims.getSubject())
                .nom(claims.get(CLAIM_NOM, String.class))
                .prenom(claims.get(CLAIM_PRENOM, String.class))
                .role(versEnum(Role.class, claims.get(CLAIM_ROLE, String.class)))
                .structureId(claims.get(CLAIM_STRUCTURE, String.class))
                .structureNom(claims.get(CLAIM_STRUCTURE_NOM, String.class))
                .typeStructure(versEnum(TypeStructure.class, claims.get(CLAIM_TYPE_STRUCTURE, String.class)))
                .structureRattachementId(claims.get(CLAIM_RATTACHEMENT, String.class))
                .districtId(claims.get(CLAIM_DISTRICT, String.class))
                .regionId(claims.get(CLAIM_REGION, String.class))
                .pharmacieId(claims.get(CLAIM_PHARMACIE, String.class))
                // Absent des jetons émis avant l'introduction du claim : on considère alors
                // le compte comme régularisé plutôt que de bloquer des sessions en cours.
                .motDePasseAChanger(Boolean.TRUE.equals(claims.get(CLAIM_MOT_DE_PASSE_A_CHANGER, Boolean.class)))
                // Absent des jetons antérieurs : pas de droits district (reconnexion requise).
                .siegeDistrict(Boolean.TRUE.equals(claims.get(CLAIM_SIEGE_DISTRICT, Boolean.class)))
                .build();
    }

    /** Un claim absent ou devenu invalide (enum renommé) ne doit pas faire échouer la lecture. */
    private static <T extends Enum<T>> T versEnum(Class<T> type, String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, valeur);
        } catch (IllegalArgumentException e) {
            log.warn("Claim JWT « {} » inconnu pour {} — ignoré.", valeur, type.getSimpleName());
            return null;
        }
    }
}
