package com.csu.pharmacie.entity;

/**
 * Rôles applicatifs.
 *
 * <p>Depuis le découpage en microservices, chaque rôle est servi par un service précis :
 * {@link #PHARMACIEN} par service-pharmacie, {@link #POSTE_SANTE} par service-poste-sante,
 * {@link #CENTRE_SANTE} par service-centre-sante. La passerelle aiguille sur cette base.</p>
 *
 * <p>{@link #STRUCTURE_SANITAIRE} est le rôle générique historique : tous les comptes de
 * structure existants le portent. Il est conservé tel quel et se résout dynamiquement en
 * {@link #POSTE_SANTE} ou {@link #CENTRE_SANTE} selon le type de la structure rattachée
 * (voir {@link #effectif}), de sorte qu'aucun compte n'a besoin d'être migré.</p>
 */
public enum Role {

    PHARMACIEN,
    SERVICE_REGIONAL,
    SERVICE_CENTRAL,
    ADMIN,
    BCSU,
    /** Échelon de consolidation entre la structure et la région (ajouté avec les districts). */
    DISTRICT,
    /** Agent d'un poste de santé — soumet ses factures à son centre de rattachement. */
    POSTE_SANTE,
    /** Agent d'un centre de santé / EPS — facture et valide les factures de ses postes. */
    CENTRE_SANTE,
    /** Rôle générique historique — résolu à la connexion via {@link #effectif}. */
    STRUCTURE_SANITAIRE;

    /**
     * Rôle réellement appliqué, une fois le type de structure connu.
     * Les autres rôles sont renvoyés inchangés.
     */
    public static Role effectif(Role role, TypeStructure type) {
        if (role != STRUCTURE_SANITAIRE || type == null) {
            return role;
        }
        return type == TypeStructure.PS ? POSTE_SANTE : CENTRE_SANTE;
    }

    /** Rôles autorisés à facturer depuis un point de soins (poste, centre, hôpital). */
    public boolean estStructureSanitaire() {
        return this == POSTE_SANTE || this == CENTRE_SANTE || this == STRUCTURE_SANITAIRE;
    }

    /** Rôles de supervision : lecture élargie, aucune saisie de facture. */
    public boolean estSuperviseur() {
        return this == DISTRICT || this == SERVICE_REGIONAL || this == SERVICE_CENTRAL || this == ADMIN;
    }
}
