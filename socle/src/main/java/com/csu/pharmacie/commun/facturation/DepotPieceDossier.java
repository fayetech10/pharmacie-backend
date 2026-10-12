package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.entity.PieceDossier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Photos justificatives des dossiers patients (cf. {@link PieceDossier}). */
public interface DepotPieceDossier extends JpaRepository<PieceDossier, String> {

    Optional<PieceDossier> findFirstByDossierIdAndType(String dossierId, String type);

    List<PieceDossier> findByDossierId(String dossierId);

    void deleteByDossierIdAndType(String dossierId, String type);

    void deleteByDossierId(String dossierId);

    void deleteByFactureId(String factureId);

    /**
     * Pièces d'un type sans leur image (projection) : un dossier peut compter plusieurs tickets
     * de caisse, que l'écran liste avant d'en ouvrir un.
     */
    List<ResumePiece> findByDossierIdAndTypeOrderByCreatedAtAsc(String dossierId, String type);

    long countByDossierIdAndType(String dossierId, String type);

    Optional<PieceDossier> findByIdAndDossierId(String id, String dossierId);

    /** Une pièce, sans son image. */
    interface ResumePiece {
        String getId();

        LocalDateTime getCreatedAt();
    }
}
