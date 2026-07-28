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

        // Filtres appliqués en base (0 = pas de filtre ; regime null = tous).
        int m = (mois != null && mois > 0) ? mois : 0;
        int a = (annee != null && annee > 0) ? annee : 0;
        Regime regime = null;
        if (regimeStr != null && !regimeStr.trim().isEmpty()) {
            try {
                regime = Regime.valueOf(regimeStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                // Régime inconnu : on ignore le filtre (comportement historique).
            }
        }

        if (user.getRole() == Role.ADMIN || user.getRole() == Role.SERVICE_CENTRAL) {
            return factureRepository.findAllFiltre(m, a, regime);
        }
        if (user.getRole() == Role.SERVICE_REGIONAL) {
            // Le SR ne voit que les factures transmises et validées par le niveau de base
            // (exclusion BROUILLON / SOUMISE_CS / REJETEE_CS faite dans la requête).
            return factureRepository.findVisiblesParRegionFiltre(user.getRegionId(), m, a, regime);
        }
        if (user.getRole() == Role.STRUCTURE_SANITAIRE) {
            // Ses propres factures uniquement (les factures des PS rattachés sont dans l'onglet dédié)
            return factureRepository.findByStructureFiltre(user.getStructureSanitaireId(), m, a, regime);
        }
        return new ArrayList<>();
    }
    
    public List<FactureStructure> getAllForCurrentUser() {
        return getAllForCurrentUser(null, null, null);
    }

    /**
     * Identifiants + statuts des factures structure visibles par l'utilisateur, pour les
     * badges de notification. Même périmètre que {@link #getAllForCurrentUser()}, mais en
     * projection : aucune colonne JSONB (lignes, historique) n'est désérialisée.
     */
    public List<com.csu.pharmacie.dto.FactureStatutDto> getStatutsPourBadges() {
        User user = getCurrentUser();
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.SERVICE_CENTRAL) {
            return factureRepository.findAllStatuts();
        }
        if (user.getRole() == Role.SERVICE_REGIONAL) {
            return factureRepository.findStatutsVisiblesParRegion(user.getRegionId());
        }
        if (user.getRole() == Role.STRUCTURE_SANITAIRE) {
            return factureRepository.findStatutsByStructure(user.getStructureSanitaireId());
        }
        return new ArrayList<>();
    }

    public FactureStructure getById(String id) {
        FactureStructure facture = factureRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Facture non trouvée"));
        User user = getCurrentUser();
        if (user.getRole() == Role.STRUCTURE_SANITAIRE
                && !java.util.Objects.equals(facture.getStructureSanitaireId(), user.getStructureSanitaireId())
                && !estFactureDePosteRattache(facture, user)) {
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

        LettreGarantie lettre = null;
        Regime regimeFacture;
        boolean isPS = structure.getTypeStructure() != null && structure.getTypeStructure().equalsIgnoreCase("PS");

        if (request.getLettreGarantieNumero() != null && !request.getLettreGarantieNumero().isBlank()) {
            lettre = rechercherLettre(request.getLettreGarantieNumero());
            regimeFacture = lettre.getRegime();
        } else {
            if (!isPS) {
                throw new BusinessException("Le numéro de lettre de garantie est obligatoire pour cette structure");
            }
            if (request.getRegime() == null) {
                throw new BusinessException("Le régime est obligatoire pour l'ajout d'un patient sans lettre de garantie");
            }
            if (request.getPatientNom() == null || request.getPatientNom().isBlank() ||
                request.getPatientPrenom() == null || request.getPatientPrenom().isBlank()) {
                throw new BusinessException("Le nom et prénom du patient sont obligatoires");
            }
            regimeFacture = request.getRegime();
        }

        // Note: La vérification de doublon (dejaFacturee) a été retirée pour permettre 
        // au patient d'utiliser la même lettre de garantie plusieurs fois dans le délai de 30 jours.

        LocalDate now = LocalDate.now();
        int currentMois = now.getMonthValue();
        int currentAnnee = now.getYear();

        // Les saisies du mois sont COMPILÉES dans la facture mensuelle par régime,
        // qui reste en BROUILLON : l'envoi au Service Régional est une action
        // explicite (bouton « Envoyer » / envoi mensuel), jamais automatique.
        FactureStructure facture = factureRepository
                .findByStructureSanitaireIdAndRegimeAndMoisAndAnnee(structure.getId(), regimeFacture, currentMois, currentAnnee)
                .stream()
                .filter(f -> f.getStatut() == StatutFacture.BROUILLON || f.getStatut() == StatutFacture.REJETEE_SR || f.getStatut() == StatutFacture.REJETEE_CS)
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
                    .regime(regimeFacture)
                    .mois(currentMois)
                    .annee(currentAnnee)
                    .lignes(new ArrayList<>())
                    .statut(StatutFacture.BROUILLON)
                    .documentsComplementaires(new ArrayList<>())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .createdBy(user.getId())
                    .build();
        }

        List<LigneFactureStructure> nouvellesLignes = mapLignes(request.getLignes(), regimeFacture);
        
        // On enrichit chaque ligne avec les métadonnées du patient et de la lettre de garantie
        for (LigneFactureStructure ligne : nouvellesLignes) {
            if (lettre != null) {
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
            } else {
                ligne.setPatientNom(request.getPatientNom());
                ligne.setPatientPrenom(request.getPatientPrenom());
                ligne.setPatientTelephone(request.getPatientTelephone());
                ligne.setPatientDateNaissance(request.getPatientDateNaissance());
                ligne.setPatientSexe(request.getPatientSexe());
                ligne.setPatientMatricule(request.getPatientMatricule());
            }
            
            ligne.setPatientAdresse(request.getPatientAdresse());
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
                    .lettreGarantieNumero(lettre != null ? lettre.getNumero() : null)
                    .build());
        }
        if (request.getDocumentsComplementaires() != null) {
            for (com.csu.pharmacie.dto.DocumentComplementaireDto dto : request.getDocumentsComplementaires()) {
                dto.setLettreGarantieNumero(lettre != null ? lettre.getNumero() : null);
            }
            mergedDocs.addAll(mapDocuments(request.getDocumentsComplementaires()));
        }
        facture.setDocumentsComplementaires(mergedDocs);
        
        facture.setUpdatedAt(LocalDateTime.now());

        if (isNew) {
            ajouterHistorique(facture, user, facture.getStatut(), "Création de la facture mensuelle " + regimeFacture.name() + " (brouillon, envoi manuel)");
        } else {
            String patientLabel = lettre != null
                    ? (lettre.getPatientPrenom() + " " + lettre.getPatientNom())
                    : (request.getPatientPrenom() + " " + request.getPatientNom());
            ajouterHistorique(facture, user, facture.getStatut(), "Ajout de prestation pour le patient " + patientLabel);
        }
        
        FactureStructure enregistree = factureRepository.save(facture);

        // Fin du circuit de la feuille de soins : on lie s'il y a un ID
        if (request.getFeuilleSoinsId() != null && !request.getFeuilleSoinsId().isBlank()) {
            com.csu.pharmacie.entity.FeuilleSoins feuille =
                    feuilleSoinsService.lierFacture(request.getFeuilleSoinsId(), enregistree, user);
            if (lettre != null) {
                for (LigneFactureStructure l : enregistree.getLignes()) {
                    if (lettre.getId().equals(l.getLettreGarantieId())) {
                        l.setFeuilleSoinsNumero(feuille.getNumero());
                    }
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
        // Pas d'envoi automatique : la facture garde son statut, l'envoi reste une action explicite.
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, facture.getStatut(), "Modification de la facture mensuelle");
        
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
        boolean correction = facture.getStatut() == StatutFacture.REJETEE_SR || facture.getStatut() == StatutFacture.REJETEE_CS;
        if (facture.getStatut() != StatutFacture.BROUILLON && !correction) {
            throw new BusinessException("La facture n'est pas dans un état permettant l'envoi");
        }
        if (correction) {
            facture.setCommentaireRejet(null);
        }

        StructureSanitaire structure = getCurrentStructure(user);
        StatutFacture nouveauStatut;
        String actionHistorique;

        if ("PS".equalsIgnoreCase(structure.getTypeStructure())) {
            nouveauStatut = StatutFacture.SOUMISE_CS;
            actionHistorique = correction ? "Renvoi après correction (au CS)" : "Envoi au centre de santé rattaché";
        } else {
            nouveauStatut = StatutFacture.ENVOYEE;
            actionHistorique = correction ? "Renvoi après correction (au SR)" : "Envoi au service régional";
        }

        facture.setStatut(nouveauStatut);
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, nouveauStatut, actionHistorique);
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
        StructureSanitaire structure = getCurrentStructure(user);

        List<FactureStructure> brouillons = factureRepository
                .findByStructureSanitaireId(user.getStructureSanitaireId()).stream()
                .filter(f -> (f.getStatut() == StatutFacture.BROUILLON || f.getStatut() == StatutFacture.REJETEE_SR || f.getStatut() == StatutFacture.REJETEE_CS)
                        && f.getRegime() == regime && f.getMois() == mois && f.getAnnee() == annee)
                .collect(Collectors.toList());
        if (brouillons.isEmpty()) {
            throw new BusinessException("Aucune facture en brouillon à envoyer pour ce groupe");
        }
        for (FactureStructure facture : brouillons) {
            boolean correction = facture.getStatut() == StatutFacture.REJETEE_SR || facture.getStatut() == StatutFacture.REJETEE_CS;
            if (correction) {
                facture.setCommentaireRejet(null);
            }
            
            StatutFacture nouveauStatut;
            String actionHistorique;

            if ("PS".equalsIgnoreCase(structure.getTypeStructure())) {
                nouveauStatut = StatutFacture.SOUMISE_CS;
                actionHistorique = correction ? "Renvoi de la facture mensuelle après correction (au CS)" : "Envoi de la facture mensuelle au centre de santé rattaché";
            } else {
                nouveauStatut = StatutFacture.ENVOYEE;
                actionHistorique = correction ? "Renvoi de la facture mensuelle après correction (au SR)" : "Envoi de la facture mensuelle au service régional";
            }

            facture.setStatut(nouveauStatut);
            facture.setUpdatedAt(LocalDateTime.now());
            ajouterHistorique(facture, user, nouveauStatut, actionHistorique);
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

    /**
     * Purge les factures de structure encore en BROUILLON (jamais envoyées).
     * Réservé au service régional (sur sa région) et à l'administrateur.
     * Les factures déjà transmises ne sont jamais concernées.
     *
     * @param structureId limite la purge à une structure (facultatif)
     * @return nombre de brouillons supprimés
     */
    @Transactional
    public int purgerBrouillons(String structureId) {
        User user = getCurrentUser();
        if (user.getRole() != Role.SERVICE_REGIONAL && user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Seul le service régional ou un administrateur peut purger les brouillons");
        }

        List<FactureStructure> candidates;
        if (structureId != null && !structureId.isBlank()) {
            candidates = factureRepository.findByStructureSanitaireId(structureId);
        } else if (user.getRole() == Role.SERVICE_REGIONAL) {
            candidates = factureRepository.findByRegionId(user.getRegionId());
        } else {
            candidates = factureRepository.findAll();
        }

        // Un SR ne purge jamais hors de sa région, même si un structureId lui est passé.
        List<FactureStructure> brouillons = candidates.stream()
                .filter(f -> f.getStatut() == StatutFacture.BROUILLON)
                .filter(f -> user.getRole() != Role.SERVICE_REGIONAL
                        || java.util.Objects.equals(f.getRegionId(), user.getRegionId()))
                .toList();

        factureRepository.deleteAll(brouillons);
        return brouillons.size();
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

    /** Centre de Santé : SOUMISE_CS -> ENVOYEE (transmise au SR). */
    @Transactional
    public FactureStructure validerPoste(String id, String commentaire) {
        FactureStructure facture = getById(id);
        User user = getCurrentUser();
        if (user.getRole() != Role.STRUCTURE_SANITAIRE) {
            throw new ForbiddenException("Seul un centre de santé peut valider une facture de poste");
        }
        if (!estFactureDePosteRattache(facture, user)) {
            throw new ForbiddenException("Cette facture n'appartient pas à un de vos postes rattachés");
        }
        if (facture.getStatut() != StatutFacture.SOUMISE_CS) {
            throw new BusinessException("Seules les factures soumises au centre de santé peuvent être validées");
        }
        facture.setStatut(StatutFacture.ENVOYEE);
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, StatutFacture.ENVOYEE,
                (commentaire != null && !commentaire.isBlank()) ? commentaire : "Validée par le centre de santé et transmise au SR");
        return factureRepository.save(facture);
    }

    /** Centre de Santé : SOUMISE_CS -> REJETEE_CS (renvoyée au PS pour correction). */
    @Transactional
    public FactureStructure rejeterPoste(String id, String motif) {
        FactureStructure facture = getById(id);
        User user = getCurrentUser();
        if (user.getRole() != Role.STRUCTURE_SANITAIRE) {
            throw new ForbiddenException("Seul un centre de santé peut rejeter une facture de poste");
        }
        if (!estFactureDePosteRattache(facture, user)) {
            throw new ForbiddenException("Cette facture n'appartient pas à un de vos postes rattachés");
        }
        if (facture.getStatut() != StatutFacture.SOUMISE_CS) {
            throw new BusinessException("Seules les factures soumises au centre de santé peuvent être rejetées");
        }
        if (motif == null || motif.isBlank()) {
            throw new BusinessException("Le motif de rejet est obligatoire");
        }
        facture.setStatut(StatutFacture.REJETEE_CS);
        facture.setCommentaireRejet(motif);
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, StatutFacture.REJETEE_CS, motif);
        return factureRepository.save(facture);
    }

    /** Facture (hors brouillon) émise par un poste de santé rattaché à la structure de l'utilisateur. */
    private boolean estFactureDePosteRattache(FactureStructure facture, User user) {
        if (facture.getStatut() == StatutFacture.BROUILLON || facture.getStructureSanitaireId() == null) {
            return false;
        }
        return structureRepository.findById(facture.getStructureSanitaireId())
                .map(s -> java.util.Objects.equals(s.getStructureRattachementId(), user.getStructureSanitaireId()))
                .orElse(false);
    }

    /** La facture est-elle encore modifiable par la structure (avant envoi / après rejet) ? */
    private void checkModifiable(FactureStructure facture, User user) {
        if (user.getRole() != Role.STRUCTURE_SANITAIRE) {
            throw new ForbiddenException("Seule une structure sanitaire peut modifier ses saisies");
        }
        if (facture.getStatut() != StatutFacture.BROUILLON
                && facture.getStatut() != StatutFacture.REJETEE_SR
                && facture.getStatut() != StatutFacture.REJETEE_CS) {
            throw new BusinessException("Les saisies ne peuvent être modifiées ou supprimées qu'avant l'envoi mensuel (ou après un rejet)");
        }
    }

    private void recalculerTotaux(FactureStructure facture) {
        List<LigneFactureStructure> lignes = facture.getLignes() == null ? new ArrayList<>() : facture.getLignes();
        facture.setMontantTotal(sommeMontant(lignes));
        facture.setMontantTotalBeneficiaire(sommeBeneficiaire(lignes));
        facture.setMontantTotalSencsu(sommeSencsu(lignes));
    }

    /**
     * Supprime la ligne d'un patient (toutes ses prestations, identifiées par le numéro
     * de lettre de garantie) d'une facture mensuelle avant l'envoi, avec ses pièces jointes.
     */
    @Transactional
    public FactureStructure supprimerPatient(String factureId, String lettreNumero) {
        FactureStructure facture = getById(factureId);
        User user = getCurrentUser();
        checkModifiable(facture, user);

        List<LigneFactureStructure> restantes = facture.getLignes().stream()
                .filter(l -> !java.util.Objects.equals(l.getLettreGarantieNumero(), lettreNumero))
                .collect(Collectors.toList());
        if (restantes.size() == facture.getLignes().size()) {
            throw new ResourceNotFoundException("Aucune saisie trouvée pour cette lettre de garantie");
        }
        facture.setLignes(restantes);
        if (facture.getDocumentsComplementaires() != null) {
            facture.setDocumentsComplementaires(facture.getDocumentsComplementaires().stream()
                    .filter(d -> !java.util.Objects.equals(d.getLettreGarantieNumero(), lettreNumero))
                    .collect(Collectors.toList()));
        }
        recalculerTotaux(facture);
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, facture.getStatut(), "Suppression de la saisie du patient (LG " + lettreNumero + ")");
        FactureStructure enregistree = factureRepository.save(facture);
        feuilleSoinsService.synchroniserAvecFacture(enregistree);
        return enregistree;
    }

    /** Modifie une prestation (ligne) d'une facture mensuelle avant l'envoi. */
    @Transactional
    public FactureStructure modifierLigne(String factureId, int index, LigneFactureStructureDto dto) {
        FactureStructure facture = getById(factureId);
        User user = getCurrentUser();
        checkModifiable(facture, user);

        if (facture.getLignes() == null || index < 0 || index >= facture.getLignes().size()) {
            throw new ResourceNotFoundException("Ligne de facture introuvable");
        }
        LigneFactureStructure ligne = facture.getLignes().get(index);
        double tauxBenef = facture.getRegime().tauxBeneficiaire();
        double tauxSencsu = facture.getRegime().tauxSencsu();

        if (dto.getDesignation() != null && !dto.getDesignation().isBlank()) {
            ligne.setDesignation(dto.getDesignation());
        }
        if (dto.getCodeActe() != null) ligne.setCodeActe(dto.getCodeActe());
        if (dto.getDatePriseEnCharge() != null) ligne.setDatePriseEnCharge(dto.getDatePriseEnCharge());
        ligne.setQuantite(dto.getQuantite() > 0 ? dto.getQuantite() : ligne.getQuantite());
        if (dto.getPrixUnitaire() >= 0) ligne.setPrixUnitaire(dto.getPrixUnitaire());
        double montant = ligne.getQuantite() * ligne.getPrixUnitaire();
        ligne.setMontant(montant);
        ligne.setMontantBeneficiaire(montant * tauxBenef);
        ligne.setMontantSencsu(montant * tauxSencsu);

        recalculerTotaux(facture);
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, facture.getStatut(), "Modification d'une prestation (" + ligne.getDesignation() + ")");
        FactureStructure enregistree = factureRepository.save(facture);
        feuilleSoinsService.synchroniserAvecFacture(enregistree);
        return enregistree;
    }

    /** Supprime une prestation (ligne) d'une facture mensuelle avant l'envoi. */
    @Transactional
    public FactureStructure supprimerLigne(String factureId, int index) {
        FactureStructure facture = getById(factureId);
        User user = getCurrentUser();
        checkModifiable(facture, user);

        if (facture.getLignes() == null || index < 0 || index >= facture.getLignes().size()) {
            throw new ResourceNotFoundException("Ligne de facture introuvable");
        }
        LigneFactureStructure supprimee = facture.getLignes().remove(index);
        recalculerTotaux(facture);
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, facture.getStatut(), "Suppression d'une prestation (" + supprimee.getDesignation() + ")");
        FactureStructure enregistree = factureRepository.save(facture);
        feuilleSoinsService.synchroniserAvecFacture(enregistree);
        return enregistree;
    }

    /**
     * Factures des postes de santé polarisés (rattachés) par la structure de
     * l'utilisateur courant. Seules les factures ENVOYÉES (et suivantes) sont
     * visibles : les brouillons restent privés au poste.
     */
    public List<FactureStructure> getFacturesPostesRattaches(Integer mois, Integer annee, String regimeStr) {
        User user = getCurrentUser();
        if (user.getRole() != Role.STRUCTURE_SANITAIRE) {
            throw new ForbiddenException("Réservé aux structures sanitaires");
        }
        StructureSanitaire structure = getCurrentStructure(user);
        List<String> posteIds = structureRepository.findByStructureRattachementId(structure.getId()).stream()
                .map(StructureSanitaire::getId)
                .collect(Collectors.toList());
        if (posteIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<FactureStructure> factures = posteIds.stream()
                .flatMap(id -> factureRepository.findByStructureSanitaireId(id).stream())
                .filter(f -> f.getStatut() != StatutFacture.BROUILLON)
                .collect(Collectors.toList());

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
                // régime invalide : ignoré
            }
        }
        return factures;
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
