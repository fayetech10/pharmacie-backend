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
import com.csu.pharmacie.entity.VerificationCarte;
import com.csu.pharmacie.exception.BusinessException;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.LettreGarantieRepository;
import com.csu.pharmacie.repository.PatientRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.utils.CleRapprochement;
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
    private final com.csu.pharmacie.repository.FeuilleSoinsRepository feuilleSoinsRepository;
    private final NumeroSequenceService numeroSequenceService;
    private final FactureMensuelleBcsuService factureMensuelle;

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

    /**
     * Registre du mois de l'agent connecté : ses lettres, dans l'ordre d'émission (les
     * numéros se suivent). Les lettres annulées y restent pour que la numérotation n'ait
     * pas de trou.
     */
    public List<LettreGarantie> registreMensuel(int mois, int annee) {
        if (mois < 1 || mois > 12 || annee < 2000) {
            throw new BusinessException("Mois ou année invalide");
        }
        return getAllForCurrentUser(mois, annee, null).stream()
                .sorted(java.util.Comparator.comparing(LettreGarantie::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    /** « Prénom Nom » de l'utilisateur connecté, pour l'en-tête du registre. */
    public String nomUtilisateurCourant() {
        User user = getCurrentUser();
        return ((user.getPrenom() == null ? "" : user.getPrenom()) + " "
                + (user.getNom() == null ? "" : user.getNom())).trim();
    }

    /** Structure de rattachement de l'utilisateur connecté (vide si aucune). */
    public String structureUtilisateurCourant() {
        return numeroSequenceService.structureDeLAgent(getCurrentUser())
                .map(StructureSanitaire::getNom).orElse("");
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
        LocalDate datePriseEnCharge = datePriseEnCharge(request);
        validerReglesRegime(request, patient, datePriseEnCharge);
        StructureSanitaire structure = numeroSequenceService.structureDeLAgent(user).orElse(null);
        validerCleRapprochement(request, patient, structure, datePriseEnCharge);

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

        String structureNom = structure == null ? "" : structure.getNom();

        LettreGarantie lettre = LettreGarantie.builder()
                .numero(genererNumeroUnique("G", user))
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
                .cniNumeroOcr(numeroCni(request, patient))
                .verificationCarte(VerificationCarte.normaliser(request.getVerificationCarte()))
                .datePriseEnCharge(datePriseEnCharge)
                .accompagnantNom(texte(request.getAccompagnantNom()))
                .accompagnantCni(texte(request.getAccompagnantCni()))
                .numeroExtraitNaissance(texte(request.getNumeroExtraitNaissance()))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(user.getId())
                .build();

        ajouterHistorique(lettre, user, StatutLettreGarantie.BROUILLON, "Création du dossier");
        String verification = VerificationCarte.libelle(lettre.getVerificationCarte());
        if (verification != null) {
            ajouterHistorique(lettre, user, StatutLettreGarantie.BROUILLON, verification);
        }
        lettre = lettreGarantieRepository.save(lettre);
        lettre = rattacherFacture(lettre, structure, user);

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
        LocalDate datePriseEnCharge = datePriseEnCharge(request);
        validerReglesRegime(request, patient, datePriseEnCharge);
        StructureSanitaire structure = numeroSequenceService.structureDeLAgent(user).orElse(null);
        validerCleRapprochement(request, patient, structure, datePriseEnCharge);
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
        lettre.setCniNumeroOcr(numeroCni(request, patient));
        lettre.setDatePriseEnCharge(datePriseEnCharge);
        lettre.setAccompagnantNom(texte(request.getAccompagnantNom()));
        lettre.setAccompagnantCni(texte(request.getAccompagnantCni()));
        lettre.setNumeroExtraitNaissance(texte(request.getNumeroExtraitNaissance()));
        lettre.setUpdatedAt(LocalDateTime.now());

        ajouterHistorique(lettre, user, StatutLettreGarantie.BROUILLON, "Modification du dossier");
        // Une modification qui ne revérifie pas la carte garde l'issue d'origine.
        String verification = VerificationCarte.normaliser(request.getVerificationCarte());
        if (verification != null && !verification.equals(lettre.getVerificationCarte())) {
            lettre.setVerificationCarte(verification);
            ajouterHistorique(lettre, user, StatutLettreGarantie.BROUILLON, VerificationCarte.libelle(verification));
        }
        lettre = lettreGarantieRepository.save(lettre);
        lettre = rattacherFacture(lettre, structure, user);

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

        // Centres et EPS : le patient quitte la facture du mois (refusé si la structure l'a déjà facturé).
        factureMensuelle.detacher(lettre, user, "annulée");
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
        factureMensuelle.detacher(lettre, user, "supprimée");
        lettreGarantieRepository.deleteById(id);
    }

    private void checkProprietaire(LettreGarantie lettre, User user) {
        if (user.getRole() == Role.BCSU && !java.util.Objects.equals(lettre.getCreatedBy(), user.getId())) {
            throw new ForbiddenException("Accès refusé");
        }
    }

    /** Âge minimal du régime Sésame (personnes âgées). */
    private static final int AGE_MIN_SESAME = 60;
    /**
     * Âge maximal (exclu) du régime « Enfants de moins de 5 ans » — la règle de la
     * facturation (Regime.ageMaximumAnnees). À 6, une lettre accordée à un enfant de 5 ans
     * ne pouvait plus être facturée par la structure.
     */
    private static final int AGE_MAX_ZERO_CINQ_ANS = 5;

    /**
     * Contrôles d'éligibilité par régime (cahier des charges) :
     *  - Enfants 0-5 ans : date de naissance obligatoire, âge strictement inférieur à 6 ans ;
     *  - Sésame : date de naissance obligatoire, âge supérieur ou égal à 60 ans ;
     *  - Césarienne : le bénéficiaire doit être de sexe féminin.
     * L'âge s'apprécie à la date de prise en charge, comme à la facturation.
     */
    private void validerReglesRegime(LettreGarantieRequest request, Patient patient, LocalDate datePriseEnCharge) {
        Regime regime = request.getRegime();
        LocalDate dateNaissance = request.getDateNaissance() != null
                ? request.getDateNaissance() : patient.getDateNaissance();
        String sexe = (request.getSexe() != null && !request.getSexe().isBlank())
                ? request.getSexe() : patient.getSexe();

        if (regime == Regime.ZERO_CINQ_ANS || regime == Regime.SESAME) {
            if (dateNaissance == null) {
                throw new BusinessException("La date de naissance est obligatoire pour le régime "
                        + (regime == Regime.SESAME ? "Sésame" : "Enfants 0-5 ans"));
            }
            int age = Period.between(dateNaissance, datePriseEnCharge).getYears();
            if (regime == Regime.ZERO_CINQ_ANS && age >= AGE_MAX_ZERO_CINQ_ANS) {
                throw new BusinessException("Régime Enfants de moins de 5 ans : le bénéficiaire a " + age
                        + " ans, ce régime est réservé aux enfants de moins de 5 ans");
            }
            if (regime == Regime.SESAME && age < AGE_MIN_SESAME) {
                throw new BusinessException("Régime Sésame : le bénéficiaire doit avoir au moins "
                        + AGE_MIN_SESAME + " ans (âge calculé : " + age + " ans)");
            }
        }

        if (regime == Regime.CESARIENNE && !"F".equalsIgnoreCase(sexe == null ? "" : sexe.trim())) {
            throw new BusinessException("Régime Césarienne : le bénéficiaire doit être de sexe féminin");
        }
    }

    /** Date de prise en charge de la saisie : aujourd'hui par défaut, jamais dans le futur. */
    private static LocalDate datePriseEnCharge(LettreGarantieRequest request) {
        LocalDate date = request.getDatePriseEnCharge() != null ? request.getDatePriseEnCharge() : LocalDate.now();
        if (date.isAfter(LocalDate.now())) {
            throw new BusinessException("La date de prise en charge ne peut pas être dans le futur");
        }
        return date;
    }

    /**
     * Centres et EPS : l'identifiant du régime est obligatoire — c'est lui qui rapproche le
     * patient du fichier de facturation que la structure importe en fin de mois.
     */
    private void validerCleRapprochement(LettreGarantieRequest request, Patient patient, StructureSanitaire structure,
                                         LocalDate datePriseEnCharge) {
        Regime regime = request.getRegime();
        if (!factureMensuelle.concerne(structure, regime, datePriseEnCharge)) {
            return;
        }
        if (CleRapprochement.identifiant(regime, patient.getNumeroAssure(), numeroCni(request, patient),
                request.getAccompagnantCni()) == null) {
            throw new BusinessException("Le " + CleRapprochement.libelleIdentifiant(regime) + " est obligatoire : "
                    + "c'est la clé qui rapproche ce patient du fichier de facturation de la structure");
        }
    }

    /** N° de CNI saisi, à défaut celui du patient déjà enregistré. */
    private static String numeroCni(LettreGarantieRequest request, Patient patient) {
        String saisi = texte(request.getNumeroCni());
        return saisi != null ? saisi : texte(patient.getNumeroCni());
    }

    private static String texte(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }

    /** Place la saisie dans la facture du mois de sa structure (centres et EPS), et le trace sur la lettre. */
    private LettreGarantie rattacherFacture(LettreGarantie lettre, StructureSanitaire structure, User user) {
        String avant = lettre.getFactureStructureNumero();
        factureMensuelle.rattacher(lettre, structure, user);
        String apres = lettre.getFactureStructureNumero();
        if (apres != null && !apres.equals(avant)) {
            ajouterHistorique(lettre, user, lettre.getStatut(), "Patient enregistré dans la facture " + apres
                    + " — prestations à compléter par la structure");
        }
        return lettreGarantieRepository.save(lettre);
    }

    /** Réutilise le patient existant si patientId fourni, sinon en crée un nouveau à partir des champs inline. */
    private Patient resoudrePatient(LettreGarantieRequest request, User user) {
        if (request.getPatientId() != null && !request.getPatientId().isBlank()) {
            Patient existant = patientRepository.findById(request.getPatientId())
                    .orElseThrow(() -> new ResourceNotFoundException("Patient non trouvé"));
            boolean modifie = false;
            // Complète le sexe sur un patient déjà enregistré si l'agent vient de le renseigner.
            if (request.getSexe() != null && !request.getSexe().isBlank()
                    && !request.getSexe().equals(existant.getSexe())) {
                existant.setSexe(request.getSexe());
                modifie = true;
            }
            // Idem pour ses identifiants, qui servent de clé de rapprochement : un patient
            // enregistré sans eux les reçoit de cette saisie (jamais écrasés s'ils existent).
            if (texte(existant.getNumeroAssure()) == null && texte(request.getNumeroAssure()) != null) {
                existant.setNumeroAssure(request.getNumeroAssure().trim());
                modifie = true;
            }
            if (texte(existant.getNumeroCni()) == null && texte(request.getNumeroCni()) != null) {
                existant.setNumeroCni(request.getNumeroCni().trim());
                modifie = true;
            }
            if (modifie) {
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

    /**
     * Numéro LG codifié séquentiel = {type}-{initiales structure}-{séquence 4 chiffres}
     * (ex: G-DKCS-0001). Regénéré tant qu'il n'est pas unique.
     */
    private String genererNumeroUnique(String typeDocument, User user) {
        for (int i = 0; i < 5; i++) {
            String candidat = numeroSequenceService.prochainNumeroCodifie(typeDocument, user);
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
            feuille.setAccompagnantPrenomNom(lettre.getAccompagnantNom());
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
                    .accompagnantPrenomNom(lettre.getAccompagnantNom())
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
