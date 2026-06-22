package com.csu.pharmacie.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Résultat d'un import de base (traité ou immatriculé) depuis un fichier Excel.
 * Import « ajout sans doublons » : on remonte le nombre de lignes importées,
 * de doublons ignorés, d'échecs, et le détail des erreurs par ligne.
 */
@Data
public class BaseImportResult {
    private int importes = 0;
    private int doublons = 0;
    private int echecs = 0;
    private List<String> erreurs = new ArrayList<>();

    public void incrementerImportes() {
        this.importes++;
    }

    public void incrementerDoublons() {
        this.doublons++;
    }

    public void ajouterErreur(String message) {
        this.erreurs.add(message);
        this.echecs++;
    }
}
