package com.csu.pharmacie.identite.depot;

import org.springframework.data.jpa.repository.JpaRepository;
import com.csu.pharmacie.entity.Pointage;

import java.time.LocalDate;
import java.util.Optional;

public interface DepotPointage extends JpaRepository<Pointage, String> {

    /** Le pointage du jour d'un agent : un seul par agent et par jour (index unique). */
    Optional<Pointage> findByUserIdAndDate(String userId, LocalDate date);
}
