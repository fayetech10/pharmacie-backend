package com.csu.pharmacie.entity;

public enum StatutMedicament {
    ELIGIBLE,
    EXCLU,
    // Médicament ajouté par un pharmacien mais ne figurant ni dans les éligibles ni dans
    // les exclusions : en attente de classement (validation ou exclusion) par la SEN-CSU.
    NON_REPERTORIE
}
