package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.Constat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConstatRepository extends JpaRepository<Constat, String> {

    /** Constats d'un agent, du plus récent au plus ancien. */
    List<Constat> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    /** Tous les constats (vue ADMIN), du plus récent au plus ancien. */
    List<Constat> findAllByOrderByCreatedAtDesc();
}
