package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.StructureSanitaire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StructureSanitaireRepository extends JpaRepository<StructureSanitaire, String> {
    Optional<StructureSanitaire> findByCode(String code);
    List<StructureSanitaire> findByRegionId(String regionId);
    List<StructureSanitaire> findByBcsuId(String bcsuId);
}
