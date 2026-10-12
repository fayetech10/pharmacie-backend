package com.csu.pharmacie.poste.depot;

import com.csu.pharmacie.entity.Beneficiaire;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DepotBeneficiaire extends JpaRepository<Beneficiaire, String> {

    Optional<Beneficiaire> findByStructureIdAndMatriculeIgnoreCase(String structureId, String matricule);

    Optional<Beneficiaire> findByStructureIdAndNumeroCniIgnoreCase(String structureId, String numeroCni);

    /** Recherche par nom, prénom, matricule ou CNI, cloisonnée à la structure. */
    @Query("""
            select b from Beneficiaire b
            where b.structureId = :structureId
              and (:recherche = '' or lower(b.nom) like concat('%', :recherche, '%')
                   or lower(b.prenom) like concat('%', :recherche, '%')
                   or lower(b.matricule) like concat('%', :recherche, '%')
                   or lower(b.numeroCni) like concat('%', :recherche, '%'))
            """)
    Page<Beneficiaire> rechercher(@Param("structureId") String structureId,
                                  @Param("recherche") String recherche,
                                  Pageable pageable);
}
