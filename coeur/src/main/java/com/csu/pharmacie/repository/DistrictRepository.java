package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.District;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Districts sanitaires (gérés par service-identite) : le cœur ne fait qu'en lire le nom. */
@Repository
public interface DistrictRepository extends JpaRepository<District, String> {
}
