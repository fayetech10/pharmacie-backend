package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
@Table(name = "pharmacies")
public class Pharmacie {
    @Id
    @UuidGenerator
    private String id;
    private String code;
    private String nom;
    private String adresse;
    private String telephone;
    private String email;
    private String regionId;
    /**
     * Structure sanitaire desservie, pour un dépôt de poste ou de centre.
     * C'est cette clé qui permet à une facturation venue d'un point de soins de trouver
     * le bon stock à décrémenter (§14). Nulle pour une officine privée.
     */
    private String structureId;
    /** District de rattachement — maille de consolidation (§9). */
    private String districtId;
    private String responsableId;
    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;
    private LocalDateTime createdAt;
}
