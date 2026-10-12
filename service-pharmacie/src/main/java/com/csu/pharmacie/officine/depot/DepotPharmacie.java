package com.csu.pharmacie.officine.depot;

import com.csu.pharmacie.entity.Pharmacie;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepotPharmacie extends JpaRepository<Pharmacie, String> {

    Optional<Pharmacie> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<Pharmacie> findByStructureId(String structureId);

    List<Pharmacie> findByDistrictId(String districtId);
}
