package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.entity.FeuilleSoins;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Feuilles de soins, vues depuis la facturation.
 *
 * <p>Les microservices ne les créent ni ne les annulent — c'est le circuit BCSU, resté au
 * cœur — mais ils doivent les <em>tenir à jour</em> : une feuille reflète les prestations
 * réellement facturées, et c'est désormais un microservice qui enregistre la facture.</p>
 */
public interface DepotFeuilleSoins extends JpaRepository<FeuilleSoins, String> {

    List<FeuilleSoins> findByLettreGarantieId(String lettreGarantieId);
}
