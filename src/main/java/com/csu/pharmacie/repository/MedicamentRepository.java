package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.Medicament;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MedicamentRepository extends JpaRepository<Medicament, String> {
    List<Medicament> findTop10ByNomContainingIgnoreCaseAndActifTrue(String query);
    Optional<Medicament> findByNomIgnoreCase(String nom);
    Optional<Medicament> findByCode(String code);
    Optional<Medicament> findByCodeIgnoreCase(String code);
}
