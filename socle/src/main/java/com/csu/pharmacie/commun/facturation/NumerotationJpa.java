package com.csu.pharmacie.commun.facturation;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import com.csu.pharmacie.commun.web.ExceptionMetier;

/**
 * Numérotation adossée à la base du service.
 *
 * <p>Format : {@code <prefixe>-<annéemois>-<compteur sur 5 chiffres>}, par exemple
 * {@code FP-202608-00042}. Le compteur repart à 1 chaque mois, ce qui est la présentation
 * attendue sur les documents officiels.</p>
 *
 * <p>Deux caisses qui encaissent à la même seconde entrent en conflit de version : on
 * rejoue alors l'incrément. Sans ce réessai, l'une des deux factures recevrait une erreur
 * alors que rien n'est réellement en faute.</p>
 */
@RequiredArgsConstructor
public class NumerotationJpa implements Numerotation {

    private static final int TENTATIVES = 5;

    private final CompteurTransactionnel compteur;

    @Override
    public String prochainNumero(String prefixe, int annee, int mois) {
        String cle = "%s-%d%02d".formatted(prefixe, annee, mois);
        for (int tentative = 1; tentative <= TENTATIVES; tentative++) {
            try {
                return "%s-%05d".formatted(cle, compteur.incrementer(cle));
            } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
                if (tentative == TENTATIVES) {
                    throw ExceptionMetier.conflit(
                            "Attribution du numéro impossible (écritures simultanées). Réessayez dans un instant.");
                }
            }
        }
        throw ExceptionMetier.conflit("Attribution du numéro impossible.");
    }
}
