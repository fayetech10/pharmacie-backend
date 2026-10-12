package com.csu.pharmacie.identite.depot;

import com.csu.pharmacie.entity.District;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepotDistrict extends JpaRepository<District, String> {

    Optional<District> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<District> findByRegionId(String regionId);

    List<District> findByActifTrue();
}
