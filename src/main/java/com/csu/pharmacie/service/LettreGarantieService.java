package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.AnnulationRequest;
import com.csu.pharmacie.dto.LettreGarantieRequest;
import com.csu.pharmacie.dto.PrestationGarantieDto;
import com.csu.pharmacie.entity.HistoriqueDossier;
import com.csu.pharmacie.entity.LettreGarantie;
import com.csu.pharmacie.entity.Patient;
import com.csu.pharmacie.entity.PrestationGarantie;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutLettreGarantie;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.BusinessException;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.LettreGarantieRepository;
import com.csu.pharmacie.repository.PatientRepository;
import com.csu.pharmacie.repository.StructureSanitaireRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.utils.NumeroGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LettreGarantieService {

    private final LettreGarantieRepository lettreGarantieRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final StructureSanitaireRepository structureSanitaireRepository;
    private final com.csu.pharmacie.repository.FeuilleSoinsRepository feuilleSoinsRepository;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    private void checkAgentAutorise(User user) {
        if (user.getRole() != Role.BCSU && user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Seul un agent BCSU peut gérer les lettres de garantie");
        }
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

    /** Le SR ne consulte que les dossiers (hors brouillon) des agents BCSU de sa région. */
    private void checkAccesRegional(User user, LettreGarantie lettre) {
        boolean memeRegion = lettre.getCreatedBy() != null && userRepository.findById(lettre.getCreatedBy())
                .map(agent -> java.util.Objects.equals(agent.getRegionId(), user.getRegionId()))
                .orElse(false);
        if (lettre.getStatut() == StatutLettreGarantie.BROUILLON || !memeRegion) {
            throw new ForbiddenException("Accès refusé");
        }
    }

    public List<LettreGarantie> getAllForCurrentUser(Integer mois, Integer annee, String regimeStr) {
        User user = getCurrentUser();
        List<LettreGarantie> lettres;
        
        if (user.getRole() == Role.ADMIN) {
            lettres = lettreGarantieRepository.findAll();
        } else if (user.getRole() == Role.SERVICE_REGIONAL) {
            // Visualisation régionale : lettres émises par les agents BCSU de la région
            // (les brouillons restent privés à l'agent).
            List<String> agents = bcsuIdsDeLaRegion(user.getRegionId());
            if (agents.isEmpty()) {
                lettres = new ArrayList<>();
            } else {
                lettres = lettreGarantieRepository.findByCreatedByIn(agents).stream()
                        .filter(l -> l.getStatut() != StatutLettreGarantie.BROUILLON)
                        .collect(Collectors.toList());
            }
        } else {
            checkAgentAutorise(user);
            lettres = lettreGarantieRepository.findByCreatedBy(user.getId());
        }
        
        if (mois != null && mois > 0) {
            lettres = lettres.stream().filter(l -> l.getCreatedAt().getMonthValue() == mois).collect(Collectors.toList());
        }
        if (annee != null && annee > 0) {
            lettres = lettres.stream().filter(l -> l.getCreatedAt().getYear() == annee).collect(Collectors.toList());
        }
        if (regimeStr != null && !regimeStr.trim().isEmpty()) {
            try {
                Regime regimeEnum = Regime.valueOf(regimeStr.trim().toUpperCase());
                lettres = lettres.stream().filter(l -> l.getRegime() == regimeEnum).collect(Collectors.toList());
            } catch (IllegalArgumentException e) {
                // Ignore invalid regime
            }
        }
        
        return lettres;
    }

    public List<LettreGarantie> getAllForCurrentUser() {
        return getAllForCurrentUser(null, null, null);
    }

    public LettreGarantie getById(String id) {
        LettreGarantie lettre = lettreGarantieRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lettre de garantie non trouvée"));
        User user = getCurrentUser();
        if (user.getRole() == Role.BCSU && !java.util.Objects.equals(lettre.getCreatedBy(), user.getId())) {
            throw new ForbiddenException("Accès refusé");
        }
        if (user.getRole() == Role.SERVICE_REGIONAL) {
            checkAccesRegional(user, lettre);
        }
        return lettre;
    }

    public LettreGarantie findByNumero(String numero) {
        User user = getCurrentUser();
        checkRechercheAutorisee(user);
        LettreGarantie lettre = lettreGarantieRepository.findByNumero(numero)
                .orElseThrow(() -> new ResourceNotFoundException("Aucune lettre de garantie pour ce numéro"));
        if (user.getRole() == Role.SERVICE_REGIONAL) {
            checkAccesRegional(user, lettre);
        }
        return lettre;
    }

    @Transactional
    public LettreGarantie creerLettreGarantie(LettreGarantieRequest request) {
        User user = getCurrentUser();
        checkAgentAutorise(user);

        Patient patient = resoudrePatient(request, user);

        // Vérification de la validité de 30 jours (Désactivée suite à la demande client)
        // LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        // List<LettreGarantie> recentes = lettreGarantieRepository.findByPatientId(patient.getId()).stream()
        //         .filter(l -> l.getStatut() == StatutLettreGarantie.EMISE || l.getStatut() == StatutLettreGarantie.BROUILLON)
        //         .filter(l -> l.getCreatedAt().isAfter(thirtyDaysAgo))
        //         .collect(Collectors.toList());
        //
        // if (!recentes.isEmpty()) {
        //     LettreGarantie existante = recentes.get(0);
        //     throw new BusinessException("Ce bénéficiaire possède déjà une lettre de garantie (" + existante.getNumero() + 
        //         ") en cours de validité (30 jours). Il peut utiliser la même lettre.");
        // }

        List<PrestationGarantie> prestations = mapPrestations(request.getPrestations(), request.getRegime());

        String structureNom = "";
        if (user.getStructureSanitaireId() != null) {
            structureNom = structureSanitaireRepository.findById(user.getStructureSanitaireId())
                    .map(StructureSanitaire::getNom).orElse("");
        } else {
            List<StructureSanitaire> structures = structureSanitaireRepository.findByBcsuId(user.getId());
            if (!structures.isEmpty()) {
                structureNom = structures.get(0).getNom();
            }
        }

        LettreGarantie lettre = LettreGarantie.builder()
                .numero(genererNumeroUnique("LG"))
                .patientId(patient.getId())
                .patientNom(patient.getNom())
                .patientPrenom(patient.getPrenom())
                .patientTelephone(patient.getTelephone())
                .patientDateNaissance(patient.getDateNaissance())
                .patientSexe(patient.getSexe())
                .patientNumeroAssure(patient.getNumeroAssure())
                .structureNom(structureNom)
                .regime(request.getRegime())
                .prestations(prestations)
                .montantTotal(sommeMontant(prestations))
                .montantTotalBeneficiaire(sommeBeneficiaire(prestations))
                .montantTotalSencsu(sommeSencsu(prestations))
                .statut(StatutLettreGarantie.BROUILLON)
                .cniRecto(request.getCniRecto())
                .cniVerso(request.getCniVerso())
                .carteAssureRecto(request.getCarteAssureRecto())
                .autreDocument(request.getAutreDocument())
                .autreDocumentTitre(request.getAutreDocumentTitre())
                .cniNumeroOcr(request.getNumeroCni())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(user.getId())
                .build();

        ajouterHistorique(lettre, user, StatutLettreGarantie.BROUILLON, "Création du dossier");
        lettre = lettreGarantieRepository.save(lettre);
        
        genererOuMettreAJourFeuilleSoins(lettre, user);
        
        return lettre;
    }

    @Transactional
    public LettreGarantie updateLettreGarantie(String id, LettreGarantieRequest request) {
        LettreGarantie lettre = getById(id);
        User user = getCurrentUser();
        checkAgentAutorise(user);
        checkProprietaire(lettre, user);

        if (lettre.getStatut() != StatutLettreGarantie.BROUILLON) {
            throw new BusinessException("Seul un dossier en brouillon peut être modifié");
        }

        Patient patient = resoudrePatient(request, user);
        List<PrestationGarantie> prestations = mapPrestations(request.getPrestations(), request.getRegime());

        lettre.setPatientId(patient.getId());
        lettre.setPatientNom(patient.getNom());
        lettre.setPatientPrenom(patient.getPrenom());
        lettre.setPatientTelephone(patient.getTelephone());
        lettre.setPatientDateNaissance(patient.getDateNaissance());
        lettre.setPatientSexe(patient.getSexe());
        lettre.setPatientNumeroAssure(patient.getNumeroAssure());
        lettre.setRegime(request.getRegime());
        lettre.setPrestations(prestations);
        lettre.setMontantTotal(sommeMontant(prestations));
        lettre.setMontantTotalBeneficiaire(sommeBeneficiaire(prestations));
        lettre.setMontantTotalSencsu(sommeSencsu(prestations));
        lettre.setCniRecto(request.getCniRecto());
        lettre.setCniVerso(request.getCniVerso());
        lettre.setCarteAssureRecto(request.getCarteAssureRecto());
        lettre.setAutreDocument(request.getAutreDocument());
        lettre.setAutreDocumentTitre(request.getAutreDocumentTitre());
        lettre.setCniNumeroOcr(request.getNumeroCni());
        lettre.setUpdatedAt(LocalDateTime.now());

        ajouterHistorique(lettre, user, StatutLettreGarantie.BROUILLON, "Modification du dossier");
        lettre = lettreGarantieRepository.save(lettre);
        
        genererOuMettreAJourFeuilleSoins(lettre, user);
        
        return lettre;
    }

    @Transactional
    public LettreGarantie emettre(String id) {
        LettreGarantie lettre = getById(id);
        User user = getCurrentUser();
        checkAgentAutorise(user);
        checkProprietaire(lettre, user);

        if (lettre.getStatut() != StatutLettreGarantie.BROUILLON) {
            throw new BusinessException("Seul un dossier en brouillon peut être émis");
        }

        lettre.setStatut(StatutLettreGarantie.EMISE);
        lettre.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(lettre, user, StatutLettreGarantie.EMISE, "Émission de la lettre de garantie");
        return lettreGarantieRepository.save(lettre);
    }

    @Transactional
    public LettreGarantie annuler(String id, AnnulationRequest request) {
        LettreGarantie lettre = getById(id);
        User user = getCurrentUser();
        checkAgentAutorise(user);
        checkProprietaire(lettre, user);

        if (lettre.getStatut() == StatutLettreGarantie.ANNULEE) {
            throw new BusinessException("Ce dossier est déjà annulé");
        }

        lettre.setStatut(StatutLettreGarantie.ANNULEE);
        lettre.setCommentaireAnnulation(request.getMotif());
        lettre.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(lettre, user, StatutLettreGarantie.ANNULEE, request.getMotif());
        return lettreGarantieRepository.save(lettre);
    }

    @Transactional
    public void deleteLettreGarantie(String id) {
        LettreGarantie lettre = getById(id);
        User user = getCurrentUser();
        checkAgentAutorise(user);
        checkProprietaire(lettre, user);

        if (lettre.getStatut() != StatutLettreGarantie.BROUILLON) {
            throw new BusinessException("Seul un dossier en brouillon peut être supprimé");
        }
        lettreGarantieRepository.deleteById(id);
    }

    private void checkProprietaire(LettreGarantie lettre, User user) {
        if (user.getRole() == Role.BCSU && !java.util.Objects.equals(lettre.getCreatedBy(), user.getId())) {
            throw new ForbiddenException("Accès refusé");
        }
    }

    /** Réutilise le patient existant si patientId fourni, sinon en crée un nouveau à partir des champs inline. */
    private Patient resoudrePatient(LettreGarantieRequest request, User user) {
        if (request.getPatientId() != null && !request.getPatientId().isBlank()) {
            Patient existant = patientRepository.findById(request.getPatientId())
                    .orElseThrow(() -> new ResourceNotFoundException("Patient non trouvé"));
            // Complète le sexe sur un patient déjà enregistré si l'agent vient de le renseigner.
            if (request.getSexe() != null && !request.getSexe().isBlank()
                    && !request.getSexe().equals(existant.getSexe())) {
                existant.setSexe(request.getSexe());
                existant.setUpdatedAt(LocalDateTime.now());
                existant = patientRepository.save(existant);
            }
            return existant;
        }

        Patient patient = Patient.builder()
                .nom(request.getNom())
                .prenom(request.getPrenom())
                .telephone(request.getTelephone())
                .regime(request.getRegime())
                .numeroCni(request.getNumeroCni())
                .numeroAssure(request.getNumeroAssure())
                .dateNaissance(request.getDateNaissance())
                .sexe(request.getSexe())
                .actif(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(user.getId())
                .build();
        return patientRepository.save(patient);
    }

    private List<PrestationGarantie> mapPrestations(List<PrestationGarantieDto> dtos, Regime regime) {
        if (dtos == null) return new ArrayList<>();
        double tauxBenef = regime.tauxBeneficiaire();
        double tauxSencsu = regime.tauxSencsu();
        return dtos.stream().map(dto -> PrestationGarantie.builder()
                        .designation(dto.getDesignation())
                        .datePriseEnCharge(dto.getDatePriseEnCharge())
                        .montant(dto.getMontant())
                        .motifCesarienne(regime == Regime.CESARIENNE ? dto.getMotifCesarienne() : null)
                        .montantBeneficiaire(dto.getMontant() * tauxBenef)
                        .montantSencsu(dto.getMontant() * tauxSencsu)
                        .build())
                .collect(Collectors.toList());
    }

    private double sommeMontant(List<PrestationGarantie> prestations) {
        return prestations.stream().mapToDouble(PrestationGarantie::getMontant).sum();
    }

    private double sommeBeneficiaire(List<PrestationGarantie> prestations) {
        return prestations.stream().mapToDouble(PrestationGarantie::getMontantBeneficiaire).sum();
    }

    private double sommeSencsu(List<PrestationGarantie> prestations) {
        return prestations.stream().mapToDouble(PrestationGarantie::getMontantSencsu).sum();
    }

    private String genererNumeroUnique(String prefixe) {
        for (int i = 0; i < 5; i++) {
            String candidat = NumeroGenerator.generer(prefixe);
            if (lettreGarantieRepository.findByNumero(candidat).isEmpty()) {
                return candidat;
            }
        }
        throw new BusinessException("Impossible de générer un numéro unique, veuillez réessayer");
    }

    private void ajouterHistorique(LettreGarantie lettre, User user, StatutLettreGarantie statut, String commentaire) {
        if (lettre.getHistorique() == null) {
            lettre.setHistorique(new ArrayList<>());
        }
        HistoriqueDossier action = HistoriqueDossier.builder()
                .date(LocalDateTime.now())
                .utilisateurId(user.getId())
                .utilisateurNom(user.getPrenom() + " " + user.getNom())
                .statut(statut.name())
                .commentaire(commentaire)
                .build();
        lettre.getHistorique().add(action);
    }

    private void genererOuMettreAJourFeuilleSoins(LettreGarantie lettre, User user) {
        List<com.csu.pharmacie.entity.FeuilleSoins> existantes = feuilleSoinsRepository.findByLettreGarantieId(lettre.getId());
        com.csu.pharmacie.entity.FeuilleSoins feuille;
        
        String codeAssure = lettre.getCniNumeroOcr();
        if (codeAssure == null || codeAssure.isBlank()) {
            codeAssure = patientRepository.findById(lettre.getPatientId())
                .map(p -> p.getNumeroAssure() != null ? p.getNumeroAssure() : p.getNumeroCni())
                .orElse(null);
        }

        Integer agePatient = null;
        if (lettre.getPatientDateNaissance() != null) {
            agePatient = Period.between(lettre.getPatientDateNaissance(), LocalDate.now()).getYears();
            if (agePatient < 0) agePatient = null;
        }

        if (!existantes.isEmpty()) {
            feuille = existantes.get(0);
            feuille.setPatientId(lettre.getPatientId());
            feuille.setPatientNom(lettre.getPatientNom());
            feuille.setPatientPrenom(lettre.getPatientPrenom());
            feuille.setPatientTelephone(lettre.getPatientTelephone());
            feuille.setRegime(lettre.getRegime());
            feuille.setCodeAssure(codeAssure);
            feuille.setSexe(lettre.getPatientSexe());
            feuille.setAge(agePatient);
            feuille.setUpdatedAt(LocalDateTime.now());
            feuilleSoinsRepository.save(feuille);
        } else {
            feuille = com.csu.pharmacie.entity.FeuilleSoins.builder()
                    .numero(genererNumeroUniqueFeuille("FDS"))
                    .patientId(lettre.getPatientId())
                    .patientNom(lettre.getPatientNom())
                    .patientPrenom(lettre.getPatientPrenom())
                    .patientTelephone(lettre.getPatientTelephone())
                    .regime(lettre.getRegime())
                    .codeAssure(codeAssure)
                    .sexe(lettre.getPatientSexe())
                    .age(agePatient)
                    .diagnostic("Consultation / Soins")
                    .lettreGarantieId(lettre.getId())
                    .lettreGarantieNumero(lettre.getNumero())
                    .statut(com.csu.pharmacie.entity.StatutFeuilleSoins.EMISE)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .createdBy(user.getId())
                    .build();
            
            if (feuille.getHistorique() == null) {
                feuille.setHistorique(new ArrayList<>());
            }
            feuille.getHistorique().add(HistoriqueDossier.builder()
                    .date(LocalDateTime.now())
                    .utilisateurId(user.getId())
                    .utilisateurNom(user.getPrenom() + " " + user.getNom())
                    .statut(com.csu.pharmacie.entity.StatutFeuilleSoins.EMISE.name())
                    .commentaire("Feuille de soins générée automatiquement avec la lettre de garantie")
                    .build());
                    
            feuilleSoinsRepository.save(feuille);
        }
    }

    private String genererNumeroUniqueFeuille(String prefixe) {
        for (int i = 0; i < 5; i++) {
            String candidat = NumeroGenerator.generer(prefixe);
            if (feuilleSoinsRepository.findByNumero(candidat).isEmpty()) {
                return candidat;
            }
        }
        throw new BusinessException("Impossible de générer un numéro unique de feuille de soins, veuillez réessayer");
    }
}
