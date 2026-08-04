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

    // ── Listes filtrées EN BASE (0 = pas de filtre ; regime null = tous) ────────
    // Remplacent le chargement complet suivi de filtres en mémoire.
    @Query("SELECT f FROM FactureStructure f WHERE f.structureSanitaireId = :structureId "
            + "AND (:mois = 0 OR f.mois = :mois) AND (:annee = 0 OR f.annee = :annee) "
            + "AND (CAST(:regime AS string) IS NULL OR f.regime = :regime)")
    List<FactureStructure> findByStructureFiltre(@Param("structureId") String structureId,
                                                 @Param("mois") int mois, @Param("annee") int annee,
                                                 @Param("regime") Regime regime);

    /** Vue Service Régional : uniquement les factures déjà transmises au niveau régional. */
    @Query("SELECT f FROM FactureStructure f WHERE f.regionId = :regionId "
            + "AND f.statut NOT IN (com.csu.pharmacie.entity.StatutFacture.BROUILLON, "
            + "com.csu.pharmacie.entity.StatutFacture.SOUMISE_CS, "
            + "com.csu.pharmacie.entity.StatutFacture.REJETEE_CS) "
            + "AND (:mois = 0 OR f.mois = :mois) AND (:annee = 0 OR f.annee = :annee) "
            + "AND (CAST(:regime AS string) IS NULL OR f.regime = :regime)")
    List<FactureStructure> findVisiblesParRegionFiltre(@Param("regionId") String regionId,
                                                       @Param("mois") int mois, @Param("annee") int annee,
                                                       @Param("regime") Regime regime);

    @Query("SELECT f FROM FactureStructure f WHERE (:mois = 0 OR f.mois = :mois) "
            + "AND (:annee = 0 OR f.annee = :annee) AND (CAST(:regime AS string) IS NULL OR f.regime = :regime)")
    List<FactureStructure> findAllFiltre(@Param("mois") int mois, @Param("annee") int annee,
                                         @Param("regime") Regime regime);

    // ── Projection « badges » : identifiant + statut, sans toucher au JSONB ──────
    // Le périmètre par rôle reproduit celui de getAllForCurrentUser(), exclusion
    // BROUILLON / SOUMISE_CS / REJETEE_CS comprise pour le service régional.
    @Query("SELECT new com.csu.pharmacie.dto.FactureStatutDto(f.id, f.statut) "
            + "FROM FactureStructure f WHERE f.structureSanitaireId = :structureId")
    List<com.csu.pharmacie.dto.FactureStatutDto> findStatutsByStructure(@Param("structureId") String structureId);

    @Query("SELECT new com.csu.pharmacie.dto.FactureStatutDto(f.id, f.statut) "
            + "FROM FactureStructure f WHERE f.regionId = :regionId "
            + "AND f.statut NOT IN (com.csu.pharmacie.entity.StatutFacture.BROUILLON, "
            + "com.csu.pharmacie.entity.StatutFacture.SOUMISE_CS, "
            + "com.csu.pharmacie.entity.StatutFacture.REJETEE_CS)")
    List<com.csu.pharmacie.dto.FactureStatutDto> findStatutsVisiblesParRegion(@Param("regionId") String regionId);

    @Query("SELECT new com.csu.pharmacie.dto.FactureStatutDto(f.id, f.statut) FROM FactureStructure f")
    List<com.csu.pharmacie.dto.FactureStatutDto> findAllStatuts();
    List<FactureStructure> findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(
            String structureSanitaireId, Regime regime, int mois, int annee);

    @Query(value = "SELECT COUNT(*) FROM factures_structure f WHERE f.structure_sanitaire_id = :structureId " +
            "AND f.statut != 'REJETEE_SR' AND CAST(f.lignes AS TEXT) LIKE CONCAT('%', CAST(:lettreId AS TEXT), '%')", nativeQuery = true)
    long countByStructureAndLettreGarantieNative(@Param("structureId") String structureId, @Param("lettreId") String lettreId);

    default boolean existsByStructureAndLettreGarantie(String structureId, String lettreId) {
        return countByStructureAndLettreGarantieNative(structureId, lettreId) > 0;
    }

    @Query(value = "SELECT * FROM factures_structure f WHERE CAST(f.lignes AS TEXT) LIKE CONCAT('%', CAST(:lettreId AS TEXT), '%')", nativeQuery = true)
    List<FactureStructure> findByLettreGarantieId(@Param("lettreId") String lettreId);
}
