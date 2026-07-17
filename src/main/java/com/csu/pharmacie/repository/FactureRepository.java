package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.Facture;
import com.csu.pharmacie.entity.StatutFacture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FactureRepository extends JpaRepository<Facture, String> {
    List<Facture> findByPharmacieId(String pharmacieId);
    List<Facture> findByRegionId(String regionId);
    List<Facture> findByStatut(StatutFacture statut);
    List<Facture> findByMoisAndAnnee(int mois, int annee);
    Optional<Facture> findByPharmacieIdAndMoisAndAnnee(String pharmacieId, int mois, int annee);

    // Factures encore en brouillon pour des périodes antérieures au mois courant (retards de dépôt).
    @Query("SELECT f FROM Facture f WHERE f.pharmacieId = :pharmacieId "
            + "AND f.statut = com.csu.pharmacie.entity.StatutFacture.BROUILLON "
            + "AND (f.annee < :annee OR (f.annee = :annee AND f.mois < :mois))")
    List<Facture> findRetards(@Param("pharmacieId") String pharmacieId,
                              @Param("mois") int currentMois,
                              @Param("annee") int currentAnnee);

    // NB : les variantes « allégées » (sans les images base64 des lignes) sont désormais
    // construites côté service (FactureService.getAllFacturesLight) en retirant les pièces
    // après lecture — les lignes étant stockées en JSONB, on ne peut pas projeter à l'intérieur
    // d'un sous-document comme le faisait MongoDB.

    /**
     * Recherche ciblée dans le JSONB des lignes de facture : ne charge que les factures dont
     * au moins une ligne contient le terme recherché (numéro de bon, matricule patient, etc.).
     * Utilisé par getDossierConsolide au lieu du findAll() qui chargeait toutes les factures
     * (y compris les images base64) et faisait crasher Render (512 Mo).
     */
    @Query(value = "SELECT * FROM factures f WHERE CAST(f.lignes AS TEXT) LIKE CONCAT('%', :term, '%')",
           nativeQuery = true)
    List<Facture> findByLignesContaining(@Param("term") String term);
}
