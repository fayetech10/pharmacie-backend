package com.csu.pharmacie.dto;

import com.csu.pharmacie.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO de réponse pour les pointages, enrichi du nom de la structure de rattachement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PointageDto {
    private String id;
    private String userId;
    private String userNom;
    private String userPrenom;
    private Role role;
    private LocalDate date;
    private LocalDateTime heureArrivee;
    private LocalDateTime heureDepart;
    private LocalDateTime derniereConnexion;
    private String structureNom;
}
