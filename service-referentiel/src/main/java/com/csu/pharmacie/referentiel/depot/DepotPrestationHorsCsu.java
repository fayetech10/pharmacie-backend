package com.csu.pharmacie.referentiel.depot;

import com.csu.pharmacie.entity.PrestationHorsCsu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DepotPrestationHorsCsu extends JpaRepository<PrestationHorsCsu, String> {

    Optional<PrestationHorsCsu> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<PrestationHorsCsu> findByActifTrue();

    /**
     * Le tarif d'une structure pour ce code. {@code structureId} nul = la ligne nationale.
     *
     * <p>Spring Data traduit un argument nul en {@code IS NULL} pour les méthodes dérivées :
     * pas de piège de typage Postgres ici, contrairement à un {@code :param IS NULL} écrit
     * à la main dans un {@code @Query}.</p>
     */
    Optional<PrestationHorsCsu> findByCodeIgnoreCaseAndStructureId(String code, String structureId);

    /** Tarifs que la structure s'est fixés — ce qu'elle administre dans son écran Paramètres. */
    List<PrestationHorsCsu> findByStructureIdOrderByCategorieAscDesignationAsc(String structureId);

    /** Lignes nationales réservées à un type de structure (ex. « PS »), actives ou non. */
    List<PrestationHorsCsu> findByStructureIdIsNullAndTypeStructureOrderByCategorieAscDesignationAsc(String typeStructure);

    /** Tarifs nationaux, complétés des surcharges propres à la structure. */
    @Query("""
            select p from PrestationHorsCsu p
            where p.actif = true and (p.structureId is null or p.structureId = :structureId)
            order by p.categorie, p.designation
            """)
    List<PrestationHorsCsu> applicablesA(@Param("structureId") String structureId);
}
