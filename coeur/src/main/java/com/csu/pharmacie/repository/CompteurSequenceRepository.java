package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.CompteurSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Compteurs des numéros de facture mensuelle ({@code FS-AAAAMM-NNNNN}), partagés avec les
 * microservices : le cœur y prend un numéro quand la saisie du BCSU ouvre la facture du
 * mois d'un centre, sans risque de collision avec ceux que numérote service-centre-sante.
 */
@Repository
public interface CompteurSequenceRepository extends JpaRepository<CompteurSequence, String> {
}
