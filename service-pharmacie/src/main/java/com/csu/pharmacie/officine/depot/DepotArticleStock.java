package com.csu.pharmacie.officine.depot;

import com.csu.pharmacie.entity.ArticleStock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DepotArticleStock extends JpaRepository<ArticleStock, String> {

    Optional<ArticleStock> findByStructureIdAndCodeIgnoreCase(String structureId, String code);

    List<ArticleStock> findByStructureId(String structureId);

    /** Articles sous le seuil d'alerte — écran de réapprovisionnement. */
    @Query("""
            select a from ArticleStock a
            where a.structureId = :structureId and a.actif = true
              and a.seuilAlerte > 0 and a.quantite <= a.seuilAlerte
            order by a.quantite asc
            """)
    List<ArticleStock> enAlerte(@Param("structureId") String structureId);
}
