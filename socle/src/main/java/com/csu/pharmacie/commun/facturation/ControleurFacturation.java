package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.commun.export.ApercuImage;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.StatutFacture;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Contrat HTTP de la facturation d'un point de soins — {@code /api/factures-structure}.
 *
 * <p>Ce sont exactement les routes que le dashboard appelle déjà, et les réponses sont les
 * entités {@code FactureStructure} elles-mêmes : le passage aux microservices ne demande
 * aucune modification des écrans.</p>
 *
 * <p>Les méthodes de mapping sont héritées : la sous-classe n'a qu'à porter
 * {@code @RestController} et {@code @RequestMapping("/api/factures-structure")}.</p>
 */
public abstract class ControleurFacturation {

    protected abstract ServiceFacturation service();

    // ────────────────────────────── lecture ──────────────────────────────

    @GetMapping
    public List<FactureStructure> mesFactures(@RequestParam(required = false) Integer mois,
                                              @RequestParam(required = false) Integer annee,
                                              @RequestParam(required = false) Regime regime,
                                              @RequestParam(required = false) StatutFacture statut) {
        return service().mesFactures(mois, annee, regime, statut);
    }

    /** Charge utile minimale pour les pastilles de notification du menu. */
    @GetMapping("/statuts")
    public List<Map<String, Object>> statuts() {
        return service().statutsLegers();
    }

    @GetMapping("/{id}")
    public FactureStructure detail(@PathVariable String id) {
        return service().parId(id);
    }

    // ────────────────────────────── saisie ──────────────────────────────

    /**
     * Ajoute un bénéficiaire et ses prestations à la facture du mois pour son régime.
     * C'est la saisie « au fil de l'eau » de l'écran de facturation.
     */
    @PostMapping
    public FactureStructure ajouterPatient(@RequestBody RequeteFacturation requete) {
        return service().ajouterPatient(requete);
    }

    /** Remplace le contenu d'une facture existante (édition d'un brouillon, correction). */
    @PutMapping("/{id}")
    public FactureStructure remplacer(@PathVariable String id, @RequestBody RequeteFacturation requete) {
        return service().remplacer(id, requete);
    }

    /** Supprime toutes les lignes d'un bénéficiaire (référence = n° de LG ou id patient). */
    @DeleteMapping("/{id}/patients")
    public FactureStructure supprimerPatient(@PathVariable String id, @RequestParam("lettre") String reference) {
        return service().supprimerPatient(id, reference);
    }

    @PutMapping("/{id}/lignes/{index}")
    public FactureStructure modifierLigne(@PathVariable String id, @PathVariable int index,
                                          @RequestBody LigneFactureStructure ligne) {
        return service().modifierLigne(id, index, ligne);
    }

    @DeleteMapping("/{id}/lignes/{index}")
    public FactureStructure supprimerLigne(@PathVariable String id, @PathVariable int index) {
        return service().supprimerLigne(id, index);
    }

    // ────────────────────────────── dossiers patients ──────────────────────────────

    /**
     * Complète ou corrige le dossier d'un patient : prestations (remplacées en bloc),
     * pièces, coordonnées — l'identité validée par le contrôle d'éligibilité reste figée.
     */
    @PutMapping("/{id}/dossiers/{dossierId}")
    public FactureStructure modifierDossier(@PathVariable String id, @PathVariable String dossierId,
                                            @RequestBody RequeteFacturation requete) {
        return service().modifierDossier(id, dossierId, requete);
    }

    @DeleteMapping("/{id}/dossiers/{dossierId}")
    public FactureStructure supprimerDossier(@PathVariable String id, @PathVariable String dossierId) {
        return service().supprimerDossier(id, dossierId);
    }

    /** Photo justificative d'un dossier (ticket, CNI, extrait), lue à la demande. */
    @GetMapping("/{id}/dossiers/{dossierId}/pieces/{type}")
    public Map<String, String> piece(@PathVariable String id, @PathVariable String dossierId,
                                     @PathVariable String type) {
        return service().piece(id, dossierId, type);
    }

    /** {@code ?format=image} : aperçu JPEG pour le dossier patient (voir {@link ApercuImage}). */
    @GetMapping("/{id}/dossiers/{dossierId}/attestation")
    public ResponseEntity<byte[]> attestationSoins(@PathVariable String id, @PathVariable String dossierId,
                                                   @RequestParam(required = false) String format) {
        return document(service().pdfAttestationSoins(id, dossierId),
                service().nomFichierDossier(id, dossierId, "attestation_soins"), format);
    }

    @GetMapping("/{id}/dossiers/{dossierId}/fiche-assure")
    public ResponseEntity<byte[]> ficheAssure(@PathVariable String id, @PathVariable String dossierId,
                                              @RequestParam(required = false) String format) {
        return document(service().pdfFicheAssure(id, dossierId),
                service().nomFichierDossier(id, dossierId, "fiche_assure"), format);
    }

    private static ResponseEntity<byte[]> document(byte[] pdf, String nomPdf, String format) {
        if ("image".equals(format)) {
            return fichier(ApercuImage.depuisPdf(pdf), MediaType.IMAGE_JPEG, nomPdf.replaceFirst("\\.pdf$", ".jpg"));
        }
        return fichier(pdf, MediaType.APPLICATION_PDF, nomPdf);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> supprimer(@PathVariable String id) {
        service().supprimer(id);
        return Map.of("message", "Brouillon supprimé");
    }

    // ────────────────────────────── transmission ──────────────────────────────

    /**
     * Transmet la facture. Un poste la soumet à son centre de rattachement, un centre la
     * transmet directement au service régional — le service tranche selon le type de structure.
     */
    @PostMapping("/{id}/envoyer")
    public FactureStructure envoyer(@PathVariable String id) {
        return service().envoyer(id);
    }

    /** Envoi groupé de fin de mois. */
    @PostMapping("/envoyer-groupe")
    public List<FactureStructure> envoyerGroupe(@RequestBody Map<String, Object> corps) {
        Object brut = corps.get("regime");
        Regime regime = brut == null || String.valueOf(brut).isBlank()
                ? null : Regime.valueOf(String.valueOf(brut));
        return service().envoyerGroupe(regime, entier(corps.get("mois")), entier(corps.get("annee")));
    }

    // ────────────────────────────── documents (§7) ──────────────────────────────

    @GetMapping("/{id}/export/pdf")
    public ResponseEntity<byte[]> facturePdf(@PathVariable String id) {
        return fichier(service().pdfFactureRecapitulative(id), MediaType.APPLICATION_PDF,
                service().nomFichierFacture(id, "facture", "pdf"));
    }

    /** Liste nominative (§7) — la pièce de contrôle jointe à la facture récapitulative. */
    @GetMapping("/{id}/export/liste-nominative")
    public ResponseEntity<byte[]> listeNominativePdf(@PathVariable String id) {
        return fichier(service().pdfListeNominative(id), MediaType.APPLICATION_PDF,
                service().nomFichierFacture(id, "liste_nominative", "pdf"));
    }

    protected static ResponseEntity<byte[]> fichier(byte[] contenu, MediaType type, String nomFichier) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(nomFichier, StandardCharsets.UTF_8).build().toString())
                .body(contenu);
    }

    private static Integer entier(Object valeur) {
        if (valeur == null) {
            return null;
        }
        if (valeur instanceof Number nombre) {
            return nombre.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(valeur));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
