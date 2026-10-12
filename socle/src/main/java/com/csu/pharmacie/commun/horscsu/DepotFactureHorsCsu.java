package com.csu.pharmacie.commun.horscsu;

import com.csu.pharmacie.entity.FactureHorsCsu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Accès aux factures Hors CSU — table unique, cloisonnée par structure. */
public interface DepotFactureHorsCsu extends JpaRepository<FactureHorsCsu, String> {

    Optional<FactureHorsCsu> findByNumero(String numero);

    List<FactureHorsCsu> findByStructureSanitaireIdOrderByDateDesc(String structureSanitaireId);

    List<FactureHorsCsu> findByStructureSanitaireIdAndDate(String structureSanitaireId, LocalDate date);

    List<FactureHorsCsu> findByStructureSanitaireIdAndDateBetween(String structureSanitaireId,
                                                                  LocalDate debut, LocalDate fin);
}
