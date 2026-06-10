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
@Document(collection = "users")
public class User {
    @Id
    private String id;
    private String nom;
    private String prenom;
    private String email;
    private String password;
    private Role role;
    private String pharmacieId;
    private String regionId;
    @Builder.Default
    private boolean actif = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
