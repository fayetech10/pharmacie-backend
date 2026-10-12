package com.csu.pharmacie.identite.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Compte rendu d'un import Excel.
 *
 * <p>Un import de masse ne doit jamais être « tout ou rien » : sur un fichier de 300
 * structures, une faute de frappe sur une ligne ne peut pas annuler les 299 autres.
 * Les lignes valides sont donc créées et les lignes fautives listées ici, avec leur
 * numéro, pour être corrigées et réimportées.</p>
 */
@Data
public class ResultatImport {

    private int lignesLues;
    private int crees;
    private int misAJour;
    private int ignores;
    private final List<String> erreurs = new ArrayList<>();
    /** Identifiants de connexion générés (créations en masse de comptes). */
    private final List<String> comptesCrees = new ArrayList<>();

    public void erreur(int ligne, String message) {
        erreurs.add("Ligne " + ligne + " : " + message);
    }

    public boolean estEnEchecTotal() {
        return crees == 0 && misAJour == 0 && !erreurs.isEmpty();
    }

    public String resume() {
        return "%d ligne(s) lue(s), %d création(s), %d mise(s) à jour, %d ignorée(s), %d erreur(s)"
                .formatted(lignesLues, crees, misAJour, ignores, erreurs.size());
    }
}
