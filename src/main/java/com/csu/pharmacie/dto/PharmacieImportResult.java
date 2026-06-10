package com.csu.pharmacie.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Résultat d'un import de pharmacies depuis un fichier Excel.
 * Contient le nombre de lignes importées avec succès, le nombre d'échecs
 * et le détail des erreurs (par ligne) pour affichage à l'utilisateur.
 */
@Data
public class PharmacieImportResult {
    private int importes = 0;
    private int echecs = 0;
    private List<String> erreurs = new ArrayList<>();

    public void ajouterErreur(String message) {
        this.erreurs.add(message);
        this.echecs++;
    }

    public void incrementerImportes() {
        this.importes++;
    }
}
