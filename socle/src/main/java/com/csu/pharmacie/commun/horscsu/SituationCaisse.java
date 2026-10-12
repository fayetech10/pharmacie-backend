package com.csu.pharmacie.commun.horscsu;

import com.csu.pharmacie.entity.FactureHorsCsu;
import com.csu.pharmacie.entity.LigneHorsCsu;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Situation de caisse Hors CSU (§8 : « situation journalière et mensuelle des montants
 * encaissés »).
 *
 * <p>Le détail par catégorie et par jour est fourni d'emblée : c'est ce que le
 * gestionnaire compare à sa caisse physique en fin de journée, et le recalculer côté
 * client mènerait à deux totaux différents.</p>
 */
@Data
@Builder
public class SituationCaisse {

    private LocalDate debut;
    private LocalDate fin;
    private String libellePeriode;

    private int nombreFactures;
    private int nombreFacturesAnnulees;
    private double montantFacture;
    private double montantEncaisse;
    /** Facturé mais non encore encaissé (règlements partiels). */
    private double resteARecouvrer;

    /** Recette par catégorie de prestation. */
    @Builder.Default
    private Map<String, Double> parCategorie = new LinkedHashMap<>();
    /** Recette par mode de paiement — sert au rapprochement espèces / mobile money. */
    @Builder.Default
    private Map<String, Double> parModePaiement = new LinkedHashMap<>();
    /** Recette jour par jour (situation mensuelle). */
    @Builder.Default
    private Map<String, Double> parJour = new LinkedHashMap<>();

    /** Construit la situation à partir des factures de la période. */
    public static SituationCaisse depuis(List<FactureHorsCsu> factures, LocalDate debut,
                                         LocalDate fin, String libelle) {
        SituationCaisse situation = SituationCaisse.builder()
                .debut(debut)
                .fin(fin)
                .libellePeriode(libelle)
                .build();

        for (FactureHorsCsu facture : factures) {
            if (facture.isAnnulee()) {
                situation.nombreFacturesAnnulees++;
                continue;
            }
            situation.nombreFactures++;
            situation.montantFacture += facture.getMontantTotal();
            situation.montantEncaisse += facture.getMontantEncaisse();

            String mode = facture.getModePaiement() == null || facture.getModePaiement().isBlank()
                    ? "Non précisé" : facture.getModePaiement();
            situation.parModePaiement.merge(mode, facture.getMontantEncaisse(), Double::sum);

            String jour = facture.getDate() == null ? "" : facture.getDate().toString();
            situation.parJour.merge(jour, facture.getMontantEncaisse(), Double::sum);

            if (facture.getLignes() != null) {
                for (LigneHorsCsu ligne : facture.getLignes()) {
                    String categorie = ligne.getCategorie() == null || ligne.getCategorie().isBlank()
                            ? "Autres" : ligne.getCategorie();
                    situation.parCategorie.merge(categorie, ligne.getMontant(), Double::sum);
                }
            }
        }

        situation.resteARecouvrer = situation.montantFacture - situation.montantEncaisse;
        return situation;
    }
}
