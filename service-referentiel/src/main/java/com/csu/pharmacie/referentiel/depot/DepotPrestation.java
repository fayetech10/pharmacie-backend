package com.csu.pharmacie.referentiel.depot;

import com.csu.pharmacie.entity.Prestation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DepotPrestation extends JpaRepository<Prestation, String> {

    Optional<Prestation> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<Prestation> findByIdIn(Collection<String> ids);

    List<Prestation> findByServiceIdAndActifTrue(String serviceId);

    List<Prestation> findByActifTrue();

    long countByServiceId(String serviceId);

    /**
     * Recherche paginée (§5 : « recherche et affichage paginé des prestations »).
     *
     * <p>Le filtrage est fait en SQL et non en mémoire : le référentiel national dépasse
     * largement ce qu'on veut charger d'un bloc à chaque frappe dans la barre de recherche.</p>
     *
     * <p>{@code CAST(:serviceId AS string)} n'est pas décoratif : PostgreSQL analyse
     * l'expression de gauche à droite et {@code $1 IS NULL} ne lui donne aucun type à
     * déduire — il refuse la requête entière avec « could not determine data type of
     * parameter $1 » (SQLState 42P18), avant même de voir le {@code p.serviceId = :serviceId}
     * qui aurait suffi. Le cast fixe le type une fois pour toutes les occurrences.</p>
     */
    @Query("""
            select p from Prestation p
            where (cast(:serviceId as string) is null or p.serviceId = :serviceId)
              and (:actifSeulement = false or p.actif = true)
              and (:recherche = '' or lower(p.designation) like concat('%', :recherche, '%')
                   or lower(p.code) like concat('%', :recherche, '%'))
            """)
    Page<Prestation> rechercher(@Param("serviceId") String serviceId,
                                @Param("recherche") String recherche,
                                @Param("actifSeulement") boolean actifSeulement,
                                Pageable pageable);
}
