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
 * Ligne de la « base traité » importée depuis un fichier Excel par le service
 * régional de Thiès. Les colonnes métier sont stockées telles quelles (texte),
 * l'import étant tolérant : aucune validation de format de date n'est imposée.
 *
 * Colonnes Excel attendues (1re ligne = en-têtes, ignorée) :
 * Region | Departement | Commune | Nom | Prenom | Date_Naissance | Lieu_Naissance |
 * Sexe | Telephone | Adresse | CNI/Extrait | Assureur | Regime | Type_Adhesion |
 * Type_Beneficiaire | Type_Cotisation | Date_Cotisation | Photo | Index
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "base_traite", indexes = {
        @Index(name = "idx_base_traite_region", columnList = "region_id"),
        // Accélère la déduplication par clé naturelle (CNI/Extrait + Nom) dans une région.
        @Index(name = "idx_base_traite_dedup", columnList = "region_id, cni_extrait, nom")
})
public class BaseTraite {
    @Id
    @UuidGenerator
    private String id;

    private String region;
    private String departement;
    private String commune;
    private String nom;
    private String prenom;
    private String dateNaissance;
    private String lieuNaissance;
    private String sexe;
    private String telephone;
    private String adresse;
    private String cniExtrait;
    private String assureur;
    private String regime;
    private String typeAdhesion;
    private String typeBeneficiaire;
    private String typeCotisation;
    private String dateCotisation;
    // Peut contenir une URL ou une image base64 : on évite la limite varchar(255).
    @Column(columnDefinition = "text")
    private String photo;
    private String indexExcel;

    // ----- Métadonnées d'import -----
    /** Région propriétaire (région du service régional ayant importé : Thiès). */
    private String regionId;
    private LocalDateTime importedAt;
    private String importedBy;
}
