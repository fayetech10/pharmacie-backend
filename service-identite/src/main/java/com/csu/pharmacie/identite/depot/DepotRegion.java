package com.csu.pharmacie.identite.depot;

import org.springframework.data.jpa.repository.JpaRepository;
import com.csu.pharmacie.entity.Region;

import java.util.Optional;

public interface DepotRegion extends JpaRepository<Region, String> {

    Optional<Region> findByCodeIgnoreCase(String code);

    Optional<Region> findByNomIgnoreCase(String nom);
}
