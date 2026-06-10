package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.Medicament;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutMedicament;
import com.csu.pharmacie.entity.User;
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

            // Passer tous les médicaments existants en EXCLU
            List<Medicament> tousLesMedicaments = medicamentRepository.findAll();
            for (Medicament m : tousLesMedicaments) {
                m.setStatut(StatutMedicament.EXCLU);
            }
            medicamentRepository.saveAll(tousLesMedicaments);
            log.info("{} médicaments existants passés en EXCLU", tousLesMedicaments.size());

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

                if (nom == null || nom.trim().isEmpty()) {
                    continue;
                }

                Medicament med = medicamentRepository.findByNomIgnoreCase(nom.trim()).orElse(null);

                if (med != null) {
                    med.setStatut(StatutMedicament.ELIGIBLE);
                    med.setDci(dci);
                    med.setClasseTherapeutique(classeTherapeutique);
                    med.setListe(liste);
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
}

