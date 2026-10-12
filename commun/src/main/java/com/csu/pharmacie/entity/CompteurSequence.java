package com.csu.pharmacie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Compteur persistant d'une séquence de numérotation.
 *
 * <p>La clé porte le préfixe et la période ({@code FP-202608}), ce qui remet le compteur
 * à 1 chaque mois — c'est la présentation attendue sur les factures. Le {@code @Version}
 * fait échouer deux incréments concurrents plutôt que de leur attribuer le même numéro.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "compteurs_sequence")
public class CompteurSequence {

    @Id
    private String cle;

    @Column(nullable = false)
    private long valeur;

    @Version
    private Long version;
}
