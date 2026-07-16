package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.DossierPatientDto;
import com.csu.pharmacie.dto.PatientRequest;
import com.csu.pharmacie.entity.*;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PatientService {

    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final LettreGarantieRepository lettreGarantieRepository;
    private final BonCommandeRepository bonCommandeRepository;
    private final FactureRepository factureRepository;
    private final FactureStructureRepository factureStructureRepository;
    private final FeuilleSoinsRepository feuilleSoinsRepository;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    private void checkGestionAutorisee(User user) {
        if (user.getRole() != Role.BCSU && user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Accès refusé");
        }
    }

    /** Recherche par numéro CNI, numéro d'assuré, nom ou prénom (déduplique par id). */
    public List<Patient> search(String query) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>();
        }
        String q = query.trim();

        Map<String, Patient> resultats = new LinkedHashMap<>();
        patientRepository.findByNumeroCni(q).ifPresent(p -> resultats.put(p.getId(), p));
        patientRepository.findByNumeroAssure(q).ifPresent(p -> resultats.put(p.getId(), p));
        for (Patient p : patientRepository.findByNomContainingIgnoreCaseOrPrenomContainingIgnoreCase(q, q)) {
            resultats.put(p.getId(), p);
        }
        return new ArrayList<>(resultats.values());
    }

    /**
     * Recherche de tous les patients (pour l'administration ou la recherche globale).
     * Si c'est une structure sanitaire, retourne uniquement les patients liés à ses factures.
     */
    public List<Patient> getAll() {
        User user = getCurrentUser();
        if (user.getRole() == Role.STRUCTURE_SANITAIRE && user.getStructureSanitaireId() != null) {
            return patientRepository.findDistinctByStructureSanitaireId(user.getStructureSanitaireId());
        }
        return patientRepository.findAll();
    }

    public Patient getById(String id) {
        return patientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Patient non trouvé"));
    }

    /**
     * Récupère le dossier CSU consolidé d'un patient (Lettres, Bons, Factures pharmacie, Factures structure).
     */
    @Transactional(readOnly = true)
    public DossierPatientDto getDossierConsolide(String patientId) {
        Patient patient = getById(patientId);

        // 1. Lettres de garantie
        List<LettreGarantie> lettres = lettreGarantieRepository.findByPatientId(patientId);

        // 2. Bons de commande associés à ces lettres
        List<BonCommande> bons = lettres.stream()
                .map(lg -> bonCommandeRepository.findByLettreGarantieId(lg.getId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        // 3. Factures de structure associées à ces lettres
        Map<String, FactureStructure> facturesStructureMap = new HashMap<>();
        for (LettreGarantie lg : lettres) {
            List<FactureStructure> factures = factureStructureRepository.findByLettreGarantieId(lg.getId());
            for (FactureStructure f : factures) {
                facturesStructureMap.put(f.getId(), f);
            }
        }
        
        List<FactureStructure> facturesStructure = new ArrayList<>();
        for (FactureStructure f : facturesStructureMap.values()) {
            FactureStructure fClone = FactureStructure.builder()
                    .id(f.getId())
                    .numero(f.getNumero())
                    .structureSanitaireId(f.getStructureSanitaireId())
                    .structureNom(f.getStructureNom())
                    .regionId(f.getRegionId())
                    .mois(f.getMois())
                    .annee(f.getAnnee())
                    .statut(f.getStatut())
                    .regime(f.getRegime())
                    .createdAt(f.getCreatedAt())
                    .build();
            
            List<LigneFactureStructure> filteredLignes = new ArrayList<>();
            double total = 0;
            double totalCsu = 0;
            if (f.getLignes() != null) {
                for (LigneFactureStructure l : f.getLignes()) {
                    boolean matchId = l.getPatientId() != null && l.getPatientId().equals(patientId);
                    boolean matchMatricule = l.getPatientMatricule() != null && !l.getPatientMatricule().isBlank() 
                            && (l.getPatientMatricule().equalsIgnoreCase(patient.getNumeroAssure()) 
                                || l.getPatientMatricule().equalsIgnoreCase(patient.getNumeroCni()));
                    if (matchId || matchMatricule) {
                        filteredLignes.add(l);
                        total += l.getMontant();
                        totalCsu += l.getMontantSencsu();
                    }
                }
            }
            fClone.setLignes(filteredLignes);
            fClone.setMontantTotal(total);
            fClone.setMontantTotalSencsu(totalCsu);
            if (!filteredLignes.isEmpty()) {
                facturesStructure.add(fClone);
            }
        }

        // 4. Lignes de factures d'officine (pharmacie) associées à ce patient
        // On récupère les numéros de bon de commande ou on fait une correspondance par nom/matricule
        Set<String> numerosBons = bons.stream().map(BonCommande::getNumero).collect(Collectors.toSet());
        
        List<LigneFacture> lignesFactureOfficine = new ArrayList<>();
        List<Facture> toutesFactures = factureRepository.findAll();
        
        for (Facture f : toutesFactures) {
            if (f.getLignes() == null) continue;
            for (LigneFacture lf : f.getLignes()) {
                boolean matchBon = lf.getBonCommandeNumero() != null && !lf.getBonCommandeNumero().isBlank() 
                        && numerosBons.contains(lf.getBonCommandeNumero());
                
                boolean matchMatricule = lf.getPatientMatricule() != null && !lf.getPatientMatricule().isBlank() 
                        && (lf.getPatientMatricule().equalsIgnoreCase(patient.getNumeroAssure()) 
                            || lf.getPatientMatricule().equalsIgnoreCase(patient.getNumeroCni()));
                
                if (matchBon || matchMatricule) {
                    LigneFacture lfClone = LigneFacture.builder()
                            .patientNomPrenom(lf.getPatientNomPrenom())
                            .patientMatricule(lf.getPatientMatricule())
                            .medicament(lf.getMedicament())
                            .codeProduit(lf.getCodeProduit())
                            .quantite(lf.getQuantite())
                            .prixUnitaire(lf.getPrixUnitaire())
                            .montant(lf.getMontant())
                            .statutLigne(lf.getStatutLigne())
                            .motifRejet(lf.getMotifRejet())
                            .bonCommandeNumero(lf.getBonCommandeNumero())
                            .ticketCaisse(lf.getTicketCaisse())
                            .bonCommande(lf.getBonCommande())
                            .ordonnance(lf.getOrdonnance())
                            .pharmacieNom(f.getPharmacieNom())
                            .build();
                    lignesFactureOfficine.add(lfClone);
                }
            }
        }

        // 5. Feuilles de soins délivrées au patient (circuit patient → agent bureau → structure)
        List<FeuilleSoins> feuillesSoins = feuilleSoinsRepository.findByPatientId(patientId);

        return DossierPatientDto.builder()
                .patient(patient)
                .lettresGarantie(lettres)
                .bonsCommande(bons)
                .lignesFactureOfficine(lignesFactureOfficine)
                .facturesStructure(facturesStructure)
                .feuillesSoins(feuillesSoins)
                .build();
    }

    @Transactional
    public Patient create(PatientRequest request) {
        User user = getCurrentUser();
        checkGestionAutorisee(user);

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
        Patient savedPatient = patientRepository.save(patient);

        // 1. Générer automatiquement la lettre de garantie (directement avec le statut EMISE)
        String lgNumero = genererNumeroUniqueLG();
        LettreGarantie lettre = LettreGarantie.builder()
                .numero(lgNumero)
                .patientId(savedPatient.getId())
                .patientNom(savedPatient.getNom())
                .patientPrenom(savedPatient.getPrenom())
                .patientTelephone(savedPatient.getTelephone())
                .patientDateNaissance(savedPatient.getDateNaissance())
                .patientSexe(savedPatient.getSexe())
                .regime(savedPatient.getRegime())
                .statut(StatutLettreGarantie.EMISE)
                .cniNumeroOcr(savedPatient.getNumeroCni())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(user.getId())
                .prestations(new ArrayList<>())
                .historique(new ArrayList<>())
                .build();
        
        lettre.getHistorique().add(HistoriqueDossier.builder()
                .date(LocalDateTime.now())
                .utilisateurId(user.getId())
                .utilisateurNom(user.getPrenom() + " " + user.getNom())
                .statut(StatutLettreGarantie.EMISE.name())
                .commentaire("Génération et émission automatique lors de la création du patient")
                .build());
        lettreGarantieRepository.save(lettre);

        // 2. Générer automatiquement la feuille de soins (directement avec le statut EMISE)
        String fdsNumero = genererNumeroUniqueFDS();
        // Calcul de l'âge depuis la date de naissance du patient.
        Integer agePatient = null;
        if (savedPatient.getDateNaissance() != null) {
            agePatient = Period.between(savedPatient.getDateNaissance(), LocalDate.now()).getYears();
            if (agePatient < 0) agePatient = null;
        }

        FeuilleSoins feuille = FeuilleSoins.builder()
                .numero(fdsNumero)
                .patientId(savedPatient.getId())
                .patientNom(savedPatient.getNom())
                .patientPrenom(savedPatient.getPrenom())
                .patientTelephone(savedPatient.getTelephone())
                .regime(savedPatient.getRegime())
                .codeAssure(savedPatient.getNumeroAssure() != null && !savedPatient.getNumeroAssure().isBlank()
                        ? savedPatient.getNumeroAssure() : savedPatient.getNumeroCni())
                .sexe(savedPatient.getSexe())
                .age(agePatient)
                .lettreGarantieId(lettre.getId())
                .lettreGarantieNumero(lettre.getNumero())
                .statut(StatutFeuilleSoins.EMISE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(user.getId())
                .prestations(new ArrayList<>())
                .historique(new ArrayList<>())
                .build();

        feuille.getHistorique().add(HistoriqueDossier.builder()
                .date(LocalDateTime.now())
                .utilisateurId(user.getId())
                .utilisateurNom(user.getPrenom() + " " + user.getNom())
                .statut(StatutFeuilleSoins.EMISE.name())
                .commentaire("Délivrance automatique lors de la création du patient")
                .build());
        feuilleSoinsRepository.save(feuille);

        return savedPatient;
    }

    private String genererNumeroUniqueLG() {
        for (int i = 0; i < 5; i++) {
            String candidat = com.csu.pharmacie.utils.NumeroGenerator.generer("LG");
            if (lettreGarantieRepository.findByNumero(candidat).isEmpty()) {
                return candidat;
            }
        }
        throw new com.csu.pharmacie.exception.BusinessException("Impossible de générer un numéro unique de lettre de garantie, veuillez réessayer");
    }

    private String genererNumeroUniqueFDS() {
        for (int i = 0; i < 5; i++) {
            String candidat = com.csu.pharmacie.utils.NumeroGenerator.generer("FDS");
            if (feuilleSoinsRepository.findByNumero(candidat).isEmpty()) {
                return candidat;
            }
        }
        throw new com.csu.pharmacie.exception.BusinessException("Impossible de générer un numéro unique de feuille de soins, veuillez réessayer");
    }

    @Transactional
    public Patient update(String id, PatientRequest request) {
        User user = getCurrentUser();
        checkGestionAutorisee(user);

        Patient patient = getById(id);
        patient.setNom(request.getNom());
        patient.setPrenom(request.getPrenom());
        patient.setTelephone(request.getTelephone());
        patient.setRegime(request.getRegime());
        patient.setNumeroCni(request.getNumeroCni());
        patient.setNumeroAssure(request.getNumeroAssure());
        patient.setDateNaissance(request.getDateNaissance());
        patient.setSexe(request.getSexe());
        patient.setUpdatedAt(LocalDateTime.now());
        return patientRepository.save(patient);
    }

    @Transactional
    public void delete(String id) {
        User user = getCurrentUser();
        if (user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Seul un administrateur peut supprimer un patient");
        }
        Patient patient = getById(id);
        patient.setActif(false);
        patient.setUpdatedAt(LocalDateTime.now());
        patientRepository.save(patient);
    }
}
