package com.csu.pharmacie.poste.service;

import com.csu.pharmacie.commun.carte.ServiceCarteCsu;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Données entièrement fictives, calquées sur la forme des réponses de carte.sencsu.sn. */
class ServiceCarteCsuTest {

    private static final String REPONSE = """
            {
              "nom": "DIOP", "prenom": "Awa", "sexe": "FEMININ", "etat": "Active",
              "telephone": null, "typePiece": "CNI", "numeroPiece": "1234567890123",
              "dateNaissance": "1990-05-04T00:00:00.000Z",
              "dateDebutc": "2026-01-11T00:00:00.000Z", "dateFinc": "2027-01-11T00:00:00.000Z",
              "codeImmatriculation": "X0000Y0000",
              "titulaire": { "nom": "DIOP", "prenom": "Moussa", "telephone": "770000000" },
              "typeBeneficiaire": {
                "libelle": "Adhesion Familiale",
                "categorieTB": { "code": "TB001", "libelle": "CLASSIQUE" },
                "paquetSoin": { "libelle": "CLASSIQUE", "taux": 80 }
              },
              "adhesion": { "nom": "DIOP", "prenom": "Moussa", "dateNaissance": "1960-01-01" }
            }
            """;

    private HttpServer serveur;

    @AfterEach
    void arreter() {
        if (serveur != null) {
            serveur.stop(0);
        }
    }

    @Test
    void extraitIdentiteEtEtatDeLaCarte() throws Exception {
        ServiceCarteCsu.CarteCsu carte = ServiceCarteCsu.versCarte(
                new ObjectMapper().readTree(REPONSE), "X0000Y0000", LocalDate.of(2026, 10, 1));

        assertThat(carte.nom()).isEqualTo("DIOP");
        assertThat(carte.prenom()).isEqualTo("Awa");
        assertThat(carte.sexe()).isEqualTo("F");
        assertThat(carte.dateNaissance()).isEqualTo(LocalDate.of(1990, 5, 4));
        assertThat(carte.numeroPiece()).isEqualTo("1234567890123");
        assertThat(carte.categorie()).isEqualTo("CLASSIQUE");
        assertThat(carte.tauxCouverture()).isEqualTo(80);
        assertThat(carte.carteActive()).isTrue();
        assertThat(carte.carteValide()).isTrue();
        assertThat(carte.dateFin()).isEqualTo(LocalDate.of(2027, 1, 11));
        // Téléphone du porteur absent : celui du titulaire prend le relais.
        assertThat(carte.telephone()).isEqualTo("770000000");
        assertThat(carte.photo()).isNull();
    }

    @Test
    void beneficiaireEnPeriodeObservationNEstPasPrisEnCharge() throws Exception {
        ObjectMapper json = new ObjectMapper();
        // Adhésion le 11/01/2026 (dateDebutc) ; avec un mois d'observation, droits ouverts le 11/02/2026.
        ServiceCarteCsu.CarteCsu enObservation = ServiceCarteCsu.versCarte(
                json.readTree(REPONSE), "X0000Y0000", LocalDate.of(2026, 1, 20), 1);
        assertThat(enObservation.carteActive()).isTrue();
        assertThat(enObservation.enPeriodeObservation()).isTrue();
        assertThat(enObservation.carteValide()).isFalse();
        assertThat(enObservation.dateDebutDroits()).isEqualTo(LocalDate.of(2026, 2, 11));

        // Le jour où les droits s'ouvrent : plus d'observation, carte valide.
        ServiceCarteCsu.CarteCsu droitsOuverts = ServiceCarteCsu.versCarte(
                json.readTree(REPONSE), "X0000Y0000", LocalDate.of(2026, 2, 11), 1);
        assertThat(droitsOuverts.enPeriodeObservation()).isFalse();
        assertThat(droitsOuverts.carteValide()).isTrue();

        // Observation désactivée (0 mois) : droits ouverts dès l'adhésion.
        ServiceCarteCsu.CarteCsu sansObservation = ServiceCarteCsu.versCarte(
                json.readTree(REPONSE), "X0000Y0000", LocalDate.of(2026, 1, 20), 0);
        assertThat(sansObservation.enPeriodeObservation()).isFalse();
        assertThat(sansObservation.carteValide()).isTrue();
    }

    @Test
    void carteExpireeOuSuspendueNEstPasValide() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ServiceCarteCsu.CarteCsu expiree = ServiceCarteCsu.versCarte(
                json.readTree(REPONSE), "X0000Y0000", LocalDate.of(2027, 1, 12));
        assertThat(expiree.carteActive()).isTrue();
        assertThat(expiree.carteValide()).isFalse();

        ServiceCarteCsu.CarteCsu suspendue = ServiceCarteCsu.versCarte(
                json.readTree(REPONSE.replace("\"Active\"", "\"SUSPENDRE\"")), "X0000Y0000",
                LocalDate.of(2026, 10, 1));
        assertThat(suspendue.carteActive()).isFalse();
        assertThat(suspendue.carteValide()).isFalse();
    }

    @Test
    void interrogeLeSiteAvecLeCodeNormalise() throws Exception {
        AtomicReference<String> requete = new AtomicReference<>();
        AtomicReference<String> autorisation = new AtomicReference<>();
        demarrer(200, REPONSE, requete, autorisation);

        ServiceCarteCsu.CarteCsu carte = service().verifier("  x0000 y0000 ");

        assertThat(requete.get()).isEqualTo("/api/beneficiairess/codeImmatriculation?code=X0000Y0000");
        assertThat(autorisation.get()).isNull();
        assertThat(carte.nom()).isEqualTo("DIOP");
    }

    @Test
    void codeInconnuDonneUn404() throws Exception {
        demarrer(404, "{\"error\":\"Erreur API\"}", new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> service().verifier("X0000Y0000"))
                .isInstanceOfSatisfying(ExceptionMetier.class,
                        e -> assertThat(e.getStatut()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void panneDuSiteDonneUn503() throws Exception {
        demarrer(500, "oups", new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> service().verifier("X0000Y0000"))
                .isInstanceOfSatisfying(ExceptionMetier.class,
                        e -> assertThat(e.getStatut()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void codeMalFormeRefuseSansAppel() {
        ServiceCarteCsu service = new ServiceCarteCsu("http://127.0.0.1:1", 200, 200);

        assertThatThrownBy(() -> service.verifier("a&b=c"))
                .isInstanceOfSatisfying(ExceptionMetier.class,
                        e -> assertThat(e.getStatut()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ────────────────────────────── outillage ──────────────────────────────

    private ServiceCarteCsu service() {
        return new ServiceCarteCsu("http://127.0.0.1:" + serveur.getAddress().getPort(), 2000, 2000);
    }

    private void demarrer(int statut, String corps, AtomicReference<String> requete,
                          AtomicReference<String> autorisation) throws IOException {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.createContext("/", echange -> {
            requete.set(echange.getRequestURI().toString());
            autorisation.set(echange.getRequestHeaders().getFirst("Authorization"));
            byte[] octets = corps.getBytes(StandardCharsets.UTF_8);
            echange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            echange.sendResponseHeaders(statut, octets.length);
            try (OutputStream sortie = echange.getResponseBody()) {
                sortie.write(octets);
            }
        });
        serveur.start();
    }
}
