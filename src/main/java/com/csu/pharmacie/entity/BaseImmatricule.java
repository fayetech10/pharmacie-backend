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

/**
 * Ligne de la « base immatriculé & imprimé » importée depuis un fichier Excel par
 * le service régional de Thiès. Stockage texte tolérant (cf. {@link BaseTraite}).
 *
 * Colonnes Excel attendues (1re ligne = en-têtes, ignorée) :
 * ID | Date Enregistrement | Code Immatriculation | Nom | Prénom | Date Naissance |
 * Sexe | Téléphone | Adresse | Régime | Assureur | Type Bénéficiaire | Date Cotisation |
 * Date Fin Cotisation | QR Code URL | Region | Departement | Commune | Groupe |
 * Type_Adhesion | Type_Cotisation | CNI | Photo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "base_immatricule", indexes = {
        @Index(name = "idx_base_immat_region", columnList = "region_id"),
        // Accélère la déduplication par clé naturelle (Code Immatriculation) dans une région.
        @Index(name = "idx_base_immat_dedup", columnList = "region_id, code_immatriculation")
})
public class BaseImmatricule {
    @Id
    @UuidGenerator
    private String id;

    /** Colonne « ID » du fichier Excel (identifiant métier, distinct de la PK). */
    private String externalId;
    private String dateEnregistrement;
    private String codeImmatriculation;
    private String nom;
    private String prenom;
    private String dateNaissance;
    private String sexe;
    private String telephone;
    private String adresse;
    private String regime;
    private String assureur;
    private String typeBeneficiaire;
    private String dateCotisation;
    private String dateFinCotisation;
    // URL (ou image base64) du QR code : on évite la limite varchar(255).
    @Column(columnDefinition = "text")
    private String qrCodeUrl;
    private String region;
    private String departement;
    private String commune;
    private String groupe;
    private String typeAdhesion;
    private String typeCotisation;
    private String cni;
    @Column(columnDefinition = "text")
    private String photo;

    // ----- Métadonnées d'import -----
    /** Région propriétaire (région du service régional ayant importé : Thiès). */
    private String regionId;
    private LocalDateTime importedAt;
    private String importedBy;
}
