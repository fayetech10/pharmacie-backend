package com.csu.pharmacie.entity;

/**
 * Régime de couverture du bénéficiaire — détermine la répartition part bénéficiaire /
 * part SEN-CSU.
 *
 * <p>Catégories alignées sur le classeur « GESTION DES FACTURES CS_EPS » (un onglet par
 * catégorie) et sur les 9 régimes du document « Fonctionnalités SEN-CSU » : Classique,
 * Enfants 0-5 ans, Césarienne, Dialyse péritonéale, Hémodialyse, BSF, CEC, Sésame,
 * Ndongo Dara.</p>
 *
 * <p>Chaque régime porte aussi ses règles automatiques (§4 du document) : contrôle d'âge,
 * forfait, matricule. Les regrouper ici évite qu'elles se dispersent en conditions
 * éparpillées dans les services de facturation.</p>
 */
public enum Regime {

    CONTRIBUTIF("Classique (Contributif)", 0.20),
    SESAME("Plan Sésame", 0.0),
    CESARIENNE("Césarienne", 0.0),
    ZERO_CINQ_ANS("Enfants de moins de 5 ans", 0.0),
    DIALYSE_PERITONEALE("Dialyse péritonéale", 0.0),
    HEMODIALYSE("Hémodialyse", 0.0),
    BSF("BSF (Bourse de Sécurité Familiale)", 0.0),
    CEC("CEC (Carte Égalité des Chances)", 0.0),
    /**
     * Régime contributif (cahier « Poste de Santé », §6.1) : 80 % SEN-CSU / 20 % patient,
     * comme le Classique. Il était à 0 % tant qu'il se confondait avec les élèves.
     */
    NDONGO_DARA("Ndongo Daara", 0.20),
    /** Régime contributif des élèves, distinct de Ndongo Daara depuis le module Poste de Santé. */
    ELEVES("Élèves", 0.20),

    /** @deprecated scindé en {@link #DIALYSE_PERITONEALE} / {@link #HEMODIALYSE} — conservé pour les données existantes. */
    @Deprecated
    DIALYSE("Dialyse (ancien)", 0.0),
    /**
     * BSF et CEC facturés ensemble. Abandonné par les centres (qui les distinguent), mais
     * c'est la catégorie du poste de santé : son cahier des charges les regroupe en une
     * seule facture mensuelle.
     */
    BSF_CEC("BSF / CEC", 0.0);

    private final String libelle;
    private final double tauxBeneficiaire;

    Regime(String libelle, double tauxBeneficiaire) {
        this.libelle = libelle;
        this.tauxBeneficiaire = tauxBeneficiaire;
    }

    public String getLibelle() {
        return libelle;
    }

    public String getTauxBeneficiaireLibelle() {
        return Math.round(tauxBeneficiaire * 100) + " %";
    }

    /** Régimes contributifs (Classique, Ndongo Daara, Élèves) = 20 % à la charge du bénéficiaire ; les autres 0 %. */
    public double tauxBeneficiaire() {
        return tauxBeneficiaire;
    }

    /** Complément à 100% de {@link #tauxBeneficiaire()} (80% pour CONTRIBUTIF, 100% sinon). */
    public double tauxSencsu() {
        return 1.0 - tauxBeneficiaire;
    }

    /**
     * Régimes soumis à un contrôle d'âge maximal à la saisie (§4).
     * {@code null} = aucun plafond.
     */
    public Integer ageMaximumAnnees() {
        return this == ZERO_CINQ_ANS ? 5 : null;
    }

    /**
     * Régimes soumis à un âge minimum : le Plan Sésame est réservé aux personnes âgées
     * de 60 ans et plus. {@code null} = aucun plancher.
     */
    public Integer ageMinimumAnnees() {
        return this == SESAME ? 60 : null;
    }

    /**
     * Régimes facturés au forfait : le montant ne dépend pas du détail des actes, il est
     * fixé par le paramétrage (voir {@code app.csu.forfait-zero-cinq-ans}).
     */
    public boolean estForfaitaire() {
        return this == ZERO_CINQ_ANS;
    }

    /** Le Plan Sésame attribue un matricule automatiquement au bénéficiaire (§4). */
    public boolean exigeMatricule() {
        return this == SESAME;
    }

    /** Classique, Ndongo Daara et Élèves : la répartition 80 / 20 du cahier « Poste de Santé ». */
    public boolean estContributif() {
        return this == CONTRIBUTIF || this == NDONGO_DARA || this == ELEVES;
    }

    /**
     * Régimes dont le bénéficiaire s'identifie par son code d'immatriculation, contrôlé
     * auprès du système de gestion des assurés avant toute facturation au poste.
     */
    public boolean exigeCarteCsu() {
        return estContributif() || this == BSF || this == CEC || this == BSF_CEC;
    }

    /**
     * La catégorie imprimée sur une carte CSU relève-t-elle de ce régime ? Une carte classique
     * (ou Ndongo Daara, élèves) ne s'emploie pas sur BSF / CEC, ni l'inverse ; une carte Sésame
     * seulement sur le Plan Sésame (refus depuis le 19/10/2026). {@code null} quand la catégorie
     * n'est pas reconnue : rien à opposer. Miroir de {@code categorieCompatible} côté écran.
     */
    public Boolean compatibleAvecCarte(String categorie) {
        String c = java.text.Normalizer.normalize(categorie == null ? "" : categorie, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(java.util.Locale.ROOT);
        java.util.Set<Regime> attendus;
        if (c.contains("CLASSIQUE") || c.contains("CONTRIBUTIF") || c.contains("NDONGO") || c.contains("DAARA")
                || c.contains("ELEVE")) {
            attendus = java.util.EnumSet.of(CONTRIBUTIF, NDONGO_DARA, ELEVES);
        } else if (c.contains("BSF") || c.contains("CEC")) {
            attendus = java.util.EnumSet.of(BSF, CEC, BSF_CEC);
        } else if (c.contains("SESAME")) {
            attendus = java.util.EnumSet.of(SESAME);
        } else {
            return null;
        }
        return attendus.contains(this);
    }

    /** Régimes encore proposés à la saisie (les régimes hérités sont exclus). */
    public static Regime[] regimesActifs() {
        return new Regime[]{CONTRIBUTIF, ZERO_CINQ_ANS, CESARIENNE, DIALYSE_PERITONEALE,
                HEMODIALYSE, BSF, CEC, SESAME, NDONGO_DARA, ELEVES};
    }
}
