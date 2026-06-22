package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.BaseImmatricule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BaseImmatriculeRepository extends JpaRepository<BaseImmatricule, String> {
    List<BaseImmatricule> findByRegionIdOrderByImportedAtDesc(String regionId);

    /** Déduplication par clé naturelle (Code Immatriculation) au sein d'une région. */
    boolean existsByRegionIdAndCodeImmatriculation(String regionId, String codeImmatriculation);
}
