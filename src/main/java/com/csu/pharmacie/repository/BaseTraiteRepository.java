package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.BaseTraite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BaseTraiteRepository extends JpaRepository<BaseTraite, String> {
    List<BaseTraite> findByRegionIdOrderByImportedAtDesc(String regionId);

    /** Déduplication par clé naturelle (CNI/Extrait + Nom) au sein d'une région. */
    boolean existsByRegionIdAndCniExtraitAndNom(String regionId, String cniExtrait, String nom);
}
