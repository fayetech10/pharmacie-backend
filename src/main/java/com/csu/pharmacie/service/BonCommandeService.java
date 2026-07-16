package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.AnnulationRequest;
import com.csu.pharmacie.entity.BonCommande;
import com.csu.pharmacie.entity.HistoriqueDossier;
import com.csu.pharmacie.entity.LettreGarantie;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutBonCommande;
import com.csu.pharmacie.entity.StatutLettreGarantie;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.BusinessException;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.BonCommandeRepository;
import com.csu.pharmacie.repository.LettreGarantieRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.utils.NumeroGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BonCommandeService {

    private final BonCommandeRepository bonCommandeRepository;
    private final LettreGarantieRepository lettreGarantieRepository;
    private final UserRepository userRepository;
    private final com.csu.pharmacie.repository.PatientRepository patientRepository;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    private void checkRechercheAutorisee(User user) {
        if (user.getRole() != Role.BCSU && user.getRole() != Role.ADMIN && user.getRole() != Role.PHARMACIEN
                && user.getRole() != Role.SERVICE_REGIONAL) {
            throw new ForbiddenException("Accès refusé");
        }
    }

    /** Identifiants des agents BCSU rattachés à une région (rattachement via User.regionId). */
    private List<String> bcsuIdsDeLaRegion(String regionId) {
        return userRepository.findByRoleAndRegionId(Role.BCSU, regionId).stream()
                .map(User::getId)
                .collect(Collectors.toList());
    }

    /** Vrai si le bon a été émis par un agent BCSU de la région du SR. */
    private boolean memeRegionQueLAgent(User user, String createdBy) {
        return createdBy != null && userRepository.findById(createdBy)
                .map(agent -> java.util.Objects.equals(agent.getRegionId(), user.getRegionId()))
                .orElse(false);
    }

    public List<BonCommande> getAllForCurrentUser(Integer mois, Integer annee, String regimeStr) {
        User user = getCurrentUser();
        List<BonCommande> bons;
        
        if (user.getRole() == Role.ADMIN) {
            bons = bonCommandeRepository.findAll();
        } else if (user.getRole() == Role.SERVICE_REGIONAL) {
            List<String> agents = bcsuIdsDeLaRegion(user.getRegionId());
            bons = agents.isEmpty() ? new ArrayList<>() : bonCommandeRepository.findByCreatedByIn(agents);
        } else if (user.getRole() == Role.BCSU) {
            bons = bonCommandeRepository.findByCreatedBy(user.getId());
        } else {
            throw new ForbiddenException("Accès refusé");
        }
        
        if (mois != null && mois > 0) {
            bons = bons.stream().filter(b -> b.getCreatedAt() != null && b.getCreatedAt().getMonthValue() == mois).collect(Collectors.toList());
        }
        if (annee != null && annee > 0) {
            bons = bons.stream().filter(b -> b.getCreatedAt() != null && b.getCreatedAt().getYear() == annee).collect(Collectors.toList());
        }
        if (regimeStr != null && !regimeStr.trim().isEmpty()) {
            try {
                com.csu.pharmacie.entity.Regime regimeEnum = com.csu.pharmacie.entity.Regime.valueOf(regimeStr.trim().toUpperCase());
                bons = bons.stream().filter(b -> b.getRegime() == regimeEnum).collect(Collectors.toList());
            } catch (IllegalArgumentException e) {
                // Ignore invalid regime
            }
        }
        
        return bons;
    }

    public List<BonCommande> getAllForCurrentUser() {
        return getAllForCurrentUser(null, null, null);
    }

    public BonCommande getById(String id) {
        BonCommande bon = bonCommandeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bon de commande non trouvé"));
        User user = getCurrentUser();
        if (user.getRole() == Role.BCSU && !java.util.Objects.equals(bon.getCreatedBy(), user.getId())) {
            throw new ForbiddenException("Accès refusé");
        }
        if (user.getRole() == Role.SERVICE_REGIONAL && !memeRegionQueLAgent(user, bon.getCreatedBy())) {
            throw new ForbiddenException("Accès refusé");
        }
        return bon;
    }

    public BonCommande findByNumero(String numero) {
        User user = getCurrentUser();
        checkRechercheAutorisee(user);
        BonCommande bon = bonCommandeRepository.findByNumero(numero)
                .orElseThrow(() -> new ResourceNotFoundException("Aucun bon de commande pour ce numéro"));
        if (user.getRole() == Role.SERVICE_REGIONAL && !memeRegionQueLAgent(user, bon.getCreatedBy())) {
            throw new ForbiddenException("Accès refusé");
        }
        // Usage multi-pharmacies : le pharmacien ne peut pas utiliser un bon dont toutes les
        // lignes sont consommées. Un bon partiellement utilisé reste disponible pour les lignes restantes.
        if (user.getRole() == Role.PHARMACIEN && bon.estComplet()) {
            throw new BusinessException("Ce bon de commande est complet — toutes ses " + bon.getNombreLignes()
                    + " ligne(s) ont déjà été utilisées. Aucun médicament supplémentaire ne peut être ajouté.");
        }

        // Lazy-migration/fallback pour patientMatricule s'il est vide
        if (bon.getPatientMatricule() == null || bon.getPatientMatricule().trim().isEmpty()) {
            lettreGarantieRepository.findById(bon.getLettreGarantieId()).ifPresent(lettre -> {
                if (lettre.getPatientId() != null) {
                    patientRepository.findById(lettre.getPatientId()).ifPresent(p -> {
                        String mat = (p.getNumeroAssure() != null && !p.getNumeroAssure().trim().isEmpty())
                                ? p.getNumeroAssure()
                                : p.getNumeroCni();
                        if (mat != null && !mat.trim().isEmpty()) {
                            bon.setPatientMatricule(mat);
                            bonCommandeRepository.save(bon);
                        }
                    });
                }
            });
        }

        return bon;
    }

    /** Bon associé à une lettre de garantie (ou null s'il n'y en a pas). BCSU/ADMIN/SR. */
    public BonCommande findByLettre(String lettreGarantieId) {
        User user = getCurrentUser();
        if (user.getRole() != Role.BCSU && user.getRole() != Role.ADMIN && user.getRole() != Role.SERVICE_REGIONAL) {
            throw new ForbiddenException("Accès refusé");
        }
        return bonCommandeRepository.findByLettreGarantieId(lettreGarantieId)
                .filter(b -> user.getRole() == Role.ADMIN
                        || (user.getRole() == Role.SERVICE_REGIONAL && memeRegionQueLAgent(user, b.getCreatedBy()))
                        || (user.getRole() == Role.BCSU && java.util.Objects.equals(b.getCreatedBy(), user.getId())))
                .orElse(null);
    }

    @Transactional
    public BonCommande creerBonCommande(String lettreGarantieId, int nombreLignes) {
        User user = getCurrentUser();
        if (user.getRole() != Role.BCSU && user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Seul un agent BCSU peut générer un bon de commande");
        }

        LettreGarantie lettre = lettreGarantieRepository.findById(lettreGarantieId)
                .orElseThrow(() -> new ResourceNotFoundException("Lettre de garantie non trouvée"));
        if (user.getRole() == Role.BCSU && !java.util.Objects.equals(lettre.getCreatedBy(), user.getId())) {
            throw new ForbiddenException("Accès refusé");
        }
        if (lettre.getStatut() != StatutLettreGarantie.EMISE) {
            throw new BusinessException("La lettre de garantie doit être émise avant de générer un bon de commande");
        }
        if (lettre.getRegime() != com.csu.pharmacie.entity.Regime.CONTRIBUTIF) {
            throw new BusinessException("Un bon de commande ne peut être généré que pour le régime Classique");
        }
        if (bonCommandeRepository.findByLettreGarantieId(lettreGarantieId).isPresent()) {
            throw new ConflictException("Un bon de commande existe déjà pour cette lettre de garantie");
        }

        String matricule = lettre.getCniNumeroOcr();
        if (matricule == null || matricule.trim().isEmpty()) {
            if (lettre.getPatientId() != null) {
                matricule = patientRepository.findById(lettre.getPatientId())
                        .map(p -> (p.getNumeroAssure() != null && !p.getNumeroAssure().trim().isEmpty()) ? p.getNumeroAssure() : p.getNumeroCni())
                        .orElse(null);
            }
        }

        BonCommande bon = BonCommande.builder()
                .numero(genererNumeroUnique("BC"))
                .lettreGarantieId(lettre.getId())
                .lettreGarantieNumero(lettre.getNumero())
                .patientNom(lettre.getPatientNom())
                .patientPrenom(lettre.getPatientPrenom())
                .patientMatricule(matricule)
                .regime(lettre.getRegime())
                .nombreLignes(nombreLignes > 0 ? nombreLignes
                        : (lettre.getPrestations() != null && !lettre.getPrestations().isEmpty()
                                ? lettre.getPrestations().size() : 3))
                .prestations(new ArrayList<>(lettre.getPrestations()))
                .montantTotal(lettre.getMontantTotal())
                .montantTotalBeneficiaire(lettre.getMontantTotalBeneficiaire())
                .montantTotalSencsu(lettre.getMontantTotalSencsu())
                .statut(StatutBonCommande.EMIS)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(user.getId())
                .build();

        ajouterHistorique(bon, user, StatutBonCommande.EMIS, "Génération du bon de commande depuis la lettre " + lettre.getNumero());
        return bonCommandeRepository.save(bon);
    }

    @Transactional
    public BonCommande annuler(String id, AnnulationRequest request) {
        BonCommande bon = getById(id);
        User user = getCurrentUser();
        if (user.getRole() != Role.BCSU && user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Accès refusé");
        }
        if (bon.getStatut() == StatutBonCommande.ANNULE) {
            throw new BusinessException("Ce bon de commande est déjà annulé");
        }

        bon.setStatut(StatutBonCommande.ANNULE);
        bon.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(bon, user, StatutBonCommande.ANNULE, request.getMotif());
        return bonCommandeRepository.save(bon);
    }

    private String genererNumeroUnique(String prefixe) {
        for (int i = 0; i < 5; i++) {
            String candidat = NumeroGenerator.generer(prefixe);
            if (bonCommandeRepository.findByNumero(candidat).isEmpty()) {
                return candidat;
            }
        }
        throw new BusinessException("Impossible de générer un numéro unique, veuillez réessayer");
    }

    private void ajouterHistorique(BonCommande bon, User user, StatutBonCommande statut, String commentaire) {
        if (bon.getHistorique() == null) {
            bon.setHistorique(new ArrayList<>());
        }
        HistoriqueDossier action = HistoriqueDossier.builder()
                .date(LocalDateTime.now())
                .utilisateurId(user.getId())
                .utilisateurNom(user.getPrenom() + " " + user.getNom())
                .statut(statut.name())
                .commentaire(commentaire)
                .build();
        bon.getHistorique().add(action);
    }
}
