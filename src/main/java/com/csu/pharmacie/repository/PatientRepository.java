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
    long countByCreatedByIn(Collection<String> createdBy);

    /** Patients créés par un ensemble d'utilisateurs (périmètre BCSU par structure). */
    List<Patient> findByCreatedByIn(Collection<String> createdBy);

    /** Nombre de patients par créateur, en une seule requête (stats agents). */
    @org.springframework.data.jpa.repository.Query(
            "SELECT p.createdBy, COUNT(p) FROM Patient p WHERE p.createdBy IN :createurs GROUP BY p.createdBy")
    List<Object[]> countParCreateur(@org.springframework.data.repository.query.Param("createurs") Collection<String> createurs);
    List<Patient> findByNumeroAssureIn(Collection<String> numeroAssures);
    List<Patient> findByNumeroCniIn(Collection<String> numeroCnis);
}
