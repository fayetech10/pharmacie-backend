package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.StatutFacture;
import org.springframework.data.jpa.repository.JpaRepository;

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

    List<FactureStructure> findByRegionId(String regionId);

    List<FactureStructure> findByStatut(StatutFacture statut);
}
