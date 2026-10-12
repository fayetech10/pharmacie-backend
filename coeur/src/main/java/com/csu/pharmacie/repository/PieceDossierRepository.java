package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.PieceDossier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Pièces des dossiers patients (table partagée avec les microservices). Le cœur y lit la
 * photo de l'assuré pour sa fiche patient, et y range les pièces photographiées par le BCSU
 * quand sa saisie crée le dossier d'un centre ou d'un EPS.
 */
@Repository
public interface PieceDossierRepository extends JpaRepository<PieceDossier, String> {

    /** Pièce la plus récente d'un type pour un porteur, par son code d'immatriculation. */
    Optional<PieceDossier> findFirstByTypeAndMatriculeIgnoreCaseOrderByCreatedAtDesc(String type, String matricule);

    Optional<PieceDossier> findFirstByDossierIdAndType(String dossierId, String type);

    void deleteByDossierIdAndType(String dossierId, String type);

    void deleteByDossierId(String dossierId);
}
