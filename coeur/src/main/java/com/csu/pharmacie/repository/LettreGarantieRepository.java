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
    long countByCreatedByIn(Collection<String> createdBy);

    /** Nombre de lettres par créateur, en une seule requête (stats agents). */
    @org.springframework.data.jpa.repository.Query(
            "SELECT l.createdBy, COUNT(l) FROM LettreGarantie l WHERE l.createdBy IN :createurs GROUP BY l.createdBy")
    List<Object[]> countParCreateur(@org.springframework.data.repository.query.Param("createurs") Collection<String> createurs);
}
