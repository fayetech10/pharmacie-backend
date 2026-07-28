package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.CompteurNumero;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompteurNumeroRepository extends JpaRepository<CompteurNumero, String> {

    /** Charge le compteur en le verrouillant (SELECT ... FOR UPDATE) jusqu'à la fin de la transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CompteurNumero c where c.cle = :cle")
    Optional<CompteurNumero> findByCleForUpdate(@Param("cle") String cle);
}
