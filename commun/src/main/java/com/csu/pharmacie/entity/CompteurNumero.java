package com.csu.pharmacie.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Compteur séquentiel de numérotation des documents (lettres de garantie, bons de commande).
 * Une ligne par (type de document, code structure, année) — clé ex: "LG:CSREF01:2026".
 * Incrémenté sous verrou pessimiste pour garantir des numéros consécutifs sans doublon.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "compteurs_numero")
public class CompteurNumero {
    @Id
    private String cle;
    private long valeur;
}
