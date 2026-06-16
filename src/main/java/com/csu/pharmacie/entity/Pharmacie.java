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
    private String responsableId;
    @Builder.Default
    @Column(nullable = false)
    private boolean actif = true;
    private LocalDateTime createdAt;
}
