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

/**
 * Service d'un point de soins (Consultation, Maternité, Laboratoire, Hospitalisation…).
 *
 * <p>Premier niveau du référentiel Service → Prestation → Prix (§5). Le regroupement par
 * service n'est pas décoratif : c'est lui qui alimente le filtre du formulaire de
 * facturation, sans lequel une liste de plusieurs centaines de prestations serait
 * inutilisable au comptoir.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "services_medicaux")
public class ServiceMedical {

    @Id
    @UuidGenerator
    private String id;

    @Column(unique = true, nullable = false)
    private String code;

    @Column(nullable = false)
    private String nom;

    /** Ordre d'affichage dans les listes déroulantes (à défaut : ordre alphabétique). */
    @Builder.Default
    private int ordre = 100;

    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;
}
