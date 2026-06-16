package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RegionRepository extends JpaRepository<Region, String> {
    Optional<Region> findByCode(String code);
}
