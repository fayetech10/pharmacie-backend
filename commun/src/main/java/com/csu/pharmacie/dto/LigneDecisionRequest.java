package com.csu.pharmacie.dto;

import lombok.Data;

@Data
public class LigneDecisionRequest {
    // true = accepter la ligne, false = rejeter la ligne
    private boolean accepter;
    // Obligatoire lorsque accepter == false
    private String motif;
}
