package com.csu.pharmacie.dto;

import com.csu.pharmacie.entity.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DossierPatientDto {
    private Patient patient;
    private List<LettreGarantie> lettresGarantie;
    private List<BonCommande> bonsCommande;
    private List<LigneFacture> lignesFactureOfficine;
    private List<FactureStructure> facturesStructure;
    private List<FeuilleSoins> feuillesSoins;
    /**
     * Photo du porteur reprise de sa carte CSU lors d'un contrôle d'éligibilité en Espace PS
     * (data URL), retrouvée par son n° d'assuré ; {@code null} s'il n'y en a pas.
     */
    private String photoAssure;
}
