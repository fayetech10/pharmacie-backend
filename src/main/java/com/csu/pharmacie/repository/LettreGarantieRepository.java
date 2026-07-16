package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.LettreGarantie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LettreGarantieRepository extends JpaRepository<LettreGarantie, String> {
    Optional<LettreGarantie> findByNumero(String numero);
    List<LettreGarantie> findByCreatedBy(String createdBy);
    List<LettreGarantie> findByCreatedByIn(Collection<String> createdBy);
    List<LettreGarantie> findByPatientId(String patientId);
    long countByCreatedBy(String createdBy);
}
