package com.csu.pharmacie.commun.carte;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import com.csu.pharmacie.commun.web.ExceptionMetier;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Vérification d'une carte CSU auprès du site officiel {@code carte.sencsu.sn}, pour
 * préremplir le patient au poste à partir de son matricule.
 *
 * <p>On interroge l'API JSON que la page de vérification appelle elle-même
 * ({@code /api/beneficiairess/codeImmatriculation?code=…}). Elle n'est <b>ni documentée
 * ni contractuelle</b> : si l'Agence la modifie, seule cette classe est à reprendre.
 * Le passage par le serveur, plutôt qu'un appel direct du navigateur, sert à ne renvoyer
 * à l'écran que les champs utiles : la réponse brute porte aussi l'adhésion et le chef de
 * famille, qu'on ne veut pas faire circuler.</p>
 *
 * <p>Le client est volontairement distinct de {@code AppelInterServices} : celui-ci
 * réémet le jeton de l'utilisateur, qui ne doit jamais partir vers un site tiers.</p>
 *
 * <p>Dans le socle et non dans service-poste-sante : poste et centre de santé facturent
 * tous deux depuis l'Espace PS, et le contrôle d'éligibilité est refait par le serveur qui
 * enregistre la facture. Le bean est déclaré par {@code ConfigurationCommune}.</p>
 */
public class ServiceCarteCsu {

    private static final Logger log = LoggerFactory.getLogger(ServiceCarteCsu.class);

    /** Matricule tel qu'imprimé sur la carte (ex. V8658Y2605), espaces retirés. */
    private static final Pattern FORMAT_CODE = Pattern.compile("^[A-Z0-9-]{4,30}$");
    private static final Pattern FORMAT_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}.*");
    /** Une photo plus lourde que ça n'a rien à faire dans une réponse d'aperçu. */
    private static final int TAILLE_MAX_PHOTO = 2_000_000;
    /** Durée par défaut de la période d'observation, en mois (le barème national en retient un). */
    private static final int PERIODE_OBSERVATION_DEFAUT = 1;

    private final RestClient client;
    /** Durée de la période d'observation, en mois : délai entre l'adhésion et l'ouverture des droits. */
    private final int periodeObservationMois;

    public ServiceCarteCsu(String urlBase, int delaiConnexion, int delaiLecture) {
        this(urlBase, delaiConnexion, delaiLecture, PERIODE_OBSERVATION_DEFAUT);
    }

    public ServiceCarteCsu(String urlBase, int delaiConnexion, int delaiLecture, int periodeObservationMois) {
        SimpleClientHttpRequestFactory fabrique = new SimpleClientHttpRequestFactory();
        fabrique.setConnectTimeout(delaiConnexion);
        fabrique.setReadTimeout(delaiLecture);
        this.client = RestClient.builder()
                .baseUrl(urlBase)
                .requestFactory(fabrique)
                .build();
        this.periodeObservationMois = Math.max(0, periodeObservationMois);
    }

    /**
     * Ce que l'écran du poste reçoit : l'identité du porteur et l'état de sa carte.
     * {@code sexe} vaut « M » ou « F » ; {@code carteValide} combine l'état « Active » et
     * la période de couverture. {@code mutuelle} et {@code typeAdhesion} sont repris quand
     * le site les fournit — sa réponse n'étant pas documentée, ils peuvent manquer.
     */
    public record CarteCsu(String codeImmatriculation, String prenom, String nom, String sexe,
                           String telephone, LocalDate dateNaissance, String typePiece,
                           String numeroPiece, String categorie, Integer tauxCouverture,
                           String etat, boolean carteActive, LocalDate dateDebut,
                           LocalDate dateFin, boolean carteValide, String photo,
                           String mutuelle, String typeAdhesion,
                           boolean enPeriodeObservation, LocalDate dateDebutDroits) {
    }

    public CarteCsu verifier(String code) {
        String codeNormalise = code == null ? "" : code.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!FORMAT_CODE.matcher(codeNormalise).matches()) {
            throw ExceptionMetier.invalide("Matricule CSU invalide : saisissez le code imprimé sur la carte (ex. V8658Y2605).");
        }

        JsonNode reponse;
        try {
            reponse = client.get()
                    .uri(uri -> uri.path("/api/beneficiairess/codeImmatriculation")
                            .queryParam("code", codeNormalise)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest e) {
            throw ExceptionMetier.introuvable("Aucune carte CSU ne correspond au matricule « " + codeNormalise + " ».");
        } catch (RestClientException e) {
            // Pas de matricule dans le journal : c'est un identifiant de personne.
            log.warn("Vérification de carte CSU en échec : {}", e.getMessage());
            throw ExceptionMetier.serviceIndisponible(
                    "Le site de vérification des cartes CSU est injoignable pour le moment. "
                            + "Réessayez dans un instant ou saisissez les informations à la main.");
        }

        if (reponse == null || texte(reponse, "nom") == null) {
            throw ExceptionMetier.introuvable("Aucune carte CSU ne correspond au matricule « " + codeNormalise + " ».");
        }
        return versCarte(reponse, codeNormalise, LocalDate.now(), periodeObservationMois);
    }

    /** Extrait les champs utiles ; période d'observation par défaut. Public pour les tests. */
    public static CarteCsu versCarte(JsonNode b, String codeSaisi, LocalDate aujourdhui) {
        return versCarte(b, codeSaisi, aujourdhui, PERIODE_OBSERVATION_DEFAUT);
    }

    /** Extrait les champs utiles de la réponse brute. Public pour les tests. */
    public static CarteCsu versCarte(JsonNode b, String codeSaisi, LocalDate aujourdhui, int periodeObservationMois) {
        JsonNode type = b.path("typeBeneficiaire");
        String categorie = texte(type.path("categorieTB"), "libelle");
        if (categorie == null) {
            categorie = texte(type, "libelle");
        }
        JsonNode taux = type.path("paquetSoin").path("taux");

        String telephone = texte(b, "telephone");
        if (telephone == null) {
            telephone = texte(b.path("titulaire"), "telephone");
        }

        String etat = texte(b, "etat");
        boolean active = "ACTIVE".equalsIgnoreCase(etat);
        // La date de début renvoyée par le site (dateDebutc) est la date d'adhésion : les
        // droits ne s'ouvrent qu'après la période d'observation, pendant laquelle la prise
        // en charge reste suspendue même si la carte est active.
        LocalDate adhesion = date(texte(b, "dateDebutc"));
        LocalDate fin = date(texte(b, "dateFinc"));
        LocalDate debutDroits = adhesion == null ? null : adhesion.plusMonths(periodeObservationMois);
        boolean dansLaPeriode = (debutDroits == null || !aujourdhui.isBefore(debutDroits))
                && (fin == null || !aujourdhui.isAfter(fin));
        boolean enObservation = active && debutDroits != null && aujourdhui.isBefore(debutDroits)
                && (adhesion == null || !aujourdhui.isBefore(adhesion));

        String matricule = texte(b, "codeImmatriculation");

        return new CarteCsu(
                matricule != null ? matricule : codeSaisi,
                texte(b, "prenom"),
                texte(b, "nom"),
                sexe(texte(b, "sexe")),
                telephone,
                date(texte(b, "dateNaissance")),
                texte(b, "typePiece"),
                texte(b, "numeroPiece"),
                categorie,
                taux.isNumber() ? taux.asInt() : null,
                etat,
                active,
                adhesion,
                fin,
                active && dansLaPeriode,
                photo(b),
                mutuelle(b),
                texte(type, "libelle"),
                enObservation,
                debutDroits);
    }

    /**
     * Mutuelle de rattachement de l'assuré. Le site ne documente pas sa réponse : on essaie
     * les emplacements plausibles, du plus précis au plus général, sans jamais échouer.
     */
    private static String mutuelle(JsonNode b) {
        JsonNode[] candidats = {
                b.path("mutuelle"), b.path("adhesion").path("mutuelle"), b.path("titulaire").path("mutuelle"),
                b.path("mutuelleSante"), b.path("adhesion").path("mutuelleSante")
        };
        for (JsonNode candidat : candidats) {
            if (candidat.isTextual() && !candidat.asText().isBlank()) {
                return candidat.asText().trim();
            }
            for (String champ : new String[]{"libelle", "nom", "designation", "raisonSociale"}) {
                String valeur = texte(candidat, champ);
                if (valeur != null) {
                    return valeur;
                }
            }
        }
        String plat = texte(b, "nomMutuelle");
        return plat != null ? plat : texte(b, "libelleMutuelle");
    }

    // ────────────────────────────── lecture défensive ──────────────────────────────

    private static String texte(JsonNode noeud, String champ) {
        JsonNode valeur = noeud.path(champ);
        if (valeur.isMissingNode() || valeur.isNull()) {
            return null;
        }
        String texte = valeur.asText().trim();
        return texte.isEmpty() ? null : texte;
    }

    /** Dates ISO, avec ou sans heure (« 2027-01-11 », « 2027-01-11T00:00:00Z »). */
    private static LocalDate date(String valeur) {
        if (valeur == null || !FORMAT_DATE.matcher(valeur).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(valeur.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** « FEMININ » / « MASCULIN » → « F » / « M ». */
    private static String sexe(String valeur) {
        if (valeur == null) {
            return null;
        }
        char premiere = Character.toUpperCase(valeur.charAt(0));
        return premiere == 'F' ? "F" : premiere == 'M' || premiere == 'H' ? "M" : null;
    }

    /**
     * Photo du porteur, utile pour vérifier qu'il s'agit bien de la personne au guichet.
     * Le site la livre en base64 (avec ou sans préfixe {@code data:}) ou en URL.
     */
    private static String photo(JsonNode b) {
        String brute = texte(b, "photoBase64");
        if (brute == null) {
            brute = texte(b, "photo");
        }
        if (brute == null || brute.length() > TAILLE_MAX_PHOTO) {
            return null;
        }
        if (brute.startsWith("data:image/") || brute.startsWith("https://")) {
            return brute;
        }
        String typeContenu = texte(b, "photoContentType");
        if (typeContenu == null || !typeContenu.startsWith("image/")) {
            typeContenu = "image/jpeg";
        }
        return brute.matches("^[A-Za-z0-9+/=\\r\\n]+$") ? "data:" + typeContenu + ";base64," + brute : null;
    }
}
