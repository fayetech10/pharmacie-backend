package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.DocumentComplementaireDto;
import com.csu.pharmacie.dto.FactureStructureRequest;
import com.csu.pharmacie.dto.LigneFactureStructureDto;
import com.csu.pharmacie.entity.DocumentComplementaire;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.HistoriqueDossier;
import com.csu.pharmacie.entity.LettreGarantie;
import com.csu.pharmacie.entity.LigneFactureStructure;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.StatutLettreGarantie;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.BusinessException;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.FactureStructureRepository;
import com.csu.pharmacie.repository.LettreGarantieRepository;
import com.csu.pharmacie.repository.StructureSanitaireRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.utils.NumeroGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FactureStructureService {

    private final FactureStructureRepository factureRepository;
    private final LettreGarantieRepository lettreGarantieRepository;
    private final StructureSanitaireRepository structureRepository;
    private final UserRepository userRepository;
    private final FeuilleSoinsService feuilleSoinsService;
    private final com.csu.pharmacie.repository.PatientRepository patientRepository;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    /** Structure rattachée à l'agent courant (rôle STRUCTURE_SANITAIRE). */
    private StructureSanitaire getCurrentStructure(User user) {
        if (user.getStructureSanitaireId() == null) {
            throw new ForbiddenException("Aucune structure sanitaire n'est rattachée à ce compte");
        }
        return structureRepository.findById(user.getStructureSanitaireId())
                .orElseThrow(() -> new ResourceNotFoundException("Structure sanitaire non trouvée"));
    }

    /**
     * Recherche une lettre de garantie par numéro pour la facturation.
     * La structure ne peut consulter que les lettres émises par SON BCSU.
     */
    public LettreGarantie rechercherLettre(String numero) {
        LettreGarantie lettre = lettreGarantieRepository.findByNumero(numero)
                .orElseThrow(() -> new ResourceNotFoundException("Aucune lettre de garantie pour ce numéro"));
        if (lettre.getStatut() != StatutLettreGarantie.EMISE) {
            throw new BusinessException("Cette lettre de garantie n'est pas émise");
        }

        // Vérification de la validité de 30 jours
        if (lettre.getCreatedAt().isBefore(LocalDateTime.now().minusDays(30))) {
            throw new BusinessException("Cette lettre de garantie a expiré (délai de 30 jours dépassé). Le patient doit retourner au BCSU.");
        }

        return lettre;
    }

    public List<FactureStructure> getAllForCurrentUser(Integer mois, Integer annee, String regimeStr) {
        User user = getCurrentUser();
        List<FactureStructure> factures;
        
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.SERVICE_CENTRAL) {
            factures = factureRepository.findAll();
        } else if (user.getRole() == Role.SERVICE_REGIONAL) {
            // Le SR ne voit que les factures transmises (pas les brouillons des structures).
            factures = factureRepository.findByRegionId(user.getRegionId()).stream()
                    .filter(f -> f.getStatut() != StatutFacture.BROUILLON)
                    .collect(Collectors.toList());
        } else if (user.getRole() == Role.STRUCTURE_SANITAIRE) {
            factures = factureRepository.findByStructureSanitaireId(user.getStructureSanitaireId());
        } else {
            factures = new ArrayList<>();
        }
        
        if (mois != null && mois > 0) {
            factures = factures.stream().filter(f -> f.getMois() == mois).collect(Collectors.toList());
        }
        if (annee != null && annee > 0) {
            factures = factures.stream().filter(f -> f.getAnnee() == annee).collect(Collectors.toList());
        }
        if (regimeStr != null && !regimeStr.trim().isEmpty()) {
            try {
                Regime regimeEnum = Regime.valueOf(regimeStr.trim().toUpperCase());
                factures = factures.stream().filter(f -> f.getRegime() == regimeEnum).collect(Collectors.toList());
            } catch (IllegalArgumentException e) {
                // Ignore invalid regime string
            }
        }
        
        return factures;
    }
    
    public List<FactureStructure> getAllForCurrentUser() {
        return getAllForCurrentUser(null, null, null);
    }

    public FactureStructure getById(String id) {
        FactureStructure facture = factureRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Facture non trouvée"));
        User user = getCurrentUser();
        if (user.getRole() == Role.STRUCTURE_SANITAIRE
                && !java.util.Objects.equals(facture.getStructureSanitaireId(), user.getStructureSanitaireId())) {
            throw new ForbiddenException("Accès refusé");
        }
        if (user.getRole() == Role.SERVICE_REGIONAL
                && !java.util.Objects.equals(facture.getRegionId(), user.getRegionId())) {
            throw new ForbiddenException("Accès refusé");
        }

        boolean updated = false;
        if (facture.getLignes() != null) {
            for (LigneFactureStructure ligne : facture.getLignes()) {
                if (ligne.getPatientMatricule() == null || ligne.getPatientMatricule().trim().isEmpty()) {
                    if (ligne.getPatientId() != null) {
                        final LigneFactureStructure currentLigne = ligne;
                        patientRepository.findById(ligne.getPatientId()).ifPresent(p -> {
                            String mat = (p.getNumeroAssure() != null && !p.getNumeroAssure().trim().isEmpty())
                                    ? p.getNumeroAssure()
                                    : p.getNumeroCni();
                            if (mat != null && !mat.trim().isEmpty()) {
                                currentLigne.setPatientMatricule(mat);
                            }
                        });
                        updated = true; // Flag for save
                    }
                }
            }
        }
        if (updated) {
            factureRepository.save(facture);
        }

        return facture;
    }

    @Transactional
    public FactureStructure creer(FactureStructureRequest request) {
        User user = getCurrentUser();
        if (user.getRole() != Role.STRUCTURE_SANITAIRE) {
            throw new ForbiddenException("Seule une structure sanitaire peut créer une facture");
        }
        StructureSanitaire structure = getCurrentStructure(user);
        LettreGarantie lettre = rechercherLettre(request.getLettreGarantieNumero());

        // Note: La vérification de doublon (dejaFacturee) a été retirée pour permettre 
        // au patient d'utiliser la même lettre de garantie plusieurs fois dans le délai de 30 jours.

        LocalDate now = LocalDate.now();
        int currentMois = now.getMonthValue();
        int currentAnnee = now.getYear();

        // Récupère ou crée la facture mensuelle globale pour ce régime (brouillon, envoyée ou rejetée)
        FactureStructure facture = factureRepository
                .findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(structure.getId(), lettre.getRegime(), currentMois, currentAnnee)
                .stream()
                .filter(f -> f.getStatut() == StatutFacture.BROUILLON || f.getStatut() == StatutFacture.ENVOYEE || f.getStatut() == StatutFacture.REJETEE_SR)
                .findFirst()
                .orElse(null);

        boolean isNew = false;
        if (facture == null) {
            isNew = true;
            facture = FactureStructure.builder()
                    .numero(genererNumeroUnique("FS"))
                    .structureSanitaireId(structure.getId())
                    .structureNom(structure.getNom())
                    .regionId(structure.getRegionId())
                    .bcsuId(structure.getBcsuId())
                    .regime(lettre.getRegime())
                    .mois(currentMois)
                    .annee(currentAnnee)
                    .lignes(new ArrayList<>())
                    .statut(StatutFacture.ENVOYEE)
                    .documentsComplementaires(new ArrayList<>())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .createdBy(user.getId())
                    .build();
        } else {
            // Si la facture existante était en brouillon ou rejetée, elle passe automatiquement en ENVOYEE
            facture.setStatut(StatutFacture.ENVOYEE);
        }

        List<LigneFactureStructure> nouvellesLignes = mapLignes(request.getLignes(), lettre.getRegime());
        
        // On enrichit chaque ligne avec les métadonnées du patient et de la lettre de garantie
        for (LigneFactureStructure ligne : nouvellesLignes) {
            ligne.setLettreGarantieId(lettre.getId());
            ligne.setLettreGarantieNumero(lettre.getNumero());
            ligne.setPatientId(lettre.getPatientId());
            ligne.setPatientNom(lettre.getPatientNom());
            ligne.setPatientPrenom(lettre.getPatientPrenom());
            ligne.setPatientTelephone(lettre.getPatientTelephone());
            ligne.setPatientDateNaissance(request.getPatientDateNaissance() != null
                    ? request.getPatientDateNaissance() : lettre.getPatientDateNaissance());
            ligne.setPatientSexe(request.getPatientSexe() != null && !request.getPatientSexe().isBlank()
                    ? request.getPatientSexe() : lettre.getPatientSexe());
            ligne.setPatientAdresse(request.getPatientAdresse());
            String mat = request.getPatientMatricule() != null && !request.getPatientMatricule().isBlank()
                    ? request.getPatientMatricule() : lettre.getCniNumeroOcr();
            if (mat == null || mat.trim().isEmpty()) {
                if (lettre.getPatientId() != null) {
                    mat = patientRepository.findById(lettre.getPatientId())
                            .map(p -> (p.getNumeroAssure() != null && !p.getNumeroAssure().trim().isEmpty()) ? p.getNumeroAssure() : p.getNumeroCni())
                            .orElse(null);
                }
            }
            ligne.setPatientMatricule(mat);
            ligne.setPatientNumeroCni(request.getPatientNumeroCni());
            
            ligne.setService(request.getService());
            ligne.setDiagnostique(request.getDiagnostique());
            ligne.setNumeroRegistre(request.getNumeroRegistre());
            ligne.setIrcIra(request.getIrcIra());
            ligne.setIndicationCbt(request.getIndicationCbt());
            ligne.setNumeroRegistreBloc(request.getNumeroRegistreBloc());
            ligne.setDateHeureIntervention(request.getDateHeureIntervention());
            ligne.setDureeHospitalisationJours(request.getDureeHospitalisationJours());
            
            if (request.getFeuilleSoinsId() != null && !request.getFeuilleSoinsId().isBlank()) {
                ligne.setFeuilleSoinsId(request.getFeuilleSoinsId());
            }
        }

        List<LigneFactureStructure> mergedLignes = new ArrayList<>();
        if (facture.getLignes() != null) {
            mergedLignes.addAll(facture.getLignes());
        }
        mergedLignes.addAll(nouvellesLignes);
        facture.setLignes(mergedLignes);
        
        facture.setMontantTotal(sommeMontant(facture.getLignes()));
        facture.setMontantTotalBeneficiaire(sommeBeneficiaire(facture.getLignes()));
        facture.setMontantTotalSencsu(sommeSencsu(facture.getLignes()));
        
        List<DocumentComplementaire> mergedDocs = new ArrayList<>();
        if (facture.getDocumentsComplementaires() != null) {
            mergedDocs.addAll(facture.getDocumentsComplementaires());
        }
        if (request.getTicketCaisse() != null && !request.getTicketCaisse().isBlank()) {
            mergedDocs.add(DocumentComplementaire.builder()
                    .titre("Ticket de caisse")
                    .image(request.getTicketCaisse())
                    .lettreGarantieNumero(lettre.getNumero())
                    .build());
        }
        if (request.getDocumentsComplementaires() != null) {
            for (com.csu.pharmacie.dto.DocumentComplementaireDto dto : request.getDocumentsComplementaires()) {
                dto.setLettreGarantieNumero(lettre.getNumero());
            }
            mergedDocs.addAll(mapDocuments(request.getDocumentsComplementaires()));
        }
        facture.setDocumentsComplementaires(mergedDocs);
        
        facture.setUpdatedAt(LocalDateTime.now());

        if (isNew) {
            ajouterHistorique(facture, user, StatutFacture.ENVOYEE, "Création et envoi automatique de la facture mensuelle " + lettre.getRegime().name());
        } else {
            ajouterHistorique(facture, user, StatutFacture.ENVOYEE, "Ajout automatique de prestation pour le patient " + lettre.getPatientPrenom() + " " + lettre.getPatientNom());
        }
        
        FactureStructure enregistree = factureRepository.save(facture);

        // Fin du circuit de la feuille de soins : on lie s'il y a un ID
        if (request.getFeuilleSoinsId() != null && !request.getFeuilleSoinsId().isBlank()) {
            com.csu.pharmacie.entity.FeuilleSoins feuille =
                    feuilleSoinsService.lierFacture(request.getFeuilleSoinsId(), enregistree, user);
            for (LigneFactureStructure l : enregistree.getLignes()) {
                if (lettre.getId().equals(l.getLettreGarantieId())) {
                    l.setFeuilleSoinsNumero(feuille.getNumero());
                }
            }
            enregistree = factureRepository.save(enregistree);
        }
        
        // Dans tous les cas, on synchronise les lignes de la facture avec les feuilles de soins correspondantes
        feuilleSoinsService.synchroniserAvecFacture(enregistree);

        return enregistree;
    }

    @Transactional
    public FactureStructure update(String id, FactureStructureRequest request) {
        FactureStructure facture = getById(id);
        User user = getCurrentUser();
        if (facture.getStatut() != StatutFacture.BROUILLON 
                && facture.getStatut() != StatutFacture.ENVOYEE 
                && facture.getStatut() != StatutFacture.REJETEE_SR) {
            throw new BusinessException("Seule une facture en brouillon, envoyée ou rejetée peut être modifiée");
        }

        List<LigneFactureStructure> lignes = mapLignes(request.getLignes(), facture.getRegime());
        
        // Conserver les métadonnées patient existantes pour les lignes correspondantes
        for (int i = 0; i < lignes.size() && i < request.getLignes().size(); i++) {
            LigneFactureStructureDto dto = request.getLignes().get(i);
            LigneFactureStructure entity = lignes.get(i);
            
            // Si la ligne envoyée a des métadonnées spécifiques de patient, on les met à jour.
            // Sinon on peut garder celles du brouillon original
            entity.setLettreGarantieId(facture.getLignes().stream()
                    .filter(l -> l.getDesignation().equals(entity.getDesignation()))
                    .map(LigneFactureStructure::getLettreGarantieId)
                    .findFirst().orElse(null));
            entity.setLettreGarantieNumero(facture.getLignes().stream()
                    .filter(l -> l.getDesignation().equals(entity.getDesignation()))
                    .map(LigneFactureStructure::getLettreGarantieNumero)
                    .findFirst().orElse(request.getLettreGarantieNumero()));
            entity.setPatientId(facture.getLignes().stream()
                    .filter(l -> l.getDesignation().equals(entity.getDesignation()))
                    .map(LigneFactureStructure::getPatientId)
                    .findFirst().orElse(null));
            entity.setPatientNom(facture.getLignes().stream()
                    .filter(l -> l.getDesignation().equals(entity.getDesignation()))
                    .map(LigneFactureStructure::getPatientNom)
                    .findFirst().orElse(null));
            entity.setPatientPrenom(facture.getLignes().stream()
                    .filter(l -> l.getDesignation().equals(entity.getDesignation()))
                    .map(LigneFactureStructure::getPatientPrenom)
                    .findFirst().orElse(null));
            entity.setPatientTelephone(facture.getLignes().stream()
                    .filter(l -> l.getDesignation().equals(entity.getDesignation()))
                    .map(LigneFactureStructure::getPatientTelephone)
                    .findFirst().orElse(null));
            
            entity.setPatientDateNaissance(request.getPatientDateNaissance());
            entity.setPatientSexe(request.getPatientSexe());
            entity.setPatientAdresse(request.getPatientAdresse());
            entity.setPatientMatricule(request.getPatientMatricule());
            entity.setPatientNumeroCni(request.getPatientNumeroCni());
            entity.setService(request.getService());
            entity.setDiagnostique(request.getDiagnostique());
            entity.setNumeroRegistre(request.getNumeroRegistre());
            entity.setIrcIra(request.getIrcIra());
            entity.setIndicationCbt(request.getIndicationCbt());
            entity.setNumeroRegistreBloc(request.getNumeroRegistreBloc());
            entity.setDateHeureIntervention(request.getDateHeureIntervention());
            entity.setDureeHospitalisationJours(request.getDureeHospitalisationJours());
        }

        facture.setLignes(lignes);
        facture.setMontantTotal(sommeMontant(lignes));
        facture.setMontantTotalBeneficiaire(sommeBeneficiaire(lignes));
        facture.setMontantTotalSencsu(sommeSencsu(lignes));
        facture.setTicketCaisse(request.getTicketCaisse());
        facture.setDocumentsComplementaires(mapDocuments(request.getDocumentsComplementaires()));
        facture.setStatut(StatutFacture.ENVOYEE); // Passe automatiquement en ENVOYEE
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, StatutFacture.ENVOYEE, "Modification globale de la facture mensuelle et envoi automatique");
        
        FactureStructure enregistree = factureRepository.save(facture);
        
        // Synchroniser les lignes avec les feuilles de soins
        feuilleSoinsService.synchroniserAvecFacture(enregistree);
        
        return enregistree;
    }

    @Transactional
    public FactureStructure envoyer(String id) {
        FactureStructure facture = getById(id);
        User user = getCurrentUser();
        if (user.getRole() != Role.STRUCTURE_SANITAIRE) {
            throw new ForbiddenException("Seule une structure sanitaire peut envoyer une facture");
        }
        boolean correction = facture.getStatut() == StatutFacture.REJETEE_SR;
        if (facture.getStatut() != StatutFacture.BROUILLON && !correction) {
            throw new BusinessException("La facture n'est pas dans un état permettant l'envoi");
        }
        if (correction) {
            facture.setCommentaireRejet(null);
        }
        facture.setStatut(StatutFacture.ENVOYEE);
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, StatutFacture.ENVOYEE,
                correction ? "Renvoi après correction" : "Envoi au service régional");
        return factureRepository.save(facture);
    }

    /**
     * Envoi groupé « facture mensuelle » (cahier des charges) : transmet au Service
     * Régional tous les brouillons du groupe régime + mois/année de la structure courante.
     */
    @Transactional
    public List<FactureStructure> envoyerGroupe(Regime regime, int mois, int annee) {
        User user = getCurrentUser();
        if (user.getRole() != Role.STRUCTURE_SANITAIRE) {
            throw new ForbiddenException("Seule une structure sanitaire peut envoyer ses factures");
        }
        List<FactureStructure> brouillons = factureRepository
                .findByStructureSanitaireId(user.getStructureSanitaireId()).stream()
                .filter(f -> f.getStatut() == StatutFacture.BROUILLON
                        && f.getRegime() == regime && f.getMois() == mois && f.getAnnee() == annee)
                .collect(Collectors.toList());
        if (brouillons.isEmpty()) {
            throw new BusinessException("Aucune facture en brouillon à envoyer pour ce groupe");
        }
        for (FactureStructure facture : brouillons) {
            facture.setStatut(StatutFacture.ENVOYEE);
            facture.setUpdatedAt(LocalDateTime.now());
            ajouterHistorique(facture, user, StatutFacture.ENVOYEE,
                    "Envoi groupé de la facture mensuelle au service régional");
        }
        return factureRepository.saveAll(brouillons);
    }

    @Transactional
    public void delete(String id) {
        FactureStructure facture = getById(id);
        if (facture.getStatut() != StatutFacture.BROUILLON) {
            throw new BusinessException("Seule une facture en brouillon peut être supprimée");
        }
        factureRepository.deleteById(id);
    }

    /** Service Régional : ENVOYEE -> VALIDEE_SR. */
    @Transactional
    public FactureStructure valider(String id, String commentaire) {
        FactureStructure facture = getById(id);
        User user = getCurrentUser();
        if (user.getRole() != Role.SERVICE_REGIONAL) {
            throw new ForbiddenException("Seul le service régional peut valider une facture de structure");
        }
        if (facture.getStatut() != StatutFacture.ENVOYEE) {
            throw new BusinessException("Seules les factures reçues (envoyées) peuvent être validées");
        }
        facture.setStatut(StatutFacture.VALIDEE_SR);
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, StatutFacture.VALIDEE_SR,
                (commentaire != null && !commentaire.isBlank()) ? commentaire : "Validée par le service régional");
        return factureRepository.save(facture);
    }

    /** Service Régional : ENVOYEE -> REJETEE_SR (motif obligatoire, renvoyée à la structure). */
    @Transactional
    public FactureStructure rejeter(String id, String motif) {
        FactureStructure facture = getById(id);
        User user = getCurrentUser();
        if (user.getRole() != Role.SERVICE_REGIONAL) {
            throw new ForbiddenException("Seul le service régional peut rejeter une facture de structure");
        }
        if (facture.getStatut() != StatutFacture.ENVOYEE) {
            throw new BusinessException("Seules les factures reçues peuvent être rejetées");
        }
        if (motif == null || motif.isBlank()) {
            throw new BusinessException("Le motif de rejet est obligatoire");
        }
        facture.setStatut(StatutFacture.REJETEE_SR);
        facture.setCommentaireRejet(motif);
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, StatutFacture.REJETEE_SR, motif);
        return factureRepository.save(facture);
    }

    // ----- Helpers -----

    private List<LigneFactureStructure> mapLignes(List<LigneFactureStructureDto> dtos, Regime regime) {
        double tauxBenef = regime.tauxBeneficiaire();
        double tauxSencsu = regime.tauxSencsu();
        return dtos.stream().map(dto -> {
            double montant = dto.getQuantite() * dto.getPrixUnitaire();
            return LigneFactureStructure.builder()
                    .designation(dto.getDesignation())
                    .codeActe(dto.getCodeActe())
                    .datePriseEnCharge(dto.getDatePriseEnCharge())
                    .quantite(dto.getQuantite())
                    .prixUnitaire(dto.getPrixUnitaire())
                    .montant(montant)
                    .motifCesarienne(regime == Regime.CESARIENNE ? dto.getMotifCesarienne() : null)
                    .montantBeneficiaire(montant * tauxBenef)
                    .montantSencsu(montant * tauxSencsu)
                    .build();
        }).collect(Collectors.toList());
    }

    private List<DocumentComplementaire> mapDocuments(List<DocumentComplementaireDto> dtos) {
        if (dtos == null) return new ArrayList<>();
        return dtos.stream()
                .map(dto -> DocumentComplementaire.builder()
                        .titre(dto.getTitre())
                        .image(dto.getImage())
                        .lettreGarantieNumero(dto.getLettreGarantieNumero())
                        .build())
                .collect(Collectors.toList());
    }

    private double sommeMontant(List<LigneFactureStructure> lignes) {
        return lignes.stream().mapToDouble(LigneFactureStructure::getMontant).sum();
    }

    private double sommeBeneficiaire(List<LigneFactureStructure> lignes) {
        return lignes.stream().mapToDouble(LigneFactureStructure::getMontantBeneficiaire).sum();
    }

    private double sommeSencsu(List<LigneFactureStructure> lignes) {
        return lignes.stream().mapToDouble(LigneFactureStructure::getMontantSencsu).sum();
    }

    private String genererNumeroUnique(String prefixe) {
        for (int i = 0; i < 5; i++) {
            String candidat = NumeroGenerator.generer(prefixe);
            if (factureRepository.findByNumero(candidat).isEmpty()) {
                return candidat;
            }
        }
        throw new BusinessException("Impossible de générer un numéro unique, veuillez réessayer");
    }

    private void ajouterHistorique(FactureStructure facture, User user, StatutFacture statut, String commentaire) {
        if (facture.getHistorique() == null) {
            facture.setHistorique(new ArrayList<>());
        }
        HistoriqueDossier action = HistoriqueDossier.builder()
                .date(LocalDateTime.now())
                .utilisateurId(user.getId())
                .utilisateurNom(user.getPrenom() + " " + user.getNom())
                .statut(statut.name())
                .commentaire(commentaire)
                .build();
        facture.getHistorique().add(action);
    }
}
