package com.csu.pharmacie.identite.depot;

import org.springframework.data.jpa.repository.JpaRepository;
import com.csu.pharmacie.entity.StructureSanitaire;

import java.util.List;
import java.util.Optional;

public interface DepotStructure extends JpaRepository<StructureSanitaire, String> {

    Optional<StructureSanitaire> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    /** Postes de santé polarisés par un centre — le cœur du circuit poste → centre. */
    List<StructureSanitaire> findByStructureRattachementId(String structureRattachementId);

    /** Structures dont l'agent BCSU référent est cet utilisateur. */
    List<StructureSanitaire> findByBcsuId(String bcsuId);

    List<StructureSanitaire> findByDistrictId(String districtId);

    List<StructureSanitaire> findByRegionId(String regionId);

    List<StructureSanitaire> findByTypeStructureIgnoreCase(String typeStructure);

    List<StructureSanitaire> findByDistrictIdAndTypeStructureIgnoreCase(String districtId, String typeStructure);

    long countByDistrictId(String districtId);
}
