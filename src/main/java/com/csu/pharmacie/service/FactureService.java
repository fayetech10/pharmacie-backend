package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.FactureRequest;
import com.csu.pharmacie.dto.LigneFactureDto;
import com.csu.pharmacie.dto.ValidationRequest;
import com.csu.pharmacie.entity.*;
import com.csu.pharmacie.exception.BusinessException;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.FactureRepository;
import com.csu.pharmacie.repository.PharmacieRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.repository.MedicamentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@Service
@RequiredArgsConstructor
public class FactureService {

    private final FactureRepository factureRepository;
    private final PharmacieRepository pharmacieRepository;
    private final UserRepository userRepository;
    private final MedicamentRepository medicamentRepository;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    public List<Facture> getAllFactures() {
        User user = getCurrentUser();
        
        switch (user.getRole()) {
            case PHARMACIEN:
                return factureRepository.findByPharmacieId(user.getPharmacieId());
            case SERVICE_REGIONAL:
                return factureRepository.findByRegionId(user.getRegionId());
            case SERVICE_CENTRAL:
            case ADMIN:
                return factureRepository.findAll();
            default:
                return new ArrayList<>();
        }
    }

    public Facture getFactureById(String id) {
        Facture facture = factureRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Facture non trouvée"));
                
        User user = getCurrentUser();
        
        if (user.getRole() == Role.PHARMACIEN && !facture.getPharmacieId().equals(user.getPharmacieId())) {
            throw new ForbiddenException("Accès refusé");
        }
        
        if (user.getRole() == Role.SERVICE_REGIONAL && !facture.getRegionId().equals(user.getRegionId())) {
            throw new ForbiddenException("Accès refusé");
        }
        
        return facture;
    }

    public Facture createFacture(FactureRequest request) {
        User user = getCurrentUser();
        
        if (user.getRole() != Role.PHARMACIEN) {
            throw new ForbiddenException("Seul un pharmacien peut créer une facture");
        }

        Pharmacie pharmacie = pharmacieRepository.findById(user.getPharmacieId())
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacie non trouvée"));

        if (factureRepository.findByPharmacieIdAndMoisAndAnnee(pharmacie.getId(), request.getMois(), request.getAnnee()).isPresent()) {
            throw new ConflictException("Une facture existe déjà pour ce mois et cette année");
        }

        List<LigneFacture> lignes = mapLignes(request.getLignes());
        double montantTotal = lignes.stream().mapToDouble(LigneFacture::getMontant).sum();

        Facture facture = Facture.builder()
                .pharmacieId(pharmacie.getId())
                .pharmacieNom(pharmacie.getNom())
                .regionId(pharmacie.getRegionId())
                .mois(request.getMois())
                .annee(request.getAnnee())
                .montantTotal(montantTotal)
                .statut(StatutFacture.BROUILLON)
                .lignes(lignes)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(user.getId())
                .build();

        ajouterHistorique(facture, user, StatutFacture.BROUILLON, "Création de la facture");
        return factureRepository.save(facture);
    }

    public Facture getCurrentFacture() {
        User user = getCurrentUser();
        if (user.getRole() != Role.PHARMACIEN) {
            throw new ForbiddenException("Seul un pharmacien peut avoir une facture en cours");
        }

        LocalDate now = LocalDate.now();
        int currentMois = now.getMonthValue();
        int currentAnnee = now.getYear();

        return factureRepository.findByPharmacieIdAndMoisAndAnnee(user.getPharmacieId(), currentMois, currentAnnee)
                .filter(f -> f.getStatut() == StatutFacture.BROUILLON)
                .orElse(null);
    }

    public List<Facture> getRetards() {
        User user = getCurrentUser();
        if (user.getRole() != Role.PHARMACIEN) {
            return new ArrayList<>();
        }

        LocalDate now = LocalDate.now();
        return factureRepository.findRetards(user.getPharmacieId(), now.getMonthValue(), now.getYear());
    }

    public Facture addLignesToCurrent(List<LigneFactureDto> lignesDto) {
        User user = getCurrentUser();
        if (user.getRole() != Role.PHARMACIEN) {
            throw new ForbiddenException("Seul un pharmacien peut créer une facture");
        }

        LocalDate now = LocalDate.now();
        int currentMois = now.getMonthValue();
        int currentAnnee = now.getYear();

        Facture facture = factureRepository.findByPharmacieIdAndMoisAndAnnee(user.getPharmacieId(), currentMois, currentAnnee)
                .orElse(null);

        if (facture != null && facture.getStatut() != StatutFacture.BROUILLON) {
            throw new BusinessException("La facture de ce mois a déjà été envoyée");
        }

        if (facture == null) {
            Pharmacie pharmacie = pharmacieRepository.findById(user.getPharmacieId())
                    .orElseThrow(() -> new ResourceNotFoundException("Pharmacie non trouvée"));
            // Créer une nouvelle facture brouillon pour le mois courant
            facture = Facture.builder()
                    .pharmacieId(pharmacie.getId())
                    .pharmacieNom(pharmacie.getNom())
                    .regionId(pharmacie.getRegionId())
                    .mois(currentMois)
                    .annee(currentAnnee)
                    .statut(StatutFacture.BROUILLON)
                    .lignes(new ArrayList<>())
                    .historique(new ArrayList<>())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .createdBy(user.getId())
                    .montantTotal(0)
                    .build();
        }

        for (LigneFactureDto ligneDto : lignesDto) {
            // Vérifier si le médicament est exclu
            medicamentRepository.findByCodeIgnoreCase(ligneDto.getCodeProduit())
                    .ifPresent(med -> {
                        if (med.getStatut() == StatutMedicament.EXCLU) {
                            throw new BusinessException("Ce médicament est exclu et non facturable");
                        }
                    });

            LigneFacture newLigne = new LigneFacture();
            newLigne.setPatientNomPrenom(ligneDto.getPatientNomPrenom());
            newLigne.setPatientMatricule(ligneDto.getPatientMatricule());
            newLigne.setMedicament(ligneDto.getMedicament());
            newLigne.setCodeProduit(ligneDto.getCodeProduit());
            newLigne.setQuantite(ligneDto.getQuantite());
            newLigne.setPrixUnitaire(ligneDto.getPrixUnitaire());
            newLigne.setMontant(ligneDto.getQuantite() * ligneDto.getPrixUnitaire());

            facture.getLignes().add(newLigne);
        }

        double montantTotal = facture.getLignes().stream()
                .mapToDouble(LigneFacture::getMontant)
                .sum();
        facture.setMontantTotal(montantTotal);
        facture.setUpdatedAt(LocalDateTime.now());

        return factureRepository.save(facture);
    }

    public Facture updateFacture(String id, FactureRequest request) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();

        if (facture.getStatut() != StatutFacture.BROUILLON) {
            throw new BusinessException("Impossible de modifier une facture déjà envoyée");
        }

        List<LigneFacture> lignes = mapLignes(request.getLignes());
        double montantTotal = lignes.stream().mapToDouble(LigneFacture::getMontant).sum();

        facture.setMois(request.getMois());
        facture.setAnnee(request.getAnnee());
        facture.setLignes(lignes);
        facture.setMontantTotal(montantTotal);
        facture.setUpdatedAt(LocalDateTime.now());

        ajouterHistorique(facture, user, StatutFacture.BROUILLON, "Modification de la facture");
        return factureRepository.save(facture);
    }

    public Facture envoyerFacture(String id) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();

        if (facture.getStatut() != StatutFacture.BROUILLON) {
            throw new BusinessException("La facture n'est pas au statut brouillon");
        }

        changerStatut(facture, StatutFacture.ENVOYEE, user, "Envoi au service régional");
        return facture;
    }

    public Facture verifierFacture(String id) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();

        if (user.getRole() != Role.SERVICE_REGIONAL) {
            throw new ForbiddenException("Seul le service régional peut vérifier une facture");
        }

        if (facture.getStatut() != StatutFacture.ENVOYEE) {
            throw new BusinessException("La facture n'est pas au statut envoyée");
        }

        changerStatut(facture, StatutFacture.EN_VERIFICATION, user, "Prise en charge pour vérification");
        return facture;
    }

    public Facture conformerFacture(String id) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();

        if (user.getRole() != Role.SERVICE_REGIONAL) {
            throw new ForbiddenException("Seul le service régional peut déclarer une facture conforme");
        }

        if (facture.getStatut() != StatutFacture.EN_VERIFICATION) {
            throw new BusinessException("La facture n'est pas en vérification");
        }

        changerStatut(facture, StatutFacture.CONFORME, user, "Facture déclarée conforme");
        return facture;
    }

    public Facture validerFacture(String id, ValidationRequest request) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();

        if (user.getRole() != Role.SERVICE_REGIONAL && user.getRole() != Role.SERVICE_CENTRAL) {
            throw new ForbiddenException("Accès refusé");
        }

        if (facture.getStatut() != StatutFacture.CONFORME && facture.getStatut() != StatutFacture.EN_VERIFICATION) {
            throw new BusinessException("Statut invalide pour validation");
        }

        changerStatut(facture, StatutFacture.VALIDEE, user, request.getCommentaire());
        return facture;
    }

    public Facture rejeterFacture(String id, ValidationRequest request) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();

        if (user.getRole() != Role.SERVICE_REGIONAL) {
            throw new ForbiddenException("Seul le service régional peut rejeter une facture");
        }

        if (facture.getStatut() != StatutFacture.EN_VERIFICATION) {
            throw new BusinessException("La facture n'est pas en vérification");
        }

        facture.setCommentaireRejet(request.getCommentaire());
        changerStatut(facture, StatutFacture.REJETEE, user, request.getCommentaire());
        return facture;
    }
    
    public void deleteFacture(String id) {
        Facture facture = getFactureById(id);
        if (facture.getStatut() != StatutFacture.BROUILLON) {
            throw new BusinessException("Seules les factures en brouillon peuvent être supprimées");
        }
        factureRepository.deleteById(id);
    }

    private void changerStatut(Facture facture, StatutFacture nouveauStatut, User user, String commentaire) {
        facture.setStatut(nouveauStatut);
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, nouveauStatut, commentaire);
        factureRepository.save(facture);
    }

    private void ajouterHistorique(Facture facture, User user, StatutFacture statut, String commentaire) {
        if (facture.getHistorique() == null) {
            facture.setHistorique(new ArrayList<>());
        }
        
        HistoriqueAction action = HistoriqueAction.builder()
                .date(LocalDateTime.now())
                .utilisateurId(user.getId())
                .utilisateurNom(user.getPrenom() + " " + user.getNom())
                .statut(statut)
                .commentaire(commentaire)
                .build();
                
        facture.getHistorique().add(action);
    }

    private List<LigneFacture> mapLignes(List<LigneFactureDto> dtos) {
        return dtos.stream().map(dto -> {
            medicamentRepository.findByNomIgnoreCase(dto.getMedicament()).ifPresent(med -> {
                if (med.getStatut() == StatutMedicament.EXCLU) {
                    throw new BusinessException("Le médicament " + dto.getMedicament() + " est exclu et ne peut pas être facturé.");
                }
            });

            double montant = dto.getQuantite() * dto.getPrixUnitaire();
            return LigneFacture.builder()
                    .patientNomPrenom(dto.getPatientNomPrenom())
                    .patientMatricule(dto.getPatientMatricule())
                    .medicament(dto.getMedicament())
                    .codeProduit(dto.getCodeProduit())
                    .quantite(dto.getQuantite())
                    .prixUnitaire(dto.getPrixUnitaire())
                    .montant(montant)
                    .build();
        }).collect(Collectors.toList());
    }

    public void importerExcel(InputStream is) {
        User user = getCurrentUser();
        if (user.getRole() != Role.SERVICE_REGIONAL && user.getRole() != Role.SERVICE_CENTRAL && user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Accès refusé pour l'importation");
        }

        try (Workbook workbook = new XSSFWorkbook(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                
                String id = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(0));
                String nouveauStatutStr = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(6));

                if (id == null || nouveauStatutStr == null) continue;
                
                try {
                    StatutFacture nouveauStatut = StatutFacture.valueOf(nouveauStatutStr);
                    Facture facture = factureRepository.findById(id).orElse(null);
                    
                    if (facture != null && facture.getStatut() != nouveauStatut) {
                        changerStatut(facture, nouveauStatut, user, "Mise à jour en masse via import Excel");
                    }
                } catch (IllegalArgumentException e) {
                    // Statut invalide, on ignore
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Erreur lors de l'importation du fichier Excel", e);
        }
    }
}
