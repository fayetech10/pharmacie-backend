package com.csu.pharmacie.officine.depot;

import com.csu.pharmacie.entity.MouvementStock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DepotMouvementStock extends JpaRepository<MouvementStock, String> {

    /** Clé d'idempotence de la sortie automatique. */
    Optional<MouvementStock> findByReferenceLigne(String referenceLigne);

    List<MouvementStock> findByArticleIdOrderByCreatedAtDesc(String articleId);

    List<MouvementStock> findByStructureIdAndDateBetweenOrderByDateDesc(String structureId,
                                                                        LocalDate debut, LocalDate fin);
}
