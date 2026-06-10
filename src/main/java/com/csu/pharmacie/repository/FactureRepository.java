package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.Facture;
import com.csu.pharmacie.entity.StatutFacture;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FactureRepository extends MongoRepository<Facture, String> {
    List<Facture> findByPharmacieId(String pharmacieId);
    List<Facture> findByRegionId(String regionId);
    List<Facture> findByStatut(StatutFacture statut);
    List<Facture> findByMoisAndAnnee(int mois, int annee);
    Optional<Facture> findByPharmacieIdAndMoisAndAnnee(String pharmacieId, int mois, int annee);

    @Query("{ 'pharmacieId': ?0, 'statut': 'BROUILLON', $or: [ { 'annee': { $lt: ?2 } }, { 'annee': ?2, 'mois': { $lt: ?1 } } ] }")
    List<Facture> findRetards(String pharmacieId, int currentMois, int currentAnnee);
}
