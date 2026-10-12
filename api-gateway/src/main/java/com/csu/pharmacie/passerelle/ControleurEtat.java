package com.csu.pharmacie.passerelle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * État de la plateforme : quels microservices répondent, lesquels sont arrêtés.
 *
 * <p>C'est la première chose qu'on veut savoir quand quelque chose ne marche pas dans une
 * architecture distribuée. Sans cette page, diagnostiquer « le tableau de bord est vide »
 * suppose d'aller pinger cinq ports à la main.</p>
 */
@RestController
@RequestMapping("/api")
public class ControleurEtat {

    /** Court : cette page doit répondre même quand trois services sur cinq sont morts. */
    private static final Duration DELAI = Duration.ofSeconds(2);

    private final WebClient webClient = WebClient.builder().build();

    @Value("${services.identite-url:http://localhost:8081}")
    private String identite;
    @Value("${services.referentiel-url:http://localhost:8082}")
    private String referentiel;
    @Value("${services.pharmacie-url:http://localhost:8083}")
    private String pharmacie;
    @Value("${services.poste-sante-url:http://localhost:8084}")
    private String posteSante;
    @Value("${services.centre-sante-url:http://localhost:8085}")
    private String centreSante;

    @GetMapping("/health")
    public Mono<Map<String, Object>> sante() {
        Map<String, String> cibles = new LinkedHashMap<>();
        cibles.put("identite", identite);
        cibles.put("referentiel", referentiel);
        cibles.put("pharmacie", pharmacie);
        cibles.put("poste-sante", posteSante);
        cibles.put("centre-sante", centreSante);

        return Flux.fromIterable(cibles.entrySet())
                .flatMap(cible -> interroger(cible.getKey(), cible.getValue()))
                .collectList()
                .map(etats -> {
                    Map<String, Object> services = new LinkedHashMap<>();
                    etats.forEach(etat -> services.put(etat.nom(), etat.statut()));
                    Map<String, Object> reponse = new LinkedHashMap<>();
                    reponse.put("service", "api-gateway");
                    reponse.put("statut", "UP");
                    reponse.put("services", services);
                    return reponse;
                });
    }

    private Mono<Etat> interroger(String nom, String url) {
        return webClient.get()
                .uri(url + "/api/health")
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(DELAI)
                .map(corps -> new Etat(nom, "UP"))
                .onErrorReturn(new Etat(nom, "INJOIGNABLE"));
    }

    private record Etat(String nom, String statut) {
    }
}
