package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/**
 * Acte tarifé (§5). Le prix porté ici fait foi : c'est lui qui est réappliqué à chaque
 * enregistrement de facture, quel que soit le montant envoyé par le client.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "prestations", indexes = {
        @Index(name = "idx_prestation_code", columnList = "code", unique = true),
        @Index(name = "idx_prestation_service", columnList = "service_id")
})
public class Prestation {

    @Id
    @UuidGenerator
    private String id;

    @Column(unique = true, nullable = false)
    private String code;

    @Column(nullable = false)
    private String designation;

    private String serviceId;

    /** Prix unitaire en FCFA. */
    private double prix;

    /**
     * Prestation servie par la pharmacie dépositaire (§5 et §14) : sa facturation
     * déclenche une sortie de stock sur {@link #medicamentId}.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean depositaire = false;

    /** Article de stock associé, obligatoire quand {@link #depositaire} est vrai. */
    private String medicamentId;

    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void avantCreation() {
        LocalDateTime maintenant = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = maintenant;
        }
        updatedAt = maintenant;
    }

    @PreUpdate
    void avantMiseAJour() {
        updatedAt = LocalDateTime.now();
    }
}
