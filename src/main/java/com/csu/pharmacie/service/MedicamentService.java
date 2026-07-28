package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.Medicament;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutMedicament;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.BusinessException;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.MedicamentRepository;
import com.csu.pharmacie.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MedicamentService {

    private final MedicamentRepository medicamentRepository;
    private final UserRepository userRepository;

    private void checkAccess() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email).orElseThrow();
        if (user.getRole() != Role.SERVICE_CENTRAL && user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Accès refusé. Seul le service central ou un admin peut gérer les médicaments.");
        }
    }

    public List<Medicament> getAll() {
        return medicamentRepository.findAll();
    }

    public List<Medicament> search(String query) {
        return medicamentRepository.findTop10ByNomContainingIgnoreCaseAndActifTrue(query);
    }

    /**
     * Équivalences d'un médicament : les autres spécialités ÉLIGIBLES partageant la même DCI,
     * classées du MOINS CHER au plus cher. Les médicaments sans prix de référence connu sont
     * renvoyés en fin de liste (le prix vient de la colonne facultative de l'import éligibles).
     */
    public List<Medicament> getEquivalents(String nom) {
        if (nom == null || nom.isBlank()) return List.of();

        // findAllByNomIgnoreCase (et non findByNomIgnoreCase) : la colonne « nom » n'a pas
        // de contrainte d'unicité, la variante Optional lèverait sur un doublon d'import.
        List<Medicament> homonymes = medicamentRepository.findAllByNomIgnoreCase(nom.trim());
        Medicament source = homonymes.isEmpty() ? null : homonymes.get(0);
        if (source == null || source.getDci() == null || source.getDci().isBlank()) return List.of();

        return medicamentRepository.findByDciIgnoreCaseAndActifTrue(source.getDci().trim()).stream()
                .filter(m -> m.getStatut() == StatutMedicament.ELIGIBLE)
                .filter(m -> !m.getId().equals(source.getId()))
                .sorted(java.util.Comparator.comparing(
                        Medicament::getPrixReference,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    /** Lecture tolérante d'une cellule de prix (numérique ou texte). */
    private Double lirePrix(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                return cell.getNumericCellValue();
            }
            String txt = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(cell);
            if (txt == null || txt.isBlank()) return null;
            return Double.parseDouble(txt.trim().replace(" ", "").replace(",", "."));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Liste des médicaments non répertoriés (ajoutés par des pharmaciens sur leurs factures),
     * en attente de classement — validation en éligible ou passage en exclusion — par la SEN-CSU.
     */
    public List<Medicament> getNonRepertories() {
        checkAccess();
        return medicamentRepository.findByStatutAndActifTrueOrderByCreatedAtDesc(StatutMedicament.NON_REPERTORIE);
    }

    /**
     * Classe un médicament non répertorié : le fait basculer en ÉLIGIBLE (intégration définitive)
     * ou en EXCLU (exclusion définitive). Alimente ainsi la liste concernée. Réservé SEN-CSU / Admin.
     */
    public Medicament classer(String id, StatutMedicament statut, String motif) {
        checkAccess();
        if (statut != StatutMedicament.ELIGIBLE && statut != StatutMedicament.EXCLU) {
            throw new BusinessException("Un médicament non répertorié ne peut être classé qu'en ÉLIGIBLE ou EXCLU.");
        }
        Medicament medicament = medicamentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Médicament non trouvé"));

        medicament.setStatut(statut);
        if (statut == StatutMedicament.EXCLU) {
            medicament.setMotif((motif != null && !motif.isBlank())
                    ? motif.trim()
                    : (medicament.getMotif() != null && !medicament.getMotif().isBlank()
                        ? medicament.getMotif()
                        : "Exclu par la SEN-CSU"));
        }
        medicament.setActif(true);
        medicament.setUpdatedAt(LocalDateTime.now());
        return medicamentRepository.save(medicament);
    }

    public Medicament create(Medicament medicament) {
        checkAccess();
        if (medicamentRepository.findByCode(medicament.getCode()).isPresent()) {
            throw new ConflictException("Un médicament avec ce code existe déjà.");
        }
        medicament.setCreatedAt(LocalDateTime.now());
        medicament.setUpdatedAt(LocalDateTime.now());
        return medicamentRepository.save(medicament);
    }

    public Medicament update(String id, Medicament medicamentDetails) {
        checkAccess();
        Medicament medicament = medicamentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Médicament non trouvé"));
        
        medicament.setNom(medicamentDetails.getNom());
        medicament.setCode(medicamentDetails.getCode());
        medicament.setStatut(medicamentDetails.getStatut());
        medicament.setDescription(medicamentDetails.getDescription());
        medicament.setMotif(medicamentDetails.getMotif());
        medicament.setActif(medicamentDetails.isActif());
        medicament.setUpdatedAt(LocalDateTime.now());
        
        return medicamentRepository.save(medicament);
    }

    public void delete(String id) {
        checkAccess();
        Medicament medicament = medicamentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Médicament non trouvé"));
        medicament.setActif(false);
        medicament.setUpdatedAt(LocalDateTime.now());
        medicamentRepository.save(medicament);
    }

    public void importerMedicamentsEligibles(InputStream is) {
        checkAccess();
        try (BufferedInputStream bis = new BufferedInputStream(is);
             Workbook workbook = new XSSFWorkbook(bis)) {

            log.info("Début de l'importation des médicaments éligibles");

            // Le fichier importé fait foi pour la liste des ÉLIGIBLES : les médicaments
            // auparavant éligibles et absents du fichier repassent en NON_REPERTORIE.
            // Les EXCLU ne sont PAS touchés — ils viennent du référentiel d'exclusions,
            // avec leur motif, et écraser ce statut ferait perdre cette qualification.
            List<Medicament> anciensEligibles = medicamentRepository.findAll().stream()
                    .filter(m -> m.getStatut() == StatutMedicament.ELIGIBLE)
                    .toList();
            for (Medicament m : anciensEligibles) {
                m.setStatut(StatutMedicament.NON_REPERTORIE);
            }
            medicamentRepository.saveAll(anciensEligibles);
            log.info("{} médicaments éligibles remis en NON_REPERTORIE avant import", anciensEligibles.size());

            Sheet sheet = workbook.getSheetAt(0);
            int imported = 0;
            int updated = 0;

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String nom = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(0));
                String dci = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(1));
                String classeTherapeutique = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(2));
                String liste = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(3));
                // Colonne facultative : prix de référence, utilisé pour classer les équivalences.
                Double prixReference = lirePrix(row.getCell(4));

                if (nom == null || nom.trim().isEmpty()) {
                    continue;
                }

                Medicament med = medicamentRepository.findByNomIgnoreCase(nom.trim()).orElse(null);

                if (med != null) {
                    med.setStatut(StatutMedicament.ELIGIBLE);
                    med.setDci(dci);
                    med.setClasseTherapeutique(classeTherapeutique);
                    med.setListe(liste);
                    if (prixReference != null) med.setPrixReference(prixReference);
                    med.setUpdatedAt(LocalDateTime.now());
                    medicamentRepository.save(med);
                    updated++;
                } else {
                    Medicament newMed = Medicament.builder()
                            .code("AUTO-" + System.currentTimeMillis() + "-" + i)
                            .nom(nom.trim())
                            .dci(dci)
                            .classeTherapeutique(classeTherapeutique)
                            .liste(liste)
                            .prixReference(prixReference)
                            .statut(StatutMedicament.ELIGIBLE)
                            .actif(true)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    medicamentRepository.save(newMed);
                    imported++;
                }
            }
            log.info("Importation terminée : {} nouveaux, {} mis à jour", imported, updated);
        } catch (Exception e) {
            log.error("Erreur lors de l'importation du fichier Excel", e);
            throw new RuntimeException("Erreur lors de l'importation du fichier Excel: " + e.getMessage(), e);
        }
    }

    /**
     * Importe une liste d'EXCLUSIONS. Chaque ligne du fichier décrit une classe de produits
     * exclus avec une liste de marques (séparées par des virgules). Chaque marque est créée /
     * mise à jour en statut EXCLU, avec son motif (la classe) et sa description (la sous-catégorie/note).
     * Import additif : ne modifie pas les médicaments non listés.
     */
    public void importerMedicamentsExclus(InputStream is) {
        checkAccess();
        try (BufferedInputStream bis = new BufferedInputStream(is);
             Workbook workbook = new XSSFWorkbook(bis)) {

            log.info("Début de l'importation des médicaments exclus");

            // Feuille dédiée si présente, sinon la première
            Sheet sheet = workbook.getSheet("Medicaments_exclus");
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }

            int imported = 0;
            int updated = 0;

            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String classe = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(1));
                String sousCat = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(2));
                String exemples = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(3));

                // Ignorer titres, en-têtes et lignes sans marques
                if (exemples == null || exemples.trim().isEmpty()) continue;
                if (classe == null || classe.trim().isEmpty()) continue;
                if (classe.toLowerCase().contains("classe de produits exclus")) continue;

                String motif = classe.trim();
                String description = (sousCat != null && !sousCat.trim().isEmpty())
                        ? sousCat.trim()
                        : "Produit non pris en charge par la CSU";

                for (String brut : exemples.split(",")) {
                    String nom = brut.trim();
                    if (nom.isEmpty()) continue;

                    Medicament med = medicamentRepository.findByNomIgnoreCase(nom).orElse(null);
                    if (med != null) {
                        med.setStatut(StatutMedicament.EXCLU);
                        med.setMotif(motif);
                        med.setDescription(description);
                        med.setClasseTherapeutique(classe.trim());
                        med.setActif(true);
                        med.setUpdatedAt(LocalDateTime.now());
                        medicamentRepository.save(med);
                        updated++;
                    } else {
                        Medicament newMed = Medicament.builder()
                                .code("EXC-" + System.currentTimeMillis() + "-" + (imported + updated))
                                .nom(nom)
                                .classeTherapeutique(classe.trim())
                                .statut(StatutMedicament.EXCLU)
                                .motif(motif)
                                .description(description)
                                .actif(true)
                                .createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .build();
                        medicamentRepository.save(newMed);
                        imported++;
                    }
                }
            }
            log.info("Importation exclusions terminée : {} nouveaux, {} mis à jour", imported, updated);
        } catch (Exception e) {
            log.error("Erreur lors de l'importation des exclusions", e);
            throw new RuntimeException("Erreur lors de l'importation du fichier Excel: " + e.getMessage(), e);
        }
    }
}

