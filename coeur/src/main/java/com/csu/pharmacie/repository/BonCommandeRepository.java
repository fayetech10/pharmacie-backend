package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.BonCommande;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BonCommandeRepository extends JpaRepository<BonCommande, String> {
    Optional<BonCommande> findByNumero(String numero);
    Optional<BonCommande> findByLettreGarantieId(String lettreGarantieId);
    List<BonCommande> findByCreatedBy(String createdBy);
    List<BonCommande> findByCreatedByIn(Collection<String> createdBy);
    List<BonCommande> findByNumeroIn(Collection<String> numeros);
}
