package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.CompteurSequence;
import com.csu.pharmacie.repository.CompteurSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compteur des numéros de facture mensuelle, au format des microservices
 * ({@code FS-AAAAMM-NNNNN}).
 *
 * <p>Même table et même clé que la numérotation du socle ({@code NumerotationJpa}) : une
 * facture ouverte par le cœur et une autre ouverte par service-centre-sante ne peuvent pas
 * recevoir le même numéro. L'incrément est validé dans sa <em>propre</em> transaction — d'où
 * un bean distinct, Spring n'interceptant pas les appels internes. Un incrément concurrent
 * fait échouer la version du compteur ; l'appelant réessaie.</p>
 */
@Service
@RequiredArgsConstructor
public class CompteurFactureService {

    private final CompteurSequenceRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long incrementer(String cle) {
        CompteurSequence compteur = repository.findById(cle)
                .orElseGet(() -> CompteurSequence.builder().cle(cle).valeur(0).build());
        compteur.setValeur(compteur.getValeur() + 1);
        return repository.saveAndFlush(compteur).getValeur();
    }
}
