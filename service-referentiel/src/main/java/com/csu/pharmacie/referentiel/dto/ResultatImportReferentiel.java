package com.csu.pharmacie.referentiel.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Compte rendu d'un import de référentiel.
 *
 * <p>Comme pour les structures, l'import n'est pas « tout ou rien » : les lignes valides
 * sont enregistrées et les lignes fautives listées avec leur numéro, pour être corrigées
 * dans le fichier d'origine puis réimportées.</p>
 */
@Data
public class ResultatImportReferentiel {

    private int lignesLues;
    private int crees;
    private int misAJour;
    private int servicesCrees;
    private final List<String> erreurs = new ArrayList<>();
    /** Points à vérifier qui n'ont pas empêché l'import (prix manquants, services douteux…). */
    private final List<String> avertissements = new ArrayList<>();

    public void erreur(int ligne, String message) {
        erreurs.add("Ligne " + ligne + " : " + message);
    }

    public void avertir(String message) {
        avertissements.add(message);
    }

    public String resume() {
        return "%d ligne(s) lue(s), %d création(s), %d mise(s) à jour, %d service(s) créé(s), %d erreur(s)"
                .formatted(lignesLues, crees, misAJour, servicesCrees, erreurs.size());
    }
}
