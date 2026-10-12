package com.csu.pharmacie.commun.securite;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.TypeStructure;

/**
 * Identité de l'appelant, reconstruite à partir des seuls claims du JWT.
 *
 * <p>C'est le point clé du découpage : aucun service n'interroge la base des
 * utilisateurs pour authentifier une requête. Le jeton porte tout le contexte
 * (rôle, structure, rattachement, district, région), donc service-poste-sante
 * peut refuser un accès sans dépendre de service-identite — et reste disponible
 * même si ce dernier est arrêté.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UtilisateurAuthentifie {

    private String id;
    private String email;
    private String nom;
    private String prenom;
    private Role role;

    /** Structure sanitaire de rattachement (rôles POSTE_SANTE / CENTRE_SANTE). */
    private String structureId;
    private String structureNom;
    private TypeStructure typeStructure;
    /** Pour un poste de santé : identifiant du centre de santé / EPS qui le polarise. */
    private String structureRattachementId;

    private String districtId;
    private String regionId;
    private String pharmacieId;

    /**
     * Mot de passe posé par un tiers, pas encore remplacé par le titulaire du compte.
     * Tant que c'est vrai, {@code FiltreJwt} ne laisse passer que la régularisation.
     */
    private boolean motDePasseAChanger;

    /**
     * Compte d'un centre de santé désigné « siège du district » : en plus de son espace centre,
     * il exerce les fonctions du district (consolidation, contrôle des factures des postes)
     * sur SON district ({@link #districtId}). Voir {@link #aCapaciteDistrict()}.
     */
    private boolean siegeDistrict;

    /** Vrai pour un compte district ou pour le centre siège de son district (double casquette). */
    public boolean aCapaciteDistrict() {
        return role == Role.DISTRICT || (siegeDistrict && districtId != null && !districtId.isBlank());
    }

    public String nomComplet() {
        return ((prenom == null ? "" : prenom) + " " + (nom == null ? "" : nom)).trim();
    }

    /** Rôle réellement appliqué : le rôle générique historique est résolu via le type de structure. */
    public Role roleEffectif() {
        return Role.effectif(role, typeStructure);
    }

    public boolean estPoste() {
        return roleEffectif() == Role.POSTE_SANTE;
    }

    public boolean estCentre() {
        return roleEffectif() == Role.CENTRE_SANTE;
    }

    public boolean estAdmin() {
        return role == Role.ADMIN;
    }
}
