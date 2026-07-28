package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.Pointage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PointageRepository extends JpaRepository<Pointage, String> {
    Optional<Pointage> findByUserIdAndDate(String userId, LocalDate date);
    List<Pointage> findByDateOrderByHeureArriveeAsc(LocalDate date);
    List<Pointage> findByUserIdOrderByDateDesc(String userId);
    /** Pointages récents de plusieurs agents en une seule requête (stats agents). */
    List<Pointage> findByUserIdInAndDateGreaterThanEqual(Collection<String> userIds, LocalDate date);
}
