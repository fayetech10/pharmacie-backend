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

    /*
     * Variantes « allégées » pour les vues liste / tableau de bord : la projection exclut
     * les images base64 des lignes (ticket de caisse, bon de commande, ordonnance), qui
     * pèsent l'essentiel du document mais ne sont pas affichées en liste. Mongo ne les charge
     * ni ne les transfère → réponses bien plus légères et affichage plus rapide.
     * Le détail d'une facture (findById) reste complet pour le dossier patient.
     */
    String EXCLUDE_PIECES = "{ 'lignes.ticketCaisse': 0, 'lignes.bonCommande': 0, 'lignes.ordonnance': 0 }";

    @Query(value = "{}", fields = EXCLUDE_PIECES)
    List<Facture> findAllLight();

    @Query(value = "{ 'pharmacieId': ?0 }", fields = EXCLUDE_PIECES)
    List<Facture> findByPharmacieIdLight(String pharmacieId);

    @Query(value = "{ 'regionId': ?0 }", fields = EXCLUDE_PIECES)
    List<Facture> findByRegionIdLight(String regionId);
}
