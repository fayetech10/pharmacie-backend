package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.AnnulationRequest;
import com.csu.pharmacie.dto.FeuilleSoinsRequest;
import com.csu.pharmacie.dto.PriseEnChargeFeuilleRequest;
import com.csu.pharmacie.entity.*;
import com.csu.pharmacie.exception.BusinessException;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.FeuilleSoinsRepository;
import com.csu.pharmacie.repository.LettreGarantieRepository;
import com.csu.pharmacie.repository.PatientRepository;
import com.csu.pharmacie.repository.StructureSanitaireRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.utils.NumeroGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Circuit de la feuille de soins :
 *  1. l'agent bureau (BCSU) la DÉLIVRE au patient (EMISE) ;
 *  2. le patient la remet à la structure sanitaire qui remplit la
 *     PRISE EN CHARGE (DEPOSEE_STRUCTURE) ;
 *  3. la structure la rattache à sa facturation (FACTUREE).
 */
@Service
@RequiredArgsConstructor
public class FeuilleSoinsService {

    private final FeuilleSoinsRepository feuilleRepository;
    private final LettreGarantieRepository lettreGarantieRepository;
    private final PatientRepository patientRepository;
    private final StructureSanitaireRepository structureRepository;
    private final UserRepository userRepository;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    private StructureSanitaire getCurrentStructure(User user) {
        if (user.getStructureSanitaireId() == null) {
            throw new ForbiddenException("Aucune structure sanitaire n'est rattachée à ce compte");
        }
        return structureRepository.findById(user.getStructureSanitaireId())
                .orElseThrow(() -> new ResourceNotFoundException("Structure sanitaire non trouvée"));
    }

    /** Identifiants des agents BCSU rattachés à une région. */
    private List<String> bcsuIdsDeLaRegion(String regionId) {
        return userRepository.findByRoleAndRegionId(Role.BCSU, regionId).stream()
                .map(User::getId)
                .collect(Collectors.toList());
    }

    private boolean memeRegionQueLAgent(User user, String createdBy) {
        return createdBy != null && userRepository.findById(createdBy)
                .map(agent -> Objects.equals(agent.getRegionId(), user.getRegionId()))
                .orElse(false);
    }

    public List<FeuilleSoins> getAllForCurrentUser(Integer mois, Integer annee, String regimeStr) {
        User user = getCurrentUser();
        List<FeuilleSoins> feuilles;
        
        switch (user.getRole()) {
            case ADMIN:
                feuilles = feuilleRepository.findAll();
                break;
            case BCSU:
                feuilles = feuilleRepository.findByCreatedBy(user.getId());
                break;
            case SERVICE_REGIONAL: {
                List<String> agents = bcsuIdsDeLaRegion(user.getRegionId());
                feuilles = agents.isEmpty() ? new ArrayList<>() : feuilleRepository.findByCreatedByIn(agents);
                break;
            }
            case STRUCTURE_SANITAIRE:
                feuilles = feuilleRepository.findByStructureSanitaireId(user.getStructureSanitaireId());
                break;
            default:
                throw new ForbiddenException("Accès refusé");
        }
        
        if (mois != null && mois > 0) {
            feuilles = feuilles.stream().filter(f -> f.getCreatedAt().getMonthValue() == mois).collect(Collectors.toList());
        }
        if (annee != null && annee > 0) {
            feuilles = feuilles.stream().filter(f -> f.getCreatedAt().getYear() == annee).collect(Collectors.toList());
        }
        if (regimeStr != null && !regimeStr.trim().isEmpty()) {
            try {
                Regime regimeEnum = Regime.valueOf(regimeStr.trim().toUpperCase());
                feuilles = feuilles.stream().filter(f -> f.getRegime() == regimeEnum).collect(Collectors.toList());
            } catch (IllegalArgumentException e) {
                // Ignore invalid regime
            }
        }
        
        return feuilles;
    }
    
    public List<FeuilleSoins> getAllForCurrentUser() {
        return getAllForCurrentUser(null, null, null);
    }

    public FeuilleSoins getById(String id) {
        FeuilleSoins feuille = feuilleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Feuille de soins non trouvée"));
        checkLecture(getCurrentUser(), feuille);
        return feuille;
    }

    /** La structure retrouve la feuille présentée par le patient (recherche par numéro). */
    public FeuilleSoins findByNumero(String numero) {
        FeuilleSoins feuille = feuilleRepository.findByNumero(numero)
                .orElseThrow(() -> new ResourceNotFoundException("Aucune feuille de soins pour ce numéro"));
        checkLecture(getCurrentUser(), feuille);
        return feuille;
    }

    private void checkLecture(User user, FeuilleSoins feuille) {
        switch (user.getRole()) {
            case ADMIN:
                return;
            case BCSU:
                if (!Objects.equals(feuille.getCreatedBy(), user.getId())) {
                    throw new ForbiddenException("Accès refusé");
                }
                return;
            case SERVICE_REGIONAL:
                if (!memeRegionQueLAgent(user, feuille.getCreatedBy())) {
                    throw new ForbiddenException("Accès refusé");
                }
                return;
            case STRUCTURE_SANITAIRE: {
                StructureSanitaire structure = getCurrentStructure(user);
                // Déjà déposée chez elle, ou émise par SON BCSU (patient qui présente la feuille).
                boolean deposeeChezElle = Objects.equals(feuille.getStructureSanitaireId(), structure.getId());
                boolean emiseParSonBcsu = Objects.equals(feuille.getCreatedBy(), structure.getBcsuId());
                if (!deposeeChezElle && !emiseParSonBcsu) {
                    throw new ForbiddenException("Cette feuille de soins n'a pas été délivrée par votre BCSU");
                }
                return;
            }
            default:
                throw new ForbiddenException("Accès refusé");
        }
    }

    /** Étape 1 — l'agent bureau délivre la feuille au patient. */
    @Transactional
    public FeuilleSoins delivrer(FeuilleSoinsRequest request) {
        User user = getCurrentUser();
        if (user.getRole() != Role.BCSU && user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Seul un agent BCSU peut délivrer une feuille de soins");
        }

        FeuilleSoins.FeuilleSoinsBuilder builder = FeuilleSoins.builder()
                .numero(genererNumeroUnique("FDS"))
                .patientNom(request.getNom())
                .patientPrenom(request.getPrenom())
                .patientTelephone(request.getTelephone())
                .regime(request.getRegime())
                .codeAssure(request.getCodeAssure())
                .sexe(request.getSexe())
                .age(request.getAge())
                .diagnostic(request.getDiagnostic())
                .accompagnantPrenomNom(request.getAccompagnantPrenomNom());

        // Rattachement facultatif à une lettre de garantie existante.
        if (request.getLettreGarantieNumero() != null && !request.getLettreGarantieNumero().isBlank()) {
            LettreGarantie lettre = lettreGarantieRepository.findByNumero(request.getLettreGarantieNumero().trim())
                    .orElseThrow(() -> new ResourceNotFoundException("Aucune lettre de garantie pour ce numéro"));
            if (user.getRole() == Role.BCSU && !Objects.equals(lettre.getCreatedBy(), user.getId())) {
                throw new ForbiddenException("Cette lettre de garantie n'a pas été émise par votre bureau");
            }
            builder.lettreGarantieId(lettre.getId()).lettreGarantieNumero(lettre.getNumero());
        }

        // Rattachement facultatif à un patient existant (issu de la recherche).
        if (request.getPatientId() != null && !request.getPatientId().isBlank()) {
            Patient patient = patientRepository.findById(request.getPatientId())
                    .orElseThrow(() -> new ResourceNotFoundException("Patient non trouvé"));
            builder.patientId(patient.getId());
            if (request.getCodeAssure() == null || request.getCodeAssure().isBlank()) {
                builder.codeAssure(patient.getNumeroAssure() != null ? patient.getNumeroAssure() : patient.getNumeroCni());
            }
        }

        FeuilleSoins feuille = builder
                .statut(StatutFeuilleSoins.EMISE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(user.getId())
                .build();

        ajouterHistorique(feuille, user, StatutFeuilleSoins.EMISE, "Feuille de soins délivrée au patient");
        return feuilleRepository.save(feuille);
    }

    /** Étape 2 — la structure sanitaire remplit la prise en charge (feuille déposée par le patient). */
    @Transactional
    public FeuilleSoins remplirPriseEnCharge(String id, PriseEnChargeFeuilleRequest request) {
        User user = getCurrentUser();
        if (user.getRole() != Role.STRUCTURE_SANITAIRE && user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Seule une structure sanitaire peut remplir la prise en charge");
        }
        FeuilleSoins feuille = getById(id);
        if (feuille.getStatut() == StatutFeuilleSoins.ANNULEE) {
            throw new BusinessException("Cette feuille de soins est annulée");
        }
        if (feuille.getStatut() == StatutFeuilleSoins.FACTUREE) {
            throw new BusinessException("Cette feuille de soins est déjà rattachée à une facture");
        }

        if (user.getRole() == Role.STRUCTURE_SANITAIRE) {
            StructureSanitaire structure = getCurrentStructure(user);
            feuille.setStructureSanitaireId(structure.getId());
            feuille.setStructureNom(structure.getNom());
        }

        Regime regime = feuille.getRegime() != null ? feuille.getRegime() : Regime.CONTRIBUTIF;
        List<PrestationSoins> prestations = request.getPrestations().stream()
                .map(p -> PrestationSoins.builder()
                        .date(p.getDate())
                        .designation(p.getDesignation())
                        .montant(p.getMontant())
                        .partAssure(p.getMontant() * regime.tauxBeneficiaire())
                        .partAssureur(p.getMontant() * regime.tauxSencsu())
                        .build())
                .collect(Collectors.toList());

        feuille.setPrestations(new ArrayList<>(prestations));
        feuille.setMontantTotal(prestations.stream().mapToDouble(PrestationSoins::getMontant).sum());
        feuille.setMontantTotalAssure(prestations.stream().mapToDouble(PrestationSoins::getPartAssure).sum());
        feuille.setMontantTotalAssureur(prestations.stream().mapToDouble(PrestationSoins::getPartAssureur).sum());
        if (request.getDiagnostic() != null && !request.getDiagnostic().isBlank()) {
            feuille.setDiagnostic(request.getDiagnostic());
        }
        feuille.setStatut(StatutFeuilleSoins.DEPOSEE_STRUCTURE);
        feuille.setUpdatedAt(LocalDateTime.now());

        ajouterHistorique(feuille, user, StatutFeuilleSoins.DEPOSEE_STRUCTURE,
                "Prise en charge remplie par la structure " + (feuille.getStructureNom() != null ? feuille.getStructureNom() : ""));
        return feuilleRepository.save(feuille);
    }

    @Transactional
    public FeuilleSoins lierFacture(String feuilleId, FactureStructure facture, User user) {
        FeuilleSoins feuille = feuilleRepository.findById(feuilleId)
                .orElseThrow(() -> new ResourceNotFoundException("Feuille de soins non trouvée"));
        if (feuille.getStatut() == StatutFeuilleSoins.ANNULEE) {
            throw new BusinessException("Cette feuille de soins est annulée");
        }
        
        feuille.setFactureStructureId(facture.getId());
        feuille.setFactureStructureNumero(facture.getNumero());
        if (feuille.getStructureSanitaireId() == null) {
            feuille.setStructureSanitaireId(facture.getStructureSanitaireId());
            feuille.setStructureNom(facture.getStructureNom());
        }

        // Le statut passe à FACTUREE
        if (feuille.getStatut() != StatutFeuilleSoins.FACTUREE) {
            feuille.setStatut(StatutFeuilleSoins.FACTUREE);
            ajouterHistorique(feuille, user, StatutFeuilleSoins.FACTUREE,
                    "Feuille rattachée à la facture " + facture.getNumero());
        }
        
        feuille.setUpdatedAt(LocalDateTime.now());
        return feuilleRepository.save(feuille);
    }

    @Transactional
    public void synchroniserAvecFacture(FactureStructure facture) {
        // Pour chaque lettre de garantie présente dans la facture
        java.util.Set<String> lettreIds = facture.getLignes().stream()
                .map(LigneFactureStructure::getLettreGarantieId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (String lettreId : lettreIds) {
            List<FeuilleSoins> feuilles = feuilleRepository.findByLettreGarantieId(lettreId);
            if (!feuilles.isEmpty()) {
                FeuilleSoins feuille = feuilles.get(0);
                
                // Recalculer les prestations
                List<PrestationSoins> prestations = new ArrayList<>();
                double total = 0;
                double totalAssure = 0;
                double totalAssureur = 0;

                for (LigneFactureStructure l : facture.getLignes()) {
                    if (Objects.equals(l.getLettreGarantieId(), lettreId)) {
                        PrestationSoins p = PrestationSoins.builder()
                                .date(l.getDatePriseEnCharge())
                                .designation(l.getDesignation())
                                .montant(l.getMontant())
                                .partAssure(l.getMontantBeneficiaire())
                                .partAssureur(l.getMontantSencsu())
                                .build();
                        prestations.add(p);
                        total += l.getMontant();
                        totalAssure += l.getMontantBeneficiaire();
                        totalAssureur += l.getMontantSencsu();

                        if (feuille.getDiagnostic() == null || feuille.getDiagnostic().isBlank()) {
                            feuille.setDiagnostic(l.getIndicationCbt() != null ? l.getIndicationCbt() : "Soins Structure");
                        }
                    }
                }

                feuille.setPrestations(prestations);
                feuille.setMontantTotal(total);
                feuille.setMontantTotalAssure(totalAssure);
                feuille.setMontantTotalAssureur(totalAssureur);
                
                // Assurer la liaison
                feuille.setFactureStructureId(facture.getId());
                feuille.setFactureStructureNumero(facture.getNumero());
                if (feuille.getStructureSanitaireId() == null) {
                    feuille.setStructureSanitaireId(facture.getStructureSanitaireId());
                    feuille.setStructureNom(facture.getStructureNom());
                }
                
                if (feuille.getStatut() != StatutFeuilleSoins.FACTUREE) {
                    feuille.setStatut(StatutFeuilleSoins.FACTUREE);
                }
                
                feuille.setUpdatedAt(LocalDateTime.now());
                feuilleRepository.save(feuille);
            }
        }
    }

    @Transactional
    public FeuilleSoins annuler(String id, AnnulationRequest request) {
        User user = getCurrentUser();
        if (user.getRole() != Role.BCSU && user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Accès refusé");
        }
        FeuilleSoins feuille = getById(id);
        if (feuille.getStatut() == StatutFeuilleSoins.ANNULEE) {
            throw new BusinessException("Cette feuille de soins est déjà annulée");
        }
        if (feuille.getStatut() == StatutFeuilleSoins.FACTUREE) {
            throw new BusinessException("Impossible d'annuler : la feuille est déjà facturée");
        }
        feuille.setStatut(StatutFeuilleSoins.ANNULEE);
        feuille.setCommentaireAnnulation(request.getMotif());
        feuille.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(feuille, user, StatutFeuilleSoins.ANNULEE, request.getMotif());
        return feuilleRepository.save(feuille);
    }

    public List<FeuilleSoins> findByPatient(String patientId) {
        return feuilleRepository.findByPatientId(patientId);
    }

    private String genererNumeroUnique(String prefixe) {
        for (int i = 0; i < 5; i++) {
            String candidat = NumeroGenerator.generer(prefixe);
            if (feuilleRepository.findByNumero(candidat).isEmpty()) {
                return candidat;
            }
        }
        throw new BusinessException("Impossible de générer un numéro unique, veuillez réessayer");
    }

    private void ajouterHistorique(FeuilleSoins feuille, User user, StatutFeuilleSoins statut, String commentaire) {
        if (feuille.getHistorique() == null) {
            feuille.setHistorique(new ArrayList<>());
        }
        feuille.getHistorique().add(HistoriqueDossier.builder()
                .date(LocalDateTime.now())
                .utilisateurId(user.getId())
                .utilisateurNom(user.getPrenom() + " " + user.getNom())
                .statut(statut.name())
                .commentaire(commentaire)
                .build());
    }
}
