package com.csu.pharmacie.passerelle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.handler.predicate.AbstractRoutePredicateFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Prédicat de route sur le rôle porté par le jeton : {@code - Role=POSTE_SANTE}.
 *
 * <p>C'est ce qui permet de conserver l'URL historique {@code /api/factures-structure}
 * tout en l'aiguillant vers deux services distincts. Sans cela il aurait fallu changer
 * les URLs appelées par le dashboard, donc modifier des écrans qui fonctionnent.</p>
 *
 * <p>Le jeton est seulement <em>décodé</em> ici, jamais validé : la passerelle n'a pas le
 * secret et n'a pas à l'avoir. Un jeton forgé permettrait au mieux de choisir le service
 * destinataire — lequel vérifie la signature et rejette la requête. L'aiguillage n'est
 * pas un contrôle d'accès.</p>
 *
 * <p><b>Nom de classe imposé</b> : Spring Cloud Gateway dérive le nom du prédicat
 * (« Role ») en retirant le suffixe {@code RoutePredicateFactory}. Renommer cette classe
 * casserait la configuration YAML au démarrage.</p>
 */
@Component
public class RoleRoutePredicateFactory extends AbstractRoutePredicateFactory<RoleRoutePredicateFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(RoleRoutePredicateFactory.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Rôle générique historique : il se résout via le type de structure. */
    private static final String ROLE_GENERIQUE = "STRUCTURE_SANITAIRE";

    public RoleRoutePredicateFactory() {
        super(Config.class);
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("roles");
    }

    @Override
    public Predicate<ServerWebExchange> apply(Config config) {
        Set<String> attendus = Set.of(config.getRoles().split(","));
        return exchange -> {
            String role = roleEffectif(exchange.getRequest().getHeaders().getFirst("Authorization"));
            return role != null && attendus.contains(role);
        };
    }

    /**
     * Rôle effectif de l'appelant, ou {@code null} si le jeton est absent ou illisible.
     * Un compte {@code STRUCTURE_SANITAIRE} est résolu en {@code POSTE_SANTE} ou
     * {@code CENTRE_SANTE} selon le type de sa structure, exactement comme côté services.
     */
    private static String roleEffectif(String entete) {
        if (entete == null || !entete.startsWith("Bearer ")) {
            return null;
        }
        String[] parties = entete.substring(7).split("\\.");
        if (parties.length < 2) {
            return null;
        }
        try {
            byte[] charge = Base64.getUrlDecoder().decode(parties[1]);
            JsonNode claims = JSON.readTree(new String(charge, StandardCharsets.UTF_8));
            String role = texte(claims, "role");
            if (role == null) {
                return null;
            }
            if (!ROLE_GENERIQUE.equals(role)) {
                return role;
            }
            return "PS".equals(texte(claims, "typeStructure")) ? "POSTE_SANTE" : "CENTRE_SANTE";
        } catch (Exception e) {
            // Jeton illisible : aucune route à rôle ne correspond, la requête suivra la
            // route par défaut et sera rejetée par le service destinataire.
            log.debug("Jeton non décodable pour l'aiguillage : {}", e.getMessage());
            return null;
        }
    }

    private static String texte(JsonNode claims, String champ) {
        JsonNode valeur = claims.get(champ);
        return valeur == null || valeur.isNull() ? null : valeur.asText();
    }

    @Data
    public static class Config {
        /** Rôles acceptés, séparés par des virgules. */
        private String roles = "";
    }
}
