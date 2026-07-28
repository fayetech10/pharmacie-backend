package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.FeuilleSoins;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface FeuilleSoinsRepository extends JpaRepository<FeuilleSoins, String> {
    Optional<FeuilleSoins> findByNumero(String numero);
    List<FeuilleSoins> findByPatientId(String patientId);
    List<FeuilleSoins> findByCreatedBy(String createdBy);
    List<FeuilleSoins> findByCreatedByIn(Collection<String> createdBy);
    List<FeuilleSoins> findByStructureSanitaireId(String structureSanitaireId);
    List<FeuilleSoins> findByLettreGarantieId(String lettreGarantieId);
    long countByCreatedBy(String createdBy);
    long countByStructureSanitaireIdIn(Collection<String> structureSanitaireIds);

    /** Nombre de feuilles par créateur, en une seule requête (stats agents). */
    @org.springframework.data.jpa.repository.Query(
            "SELECT f.createdBy, COUNT(f) FROM FeuilleSoins f WHERE f.createdBy IN :createurs GROUP BY f.createdBy")
    List<Object[]> countParCreateur(@org.springframework.data.repository.query.Param("createurs") Collection<String> createurs);
}
