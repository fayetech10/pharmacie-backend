package com.csu.pharmacie.repository;

import com.csu.pharmacie.dto.FactureStatutDto;
import com.csu.pharmacie.entity.Facture;
import com.csu.pharmacie.entity.StatutFacture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Repository
public interface FactureRepository extends JpaRepository<Facture, String> {
    List<Facture> findByPharmacieId(String pharmacieId);
    List<Facture> findByRegionId(String regionId);
    List<Facture> findByStatut(StatutFacture statut);
    List<Facture> findByMoisAndAnnee(int mois, int annee);
    Optional<Facture> findByPharmacieIdAndMoisAndAnnee(String pharmacieId, int mois, int annee);

    // ── Listes filtrées EN BASE (0 = pas de filtre) ─────────────────────────────
    // Remplacent le chargement complet suivi d'un filtrage en mémoire : seules les
    // factures de la période demandée sont désérialisées (lignes JSONB comprises).
    @Query("SELECT f FROM Facture f WHERE f.pharmacieId = :pharmacieId "
            + "AND (:mois = 0 OR f.mois = :mois) AND (:annee = 0 OR f.annee = :annee)")
    List<Facture> findByPharmacieIdFiltre(@Param("pharmacieId") String pharmacieId,
                                          @Param("mois") int mois, @Param("annee") int annee);

    @Query("SELECT f FROM Facture f WHERE f.regionId = :regionId "
            + "AND (:mois = 0 OR f.mois = :mois) AND (:annee = 0 OR f.annee = :annee)")
    List<Facture> findByRegionIdFiltre(@Param("regionId") String regionId,
                                       @Param("mois") int mois, @Param("annee") int annee);

    @Query("SELECT f FROM Facture f WHERE (:mois = 0 OR f.mois = :mois) AND (:annee = 0 OR f.annee = :annee)")
    List<Facture> findAllFiltre(@Param("mois") int mois, @Param("annee") int annee);

    // ── Projection « badges » : identifiant + statut, sans toucher au JSONB ──────
    // Les compteurs de notification ne lisent que ces deux champs ; les charger en
    // projection évite de désérialiser lignes/historique à chaque navigation.
    // Le périmètre par rôle reproduit celui de getAllFactures().
    @Query("SELECT new com.csu.pharmacie.dto.FactureStatutDto(f.id, f.statut) "
            + "FROM Facture f WHERE f.pharmacieId = :pharmacieId")
    List<FactureStatutDto> findStatutsByPharmacie(@Param("pharmacieId") String pharmacieId);

    @Query("SELECT new com.csu.pharmacie.dto.FactureStatutDto(f.id, f.statut) "
            + "FROM Facture f WHERE f.regionId = :regionId")
    List<FactureStatutDto> findStatutsByRegion(@Param("regionId") String regionId);

    @Query("SELECT new com.csu.pharmacie.dto.FactureStatutDto(f.id, f.statut) FROM Facture f")
    List<FactureStatutDto> findAllStatuts();

    // ── Lecture en flux pour les statistiques ───────────────────────────────────
    // Consommées UNE facture à la fois (voir StatsService.chargerFacturesAllegees) :
    // les pièces base64 sont retirées et l'entité détachée au fil de l'eau, si bien
    // que la mémoire ne contient jamais qu'une seule facture complète à la fois —
    // c'est ce qui évite l'OOM des stats nationales (crash Render 512 Mo documenté).
    Stream<Facture> findAllByPharmacieId(String pharmacieId);
    Stream<Facture> findAllByRegionId(String regionId);
    @Query("SELECT f FROM Facture f")
    Stream<Facture> findAllStream();

    // ── Agrégat mensuel sans toucher au JSONB ───────────────────────────────────
    // L'évolution mensuelle n'a besoin que du nombre et du montant par mois : on
    // agrège en base au lieu de charger les factures entières (lignes + images).
    @Query("SELECT f.mois, COUNT(f), COALESCE(SUM(f.montantTotal), 0) FROM Facture f "
            + "WHERE f.annee = :annee "
            + "AND (CAST(:pharmacieId AS string) IS NULL OR f.pharmacieId = :pharmacieId) "
            + "AND (CAST(:regionId AS string) IS NULL OR f.regionId = :regionId) "
            + "GROUP BY f.mois")
    List<Object[]> aggregatMensuel(@Param("annee") int annee,
                                   @Param("pharmacieId") String pharmacieId,
                                   @Param("regionId") String regionId);

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
    @Query(value = "SELECT * FROM factures f WHERE CAST(f.lignes AS TEXT) LIKE CONCAT('%', CAST(:term AS TEXT), '%')",
           nativeQuery = true)
    List<Facture> findByLignesContaining(@Param("term") String term);
}
