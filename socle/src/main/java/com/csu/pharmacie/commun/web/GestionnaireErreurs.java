package com.csu.pharmacie.commun.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Traduit les exceptions en réponses JSON uniformes sur tous les services.
 *
 * <p>Le dashboard Angular lit systématiquement {@code err.error.message} : la forme de la
 * réponse doit donc rester identique quel que soit le microservice qui a répondu.</p>
 */
@RestControllerAdvice
public class GestionnaireErreurs {

    private static final Logger log = LoggerFactory.getLogger(GestionnaireErreurs.class);

    @ExceptionHandler(ExceptionMetier.class)
    public ResponseEntity<Map<String, Object>> metier(ExceptionMetier e) {
        return reponse(e.getStatut(), e.getMessage());
    }

    /** Champs annotés {@code @Valid} en erreur : on renvoie « champ : motif » pour tous. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " : " + f.getDefaultMessage())
                .collect(Collectors.joining(" ; "));
        return reponse(HttpStatus.BAD_REQUEST, details.isEmpty() ? "Requête invalide" : details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> contrainte(ConstraintViolationException e) {
        return reponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Facture modifiée entre la lecture et l'écriture (verrou optimiste) — typiquement le
     * BCSU (cœur) qui y place un patient pendant que la structure la complète. Rien n'a été
     * écrit : il suffit de recommencer.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> conflitDeVersion(ObjectOptimisticLockingFailureException e) {
        return reponse(HttpStatus.CONFLICT, "La facture vient d'être modifiée par un autre utilisateur. "
                + "Rien n'a été enregistré : rechargez puis recommencez.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> accesRefuse(AccessDeniedException e) {
        return reponse(HttpStatus.FORBIDDEN, "Accès refusé");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> fichierTropVolumineux(MaxUploadSizeExceededException e) {
        return reponse(HttpStatus.PAYLOAD_TOO_LARGE, "Fichier trop volumineux");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> argumentInvalide(IllegalArgumentException e) {
        return reponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Chemin non routé (ex. une URL servie par un autre service que celui qu'a choisi la
     * passerelle). Sans ce handler, l'exception tombe dans {@link #inattendue(Exception)}
     * et le client reçoit un 500 trompeur, avec une pile complète tracée en ERROR à chaque
     * appel — pour ce qui n'est qu'un 404.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> ressourceIntrouvable(NoResourceFoundException e) {
        return reponse(HttpStatus.NOT_FOUND, "Ressource introuvable");
    }

    /**
     * Corps de requête illisible : JSON malformé, ou octets qui ne sont pas de l'UTF-8
     * (un client qui poste du Latin-1 accentué, par exemple). C'est une erreur du client,
     * donc 400 — et non le 500 que renvoyait le filet de sécurité.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> corpsIllisible(HttpMessageNotReadableException e) {
        return reponse(HttpStatus.BAD_REQUEST, "Corps de requête illisible : JSON malformé ou encodage non UTF-8");
    }

    /**
     * Filet de sécurité : on trace la pile côté serveur mais on ne la renvoie jamais au
     * client (elle divulguerait la structure interne des services).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> inattendue(Exception e) {
        log.error("Erreur inattendue", e);
        return reponse(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne du serveur");
    }

    private static ResponseEntity<Map<String, Object>> reponse(HttpStatus statut, String message) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("timestamp", LocalDateTime.now().toString());
        corps.put("status", statut.value());
        corps.put("error", statut.getReasonPhrase());
        corps.put("message", message);
        return ResponseEntity.status(statut).body(corps);
    }
}
