package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PatientRepository extends JpaRepository<Patient, String> {
    Optional<Patient> findByNumeroCni(String numeroCni);
    Optional<Patient> findByNumeroAssure(String numeroAssure);
    List<Patient> findByNomContainingIgnoreCaseOrPrenomContainingIgnoreCase(String nom, String prenom);

    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT p FROM Patient p WHERE p.id IN (SELECT f.patientId FROM FeuilleSoins f WHERE f.structureSanitaireId = :structureId)")
    List<Patient> findDistinctByStructureSanitaireId(String structureId);

    long countByCreatedBy(String createdBy);
    List<Patient> findByNumeroAssureIn(Collection<String> numeroAssures);
    List<Patient> findByNumeroCniIn(Collection<String> numeroCnis);
}
