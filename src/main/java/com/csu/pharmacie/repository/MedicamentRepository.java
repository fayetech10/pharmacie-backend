package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.Medicament;
import com.csu.pharmacie.entity.StatutMedicament;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MedicamentRepository extends JpaRepository<Medicament, String> {
    List<Medicament> findTop10ByNomContainingIgnoreCaseAndActifTrue(String query);
    Optional<Medicament> findByNomIgnoreCase(String nom);
    Optional<Medicament> findByCode(String code);
    Optional<Medicament> findByCodeIgnoreCase(String code);

    /** Médicaments non répertoriés (ajoutés par des pharmaciens), en attente de classement SEN-CSU. */
    List<Medicament> findByStatutAndActifTrueOrderByCreatedAtDesc(StatutMedicament statut);

    // La colonne « nom » n'a aucune contrainte d'unicité (imports Excel successifs) : les
    // variantes en liste / booléen ci-dessous tolèrent les doublons, là où findByNomIgnoreCase
    // (Optional) lèverait une IncorrectResultSizeDataAccessException.
    List<Medicament> findAllByNomIgnoreCase(String nom);
    boolean existsByCodeIgnoreCase(String code);

    /** Spécialités partageant une même DCI : base des équivalences proposées au pharmacien. */
    List<Medicament> findByDciIgnoreCaseAndActifTrue(String dci);
}
