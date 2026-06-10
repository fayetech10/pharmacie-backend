package com.csu.pharmacie.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "pharmacies")
public class Pharmacie {
    @Id
    private String id;
    private String code;
    private String nom;
    private String adresse;
    private String telephone;
    private String email;
    private String regionId;
    private String responsableId;
    @Builder.Default
    private boolean actif = true;
    private LocalDateTime createdAt;
}
