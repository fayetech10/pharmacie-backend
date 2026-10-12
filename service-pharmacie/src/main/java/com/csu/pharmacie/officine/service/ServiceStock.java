package com.csu.pharmacie.officine.service;

import com.csu.pharmacie.entity.ArticleStock;
import com.csu.pharmacie.entity.MouvementStock;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.csu.pharmacie.commun.client.dto.SortieStockDto;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.officine.depot.DepotArticleStock;
import com.csu.pharmacie.officine.depot.DepotMouvementStock;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Tenue du stock et sorties automatiques déclenchées par la facturation (§14).
 *
 * <p>Deux exigences gouvernent ce service :</p>
 * <ul>
 *   <li><b>ne jamais décrémenter deux fois</b> — chaque sortie automatique porte la
 *       référence de sa ligne de facture, et une référence déjà vue renvoie le mouvement
 *       existant au lieu d'en créer un second ;</li>
 *   <li><b>ne jamais bloquer un soin</b> — un stock insuffisant produit un mouvement
 *       tracé et un stock négatif signalé, pas un refus. Le médicament a été délivré au
 *       patient ; le nier ne le remettrait pas dans l'armoire.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ServiceStock {

    private static final Logger log = LoggerFactory.getLogger(ServiceStock.class);

    private final DepotArticleStock depotArticle;
    private final DepotMouvementStock depotMouvement;

    // ────────────────────────────── articles ──────────────────────────────

    public List<ArticleStock> articles(String structureId) {
        return depotArticle.findByStructureId(structureCible(structureId)).stream()
                .sorted(Comparator.comparing(a -> a.getDesignation() == null ? "" : a.getDesignation()))
                .toList();
    }

    public List<ArticleStock> alertes(String structureId) {
        return depotArticle.enAlerte(structureCible(structureId));
    }

    public ArticleStock parId(String id) {
        return depotArticle.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Article introuvable : " + id));
    }

    @Transactional
    public ArticleStock enregistrer(String id, ArticleStock saisie) {
        String structureId = structureCible(saisie.getStructureId());
        if (saisie.getCode() == null || saisie.getCode().isBlank()) {
            throw ExceptionMetier.invalide("Le code de l'article est obligatoire");
        }
        ArticleStock article = id == null || id.isBlank() ? new ArticleStock() : parId(id);

        Optional<ArticleStock> homonyme =
                depotArticle.findByStructureIdAndCodeIgnoreCase(structureId, saisie.getCode());
        if (homonyme.isPresent() && !homonyme.get().getId().equals(article.getId())) {
            throw ExceptionMetier.conflit("Un article porte déjà le code « " + saisie.getCode()
                    + " » dans ce stock.");
        }

        article.setStructureId(structureId);
        article.setCode(saisie.getCode().trim().toUpperCase());
        article.setDesignation(saisie.getDesignation());
        article.setForme(saisie.getForme());
        article.setDosage(saisie.getDosage());
        article.setSeuilAlerte(Math.max(0, saisie.getSeuilAlerte()));
        article.setPrixUnitaire(saisie.getPrixUnitaire());
        article.setDatePeremption(saisie.getDatePeremption());
        // La quantité ne se modifie jamais par édition de la fiche : elle résulte
        // exclusivement des mouvements, sinon l'historique ne rend plus compte du stock.
        return depotArticle.save(article);
    }

    // ────────────────────────────── mouvements ──────────────────────────────

    @Transactional
    public MouvementStock entree(String articleId, int quantite, String motif) {
        if (quantite <= 0) {
            throw ExceptionMetier.invalide("La quantité entrée doit être positive");
        }
        ArticleStock article = parId(articleId);
        article.setQuantite(article.getQuantite() + quantite);
        depotArticle.save(article);
        return tracer(article, MouvementStock.Type.ENTREE, quantite, motif, null, null);
    }

    @Transactional
    public MouvementStock sortieManuelle(String articleId, int quantite, String motif) {
        if (quantite <= 0) {
            throw ExceptionMetier.invalide("La quantité sortie doit être positive");
        }
        ArticleStock article = parId(articleId);
        if (article.getQuantite() < quantite) {
            // Sortie manuelle : ici on peut refuser, personne n'attend au comptoir.
            throw ExceptionMetier.conflit("Stock insuffisant pour « " + article.getDesignation()
                    + " » : " + article.getQuantite() + " en stock, " + quantite + " demandé(s).");
        }
        article.setQuantite(article.getQuantite() - quantite);
        depotArticle.save(article);
        return tracer(article, MouvementStock.Type.SORTIE, quantite, motif, null, null);
    }

    /** Correction d'inventaire : fixe la quantité et trace l'écart. */
    @Transactional
    public MouvementStock ajuster(String articleId, int quantiteReelle, String motif) {
        if (quantiteReelle < 0) {
            throw ExceptionMetier.invalide("La quantité constatée ne peut pas être négative");
        }
        if (motif == null || motif.isBlank()) {
            throw ExceptionMetier.invalide("Un ajustement d'inventaire doit être motivé");
        }
        ArticleStock article = parId(articleId);
        int ecart = quantiteReelle - article.getQuantite();
        article.setQuantite(quantiteReelle);
        depotArticle.save(article);
        return tracer(article, MouvementStock.Type.AJUSTEMENT, ecart, motif, null, null);
    }

    /**
     * Sortie déclenchée par la facturation d'une prestation dépositaire (§14).
     *
     * <p>Idempotente par {@code referenceLigne} et non bloquante : l'article inconnu ou
     * le stock insuffisant sont signalés dans le message, mais la réponse reste positive.
     * Le point de soins a déjà délivré le produit — la seule question est de savoir si le
     * mouvement a été enregistré.</p>
     */
    @Transactional
    public Reponse sortieAutomatique(SortieStockDto demande) {
        if (demande.getReferenceLigne() == null || demande.getReferenceLigne().isBlank()) {
            throw ExceptionMetier.invalide("La référence de ligne est obligatoire : "
                    + "c'est elle qui évite les doubles sorties.");
        }

        Optional<MouvementStock> deja = depotMouvement.findByReferenceLigne(demande.getReferenceLigne());
        if (deja.isPresent()) {
            return new Reponse(deja.get().getId(), deja.get().getStockApres(),
                    "Mouvement déjà enregistré — aucune nouvelle sortie.");
        }

        Optional<ArticleStock> trouve = demande.getMedicamentId() == null ? Optional.empty()
                : depotArticle.findById(demande.getMedicamentId());
        if (trouve.isEmpty() && demande.getStructureId() != null && demande.getMedicamentId() != null) {
            // Le référentiel peut désigner l'article par son code plutôt que par son identifiant.
            trouve = depotArticle.findByStructureIdAndCodeIgnoreCase(
                    demande.getStructureId(), demande.getMedicamentId());
        }
        if (trouve.isEmpty()) {
            log.warn("Sortie automatique ignorée : article « {} » inconnu pour la structure {} (facture {}).",
                    demande.getMedicamentId(), demande.getStructureId(), demande.getReferenceFacture());
            return new Reponse(null, null,
                    "Article inconnu dans le stock de la structure — aucune sortie enregistrée.");
        }

        ArticleStock article = trouve.get();
        int quantite = Math.max(1, demande.getQuantite());
        String message = null;
        if (article.getQuantite() < quantite) {
            message = "Stock insuffisant (" + article.getQuantite() + " disponible(s) pour "
                    + quantite + " délivré(s)) : le stock passe en négatif et doit être régularisé.";
            log.warn("Stock négatif sur « {} » (structure {}) après la facture {}.",
                    article.getDesignation(), article.getStructureId(), demande.getReferenceFacture());
        }

        article.setQuantite(article.getQuantite() - quantite);
        depotArticle.save(article);

        MouvementStock mouvement = tracer(article, MouvementStock.Type.SORTIE, quantite,
                demande.getMotif() == null ? "Facturation CSU" : demande.getMotif(),
                demande.getReferenceFacture(), demande.getReferenceLigne());

        return new Reponse(mouvement.getId(), article.getQuantite(), message);
    }

    public List<MouvementStock> historique(String articleId) {
        return depotMouvement.findByArticleIdOrderByCreatedAtDesc(articleId);
    }

    public List<MouvementStock> journal(String structureId, LocalDate debut, LocalDate fin) {
        LocalDate fininclus = fin == null ? LocalDate.now() : fin;
        LocalDate depart = debut == null ? fininclus.withDayOfMonth(1) : debut;
        return depotMouvement.findByStructureIdAndDateBetweenOrderByDateDesc(
                structureCible(structureId), depart, fininclus);
    }

    // ────────────────────────────── utilitaires ──────────────────────────────

    private MouvementStock tracer(ArticleStock article, MouvementStock.Type type, int quantite,
                                  String motif, String referenceFacture, String referenceLigne) {
        var courant = ContexteUtilisateur.courantOuNull();
        return depotMouvement.save(MouvementStock.builder()
                .articleId(article.getId())
                .structureId(article.getStructureId())
                .type(type)
                .designation(article.getDesignation())
                .quantite(quantite)
                .stockApres(article.getQuantite())
                .date(LocalDate.now())
                .motif(motif)
                .referenceFacture(referenceFacture)
                .referenceLigne(referenceLigne)
                .createdBy(courant == null ? null : courant.getId())
                .build());
    }

    /**
     * Structure dont on tient le stock : celle du jeton, sauf pour un superviseur qui
     * peut en désigner une autre explicitement.
     */
    private String structureCible(String demandee) {
        var utilisateur = ContexteUtilisateur.courant();
        boolean superviseur = utilisateur.getRole() != null && utilisateur.getRole().estSuperviseur();
        if (superviseur && demandee != null && !demandee.isBlank()) {
            return demandee;
        }
        String propre = utilisateur.getStructureId();
        if (propre == null || propre.isBlank()) {
            if (demandee != null && !demandee.isBlank()) {
                return demandee;
            }
            throw ExceptionMetier.interdit("Votre compte n'est rattaché à aucun stock.");
        }
        return propre;
    }

    /** Résultat d'une sortie automatique, tel que le lit le point de soins. */
    public record Reponse(String mouvementId, Integer stockRestant, String message) {
    }
}
