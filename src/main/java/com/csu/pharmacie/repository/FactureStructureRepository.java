package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.Regime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FactureStructureRepository extends JpaRepository<FactureStructure, String> {
    Optional<FactureStructure> findByNumero(String numero);
    List<FactureStructure> findByStructureSanitaireId(String structureSanitaireId);
    List<FactureStructure> findByRegionId(String regionId);
    List<FactureStructure> findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(
            String structureSanitaireId, Regime regime, int mois, int annee);

    @Query(value = "SELECT COUNT(*) FROM factures_structure f WHERE f.structure_sanitaire_id = :structureId " +
            "AND f.statut != 'REJETEE_SR' AND CAST(f.lignes AS VARCHAR) LIKE CONCAT('%', :lettreId, '%')", nativeQuery = true)
    long countByStructureAndLettreGarantieNative(@Param("structureId") String structureId, @Param("lettreId") String lettreId);

    default boolean existsByStructureAndLettreGarantie(String structureId, String lettreId) {
        return countByStructureAndLettreGarantieNative(structureId, lettreId) > 0;
    }

    @Query(value = "SELECT * FROM factures_structure f WHERE CAST(f.lignes AS VARCHAR) LIKE CONCAT('%', :lettreId, '%')", nativeQuery = true)
    List<FactureStructure> findByLettreGarantieId(@Param("lettreId") String lettreId);
}
