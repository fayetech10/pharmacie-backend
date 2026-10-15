package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.StatutFacture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Accès aux factures des points de soins.
 *
 * <p>Poste et centre partagent cette table — c'est celle du backend existant. Le
 * cloisonnement se fait par {@code structureSanitaireId} (sa propre structure) et par
 * {@code structureRattachementId} (les postes qu'un centre polarise), jamais par la base.</p>
 */
public interface DepotFactureStructure extends JpaRepository<FactureStructure, String> {

    Optional<FactureStructure> findByNumero(String numero);

    List<FactureStructure> findByStructureSanitaireId(String structureSanitaireId);

    /** Une facture par structure × régime × période : socle de la facturation incrémentielle. */
    Optional<FactureStructure> findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(
            String structureSanitaireId, Regime regime, int mois, int annee);

    /** Factures des postes polarisés par un centre. */
    List<FactureStructure> findByStructureRattachementId(String structureRattachementId);

    /** Périmètre de consolidation (§9). */
    List<FactureStructure> findByDistrictId(String districtId);

    /** Factures des structures d'un district — rattrape celles dont le district n'a pas été figé. */
    List<FactureStructure> findByStructureSanitaireIdIn(Collection<String> structureSanitaireIds);

    /** Factures d'une année pour un ensemble de structures — activités du réseau d'une région. */
    List<FactureStructure> findByStructureSanitaireIdInAndAnnee(Collection<String> structureSanitaireIds, int annee);

    List<FactureStructure> findByRegionId(String regionId);

    List<FactureStructure> findByStatut(StatutFacture statut);

    /**
     * Verrou consultatif PostgreSQL sur un nom libre, tenu jusqu'à la fin de la transaction
     * appelante : relâché seul au commit ou au rollback, et par la connexion fermée si le
     * service tombe. Une seconde transaction qui demande le même nom attend la première.
     *
     * <p>Sert là où un contrôle « existe-t-il déjà ? » précède une création : sans lui, deux
     * requêtes simultanées passent toutes deux le contrôle (aucune ne voit encore l'autre) et
     * créent chacune — la même saisie hors ligne envoyée trois fois a ainsi donné trois
     * dossiers. Après le verrou, en lecture validée (isolation par défaut de PostgreSQL), les
     * requêtes suivantes voient ce que la transaction précédente a enregistré : il faut donc le
     * prendre avant de lire ce qu'il protège.</p>
     *
     * <p>Le nom est réduit à un entier : deux noms qui se heurteraient ne feraient qu'attendre
     * l'un l'autre, sans autre effet. Le 2001 isole ces verrous d'autres usages éventuels.</p>
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(2001, hashtext(CAST(:nom AS text)))) AS verrou",
            nativeQuery = true)
    Integer verrouiller(@Param("nom") String nom);
}
