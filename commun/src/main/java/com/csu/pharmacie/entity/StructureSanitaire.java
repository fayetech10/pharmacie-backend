package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "structures_sanitaires", indexes = {
        @Index(name = "idx_structure_region", columnList = "region_id"),
        @Index(name = "idx_structure_bcsu", columnList = "bcsu_id"),
        @Index(name = "idx_structure_rattachement", columnList = "structure_rattachement_id")
})
public class StructureSanitaire {
    @Id
    @UuidGenerator
    private String id;
    private String code;
    private String nom;
    private String adresse;
    private String telephone;
    private String email;
    private String regionId;
    // Type d'établissement : CS (centre de santé), EPS (hôpital), PS (poste de santé).
    private String typeStructure;
    // Pour un poste de santé (PS) : centre de santé / EPS de rattachement.
    // Les factures envoyées par le poste sont visibles dans cette structure.
    private String structureRattachementId;
    /**
     * District sanitaire de rattachement (§6 et §9 du document « Fonctionnalités SEN-CSU »).
     *
     * <p>À ne pas confondre avec {@link #structureRattachementId} : celui-ci définit le
     * circuit de <em>validation</em> (le poste passe par son centre), le district définit
     * la maille de <em>consolidation</em>, qui regroupe postes et centres indépendamment
     * du circuit.</p>
     */
    private String districtId;
    // Agent BCSU (utilisateur, rôle BCSU) auquel la structure est rattachée :
    // la structure ne peut facturer que sur les lettres de garantie émises par ce BCSU.
    private String bcsuId;
    private String responsableId;
    /**
     * Centre de santé désigné « siège du district » (au plus un par district) : son compte
     * exerce aussi les fonctions du district — consolidation et contrôle des factures des
     * postes. Nullable (= non) pour que la colonne s'ajoute sans toucher aux lignes existantes.
     */
    private Boolean siegeDistrict;
    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Type sous forme typée. La colonne reste une chaîne — c'est ce que manipule tout le
     * code existant, et la migrer n'apporterait rien — mais les nouveaux traitements
     * (aiguillage poste/centre, consolidation) raisonnent sur l'énumération.
     */
    public TypeStructure typeEffectif() {
        return TypeStructure.depuisCode(typeStructure);
    }

    /** Vrai si c'est un centre de santé désigné siège de son district. */
    public boolean estSiegeDistrict() {
        return Boolean.TRUE.equals(siegeDistrict) && typeEffectif() == TypeStructure.CS
                && districtId != null && !districtId.isBlank();
    }

    /** Vrai si la structure polarise des postes de santé (centre de santé ou EPS). */
    public boolean polarise() {
        TypeStructure type = typeEffectif();
        return type != null && type.polarise();
    }
}
