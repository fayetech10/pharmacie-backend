package com.csu.pharmacie.entity;

/**
 * Niveau de la pyramide sanitaire auquel appartient une structure.
 *
 * <p>Le rattachement se lit de bas en haut : {@link #PS} → {@link #CS}/{@link #EPS}
 * → District → Région → Niveau central. Un poste de santé ne transmet jamais
 * directement au service régional : il passe par son centre de rattachement.</p>
 */
public enum TypeStructure {

    /** Poste de santé — facture, puis soumet à son centre de rattachement. */
    PS("Poste de santé"),
    /** Centre de santé — facture pour lui-même ET valide les factures de ses postes polarisés. */
    CS("Centre de santé"),
    /** Établissement public de santé (hôpital) — se comporte comme un centre de santé. */
    EPS("Établissement public de santé");

    private final String libelle;

    TypeStructure(String libelle) {
        this.libelle = libelle;
    }

    public String getLibelle() {
        return libelle;
    }

    /** Vrai pour les structures qui polarisent des postes de santé (CS et EPS). */
    public boolean polarise() {
        return this == CS || this == EPS;
    }

    /**
     * Une facture de ce régime passe-t-elle par le district avant le service régional ? Le
     * régime « Enfants de moins de 5 ans » des postes (09/10/2026) et des centres de santé
     * (12/10/2026) ; jamais un EPS, ni un autre régime.
     */
    public boolean passeParLeDistrict(Regime regime) {
        return (this == PS || this == CS) && regime == Regime.ZERO_CINQ_ANS;
    }

    /** Tolérant : accepte « PS », « ps », « POSTE_SANTE », null → null. */
    public static TypeStructure depuisCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalise = code.trim().toUpperCase();
        return switch (normalise) {
            case "PS", "POSTE", "POSTE_SANTE", "POSTE DE SANTE" -> PS;
            case "CS", "CENTRE", "CENTRE_SANTE", "CENTRE DE SANTE" -> CS;
            case "EPS", "HOPITAL", "HÔPITAL" -> EPS;
            default -> null;
        };
    }
}
