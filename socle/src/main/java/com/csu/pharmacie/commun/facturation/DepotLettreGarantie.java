package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.entity.LettreGarantie;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Lecture des lettres de garantie émises par les agents BCSU.
 *
 * <p>Les microservices de facturation ne les créent jamais — c'est le métier du cœur — mais
 * ils doivent les <em>relire</em> : une facture établie à partir d'une lettre tient d'elle
 * son régime et l'identité de son bénéficiaire, et ces valeurs ne peuvent pas venir du
 * client. C'est la même règle que pour les prix, relus dans le référentiel plutôt que crus
 * sur parole.</p>
 */
public interface DepotLettreGarantie extends JpaRepository<LettreGarantie, String> {

    Optional<LettreGarantie> findByNumeroIgnoreCase(String numero);
}
