package com.csu.pharmacie.referentiel.depot;

import com.csu.pharmacie.entity.ServiceMedical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepotServiceMedical extends JpaRepository<ServiceMedical, String> {

    Optional<ServiceMedical> findByCodeIgnoreCase(String code);

    Optional<ServiceMedical> findByNomIgnoreCase(String nom);

    boolean existsByCodeIgnoreCase(String code);

    List<ServiceMedical> findByActifTrue();
}
