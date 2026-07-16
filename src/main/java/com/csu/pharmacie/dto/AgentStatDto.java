package com.csu.pharmacie.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStatDto {
    private String id;
    private String nom;
    private String structure;
    private long dossiersTraites; // correspond aux enregistrements (patients)
    private long lettresEmises;
    private long feuillesSoins;
    private long anomalies;
    private double tempsMoyen; // en minutes
    private double heuresTravailleesSemaine; // pointages
}
