package com.csu.pharmacie.centre.dto;

import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.StatutFacture;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Compte rendu de l'import des factures déjà générées par l'ancienne application.
 *
 * <p>Contrairement à l'import du référentiel, c'est <b>tout ou rien</b> : une facture est un
 * tout (une facture à moitié reprise serait fausse), et une erreur n'importe où suffit à ne
 * rien enregistrer. On corrige le fichier et on le réimporte — sans risque de doublon,
 * puisqu'une facture déjà présente pour le même poste, régime et mois est refusée.</p>
 *
 * <p>{@link #factures} liste les factures reconnues dans le fichier, qu'elles aient été
 * enregistrées ou non : sans numéro tant que l'import a échoué, c'est un aperçu.</p>
 */
@Data
public class ResultatImportFactures {

    private String structureId;
    private String structureNom;
    private int lignesLues;
    private int facturesCreees;
    private int patients;
    private double montantTotal;
    private final List<FactureReconnue> factures = new ArrayList<>();
    private final List<String> erreurs = new ArrayList<>();
    /** Points à vérifier qui n'ont pas empêché l'import (prix vide, sexe illisible…). */
    private final List<String> avertissements = new ArrayList<>();

    public void erreur(int ligne, String message) {
        erreurs.add("Ligne " + ligne + " : " + message);
    }

    public void erreur(String message) {
        erreurs.add(message);
    }

    public void avertir(String message) {
        avertissements.add(message);
    }

    public boolean enErreur() {
        return !erreurs.isEmpty();
    }

    /**
     * Une facture reconnue dans le fichier (un régime, un mois). {@code id} et {@code numero}
     * ne sont renseignés qu'une fois la facture enregistrée.
     */
    public record FactureReconnue(String id, String numero, String numeroOrigine, Regime regime,
                                  String regimeLibelle, int mois, int annee, StatutFacture statut,
                                  String statutLibelle, int patients, int lignes, double montantTotal) {
    }
}
