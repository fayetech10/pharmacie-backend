package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.entity.CompteurSequence;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Incrémente un compteur dans sa <em>propre</em> transaction.
 *
 * <p>Classe séparée de {@link NumerotationJpa} à dessein : Spring n'intercepte pas les
 * appels qu'un bean se fait à lui-même, donc un {@code @Transactional} posé sur une
 * méthode privée de la même classe serait purement décoratif.</p>
 *
 * <p>{@code REQUIRES_NEW} + {@code saveAndFlush} : le numéro est validé immédiatement,
 * indépendamment du sort de la transaction appelante, et le conflit de version éventuel
 * remonte ici — donc dans la boucle de réessai — plutôt qu'au commit de l'appelant.</p>
 */
@RequiredArgsConstructor
public class CompteurTransactionnel {

    private final DepotCompteurSequence depot;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long incrementer(String cle) {
        CompteurSequence compteur = depot.findById(cle)
                .orElseGet(() -> CompteurSequence.builder().cle(cle).valeur(0).build());
        compteur.setValeur(compteur.getValeur() + 1);
        return depot.saveAndFlush(compteur).getValeur();
    }
}
