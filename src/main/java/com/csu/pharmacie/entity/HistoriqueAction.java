package com.csu.pharmacie.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoriqueAction {
    private LocalDateTime date;
    private String utilisateurId;
    private String utilisateurNom;
    private StatutFacture statut;
    private String commentaire;
}
