package com.csu.pharmacie.commun.horscsu;

import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.commun.export.Documents;
import com.csu.pharmacie.commun.export.EnteteDocument;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.facturation.Numerotation;
import com.csu.pharmacie.commun.facturation.ReglesFacturation;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.FactureHorsCsu;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.LigneHorsCsu;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Facturation des patients payants (§8), partagée par le poste, le centre et la pharmacie.
 *
 * <p>Circuit volontairement court : on encaisse, on imprime, on classe. Pas de validation
 * hiérarchique — cet argent ne transite pas par la SEN-CSU. La seule contrainte forte est
 * la traçabilité de la caisse : rien ne s'efface, tout s'annule avec motif.</p>
 */
@RequiredArgsConstructor
public class ServiceHorsCsu {

    protected final DepotFactureHorsCsu depot;
    protected final ClientIdentite clientIdentite;
    protected final Numerotation numerotation;
    protected final GenerateurPdf generateurPdf;

    /** Préfixe des numéros de caisse. */
    protected String prefixeNumero() {
        return "HC";
    }

    /** Titre porté par le reçu imprimé. */
    protected String titreRecu() {
        return "Facture Hors CSU";
    }

    /**
     * Point d'encaissement de l'appelant : sa structure sanitaire, ou à défaut sa pharmacie.
     * C'est cette clé qui cloisonne l'historique et les situations de caisse.
     */
    protected String pointEncaissement() {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        if (utilisateur.getStructureId() != null && !utilisateur.getStructureId().isBlank()) {
            return utilisateur.getStructureId();
        }
        if (utilisateur.getPharmacieId() != null && !utilisateur.getPharmacieId().isBlank()) {
            return utilisateur.getPharmacieId();
        }
        throw ExceptionMetier.interdit(
                "Votre compte n'est rattaché ni à une structure sanitaire ni à une pharmacie.");
    }

    // ────────────────────────────── lecture ──────────────────────────────

    /** Historique des factures Hors CSU du point d'encaissement (§8). */
    public List<FactureHorsCsu> historique() {
        return depot.findByStructureSanitaireIdOrderByDateDesc(pointEncaissement());
    }

    public FactureHorsCsu parId(String id) {
        FactureHorsCsu facture = depot.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Facture Hors CSU introuvable : " + id));
        if (!pointEncaissement().equals(facture.getStructureSanitaireId())) {
            throw ExceptionMetier.interdit("Cette facture n'appartient pas à votre caisse");
        }
        return facture;
    }

    /** Situation journalière (§8). Sans date, la journée en cours. */
    public SituationCaisse situationJournaliere(LocalDate jour) {
        LocalDate date = jour == null ? LocalDate.now() : jour;
        return SituationCaisse.depuis(depot.findByStructureSanitaireIdAndDate(pointEncaissement(), date),
                date, date, "Journée du " + date.format(Documents.DATE));
    }

    /** Situation mensuelle (§8). Sans période, le mois en cours. */
    public SituationCaisse situationMensuelle(Integer mois, Integer annee) {
        LocalDate aujourdhui = LocalDate.now();
        YearMonth periode = YearMonth.of(
                annee == null || annee <= 0 ? aujourdhui.getYear() : annee,
                mois == null || mois < 1 || mois > 12 ? aujourdhui.getMonthValue() : mois);
        LocalDate debut = periode.atDay(1);
        LocalDate fin = periode.atEndOfMonth();
        return SituationCaisse.depuis(
                depot.findByStructureSanitaireIdAndDateBetween(pointEncaissement(), debut, fin),
                debut, fin,
                FactureStructure.MOIS[periode.getMonthValue()] + " " + periode.getYear());
    }

    // ────────────────────────────── écriture ──────────────────────────────

    /**
     * Encaisse une facture. Les montants de ligne et le total sont recalculés ici ; seul
     * {@code montantEncaisse} est repris tel quel, un règlement partiel étant une
     * information de caisse et non un calcul.
     */
    @Transactional
    public FactureHorsCsu encaisser(FactureHorsCsu saisie) {
        UtilisateurAuthentifie utilisateur = ContexteUtilisateur.courant();
        String point = pointEncaissement();

        List<LigneHorsCsu> lignes = saisie.getLignes() == null ? new ArrayList<>() : saisie.getLignes();
        if (lignes.isEmpty()) {
            throw ExceptionMetier.invalide("Ajoutez au moins une prestation avant d'encaisser.");
        }

        double total = 0;
        for (LigneHorsCsu ligne : lignes) {
            int quantite = Math.max(1, ligne.getQuantite());
            ligne.setQuantite(quantite);
            if (ligne.getPrixUnitaire() < 0) {
                throw ExceptionMetier.invalide("Prix négatif sur « " + ligne.getDesignation() + " ».");
            }
            ligne.setMontant(ReglesFacturation.arrondir(quantite * ligne.getPrixUnitaire()));
            total += ligne.getMontant();
        }

        LocalDate date = saisie.getDate() == null ? LocalDate.now() : saisie.getDate();
        if (date.isAfter(LocalDate.now())) {
            throw ExceptionMetier.invalide("Une facture ne peut pas être datée dans le futur.");
        }

        FactureHorsCsu facture = new FactureHorsCsu();
        facture.setNumero(numerotation.prochainNumero(prefixeNumero(), date.getYear(), date.getMonthValue()));
        facture.setStructureSanitaireId(point);
        facture.setPharmacieId(utilisateur.getPharmacieId());
        // Le libellé et le rattachement ne sont connus que pour un point de soins ;
        // une officine privée n'a pas de structure sanitaire au référentiel.
        StructureDto structure = utilisateur.getStructureId() == null ? null
                : clientIdentite.structure(utilisateur.getStructureId());
        if (structure != null) {
            facture.setStructureNom(structure.getNom());
            facture.setDistrictId(structure.getDistrictId());
            facture.setRegionId(structure.getRegionId());
        }
        facture.setDate(date);
        facture.setPatientNom(saisie.getPatientNom());
        facture.setPatientPrenom(saisie.getPatientPrenom());
        facture.setPatientTelephone(saisie.getPatientTelephone());
        facture.setLignes(lignes);
        facture.setMontantTotal(ReglesFacturation.arrondir(total));
        // Sans montant saisi, la facture est considérée réglée intégralement : c'est le cas
        // courant au comptoir, et un zéro par défaut fausserait la recette du jour.
        facture.setMontantEncaisse(saisie.getMontantEncaisse() > 0
                ? ReglesFacturation.arrondir(saisie.getMontantEncaisse())
                : facture.getMontantTotal());
        facture.setModePaiement(saisie.getModePaiement());
        facture.setCreatedAt(LocalDateTime.now());
        facture.setCreatedBy(utilisateur.getId());
        facture.setCreatedByNom(utilisateur.nomComplet());

        if (facture.getMontantEncaisse() > facture.getMontantTotal()) {
            throw ExceptionMetier.invalide("Le montant encaissé dépasse le montant facturé.");
        }
        return depot.save(facture);
    }

    /** Annulation avec motif — la facture reste dans l'historique et dans la situation. */
    @Transactional
    public FactureHorsCsu annuler(String id, String motif) {
        if (motif == null || motif.isBlank()) {
            throw ExceptionMetier.invalide("Le motif d'annulation est obligatoire.");
        }
        FactureHorsCsu facture = parId(id);
        if (facture.isAnnulee()) {
            throw ExceptionMetier.conflit("Cette facture est déjà annulée.");
        }
        facture.setAnnulee(true);
        facture.setMotifAnnulation(motif);
        return depot.save(facture);
    }

    /**
     * Génère (ou régénère) le reçu PDF. La réimpression du §8 n'est rien d'autre qu'un
     * nouvel appel : le document est toujours reconstruit depuis la facture stockée,
     * jamais archivé — il ne peut donc pas diverger d'elle.
     */
    public byte[] pdf(String id) {
        FactureHorsCsu facture = parId(id);
        return generateurPdf.factureHorsCsu(facture, EnteteDocument.builder()
                .titre(titreRecu())
                .numero(facture.getNumero())
                .structureNom(facture.getStructureNom())
                .periode(facture.getDate() == null ? null : facture.getDate().format(Documents.DATE))
                .build());
    }
}
