package com.csu.pharmacie.identite.depot;

import com.csu.pharmacie.entity.CompteurNumero;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Accès au compteur permanent {@code compteurs_numero} — LA MÊME table que celle du cœur.
 *
 * <p>Le cœur possède son propre dépôt sur cette table. Ce doublon est assumé : les deux
 * applications attribuent des codes de structure, sur la même base, et doivent donc
 * partager le compteur. Leur donner chacune leur séquence produirait des codes en double.</p>
 */
@Repository
public interface DepotCompteurNumero extends JpaRepository<CompteurNumero, String> {

    /** Verrouille la ligne (SELECT … FOR UPDATE) jusqu'à la fin de la transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CompteurNumero c where c.cle = :cle")
    Optional<CompteurNumero> findByCleForUpdate(@Param("cle") String cle);
}
