package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.ControleDelivranceDto;
import com.csu.pharmacie.dto.FactureRequest;
import com.csu.pharmacie.dto.FactureStatutDto;
import com.csu.pharmacie.dto.LigneDecisionRequest;
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
import com.csu.pharmacie.repository.BonCommandeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final BonCommandeRepository bonCommandeRepository;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    public List<Facture> getAllFactures(Integer mois, Integer annee) {
        User user = getCurrentUser();

        // Filtres appliqués en base (0 = pas de filtre) : on ne charge plus jamais
        // l'historique complet pour n'en garder qu'un mois.
        int m = (mois != null && mois > 0) ? mois : 0;
        int a = (annee != null && annee > 0) ? annee : 0;

        switch (user.getRole()) {
            case PHARMACIEN:
                return factureRepository.findByPharmacieIdFiltre(user.getPharmacieId(), m, a);
            case SERVICE_REGIONAL:
                return factureRepository.findByRegionIdFiltre(user.getRegionId(), m, a);
            case SERVICE_CENTRAL:
            case ADMIN:
                return factureRepository.findAllFiltre(m, a);
            default:
                return new ArrayList<>();
        }
    }
    
    public List<Facture> getAllFactures() {
        return getAllFactures(null, null);
    }

    /**
     * Identifiants + statuts des factures visibles par l'utilisateur, pour les badges
     * de notification. Même périmètre que {@link #getAllFactures()}, mais en projection :
     * aucune colonne JSONB (lignes, historique) n'est désérialisée.
     */
    public List<FactureStatutDto> getStatutsPourBadges() {
        User user = getCurrentUser();
        switch (user.getRole()) {
            case PHARMACIEN:
                return factureRepository.findStatutsByPharmacie(user.getPharmacieId());
            case SERVICE_REGIONAL:
                return factureRepository.findStatutsByRegion(user.getRegionId());
            case SERVICE_CENTRAL:
            case ADMIN:
                return factureRepository.findAllStatuts();
            default:
                return new ArrayList<>();
        }
    }

    /**
     * Variante allégée de {@link #getAllFactures()} pour les vues liste / tableau de bord :
     * exclut les images base64 des lignes (non affichées en liste) pour des réponses bien
     * plus légères. Les exports (Excel/PDF) continuent d'utiliser {@link #getAllFactures()}.
     */
    public List<Facture> getAllFacturesLight(Integer mois, Integer annee) {
        return stripPieces(getAllFactures(mois, annee));
    }

    /**
     * Retire les images base64 des lignes (ticket de caisse, bon de commande, ordonnance),
     * non affichées dans les vues liste / tableau de bord, pour des réponses bien plus légères.
     * Les factures viennent d'une lecture (entités détachées hors transaction) : la mutation
     * reste en mémoire et n'entraîne aucune écriture en base. Le détail d'une facture
     * (getFactureById) reste complet pour le dossier patient.
     */
    private List<Facture> stripPieces(List<Facture> factures) {
        for (Facture f : factures) {
            if (f.getLignes() == null) continue;
            for (LigneFacture l : f.getLignes()) {
                l.setTicketCaisse(null);
                l.setBonCommande(null);
                l.setOrdonnance(null);
            }
        }
        return factures;
    }

    /**
     * Usage multi-pharmacies : vérifie que les nouvelles lignes ne dépassent pas les lignes
     * disponibles de chaque bon référencé, puis incrémente le compteur {@code lignesUtilisees}.
     * Un bon n'est « complet » que quand toutes ses lignes ({@code nombreLignes}) sont consommées.
     *
     * @param nouvellesLignes les lignes ajoutées lors de cette opération (pas les lignes déjà en base)
     */
    private void validerEtConsommerLignesBons(List<LigneFacture> nouvellesLignes) {
        if (nouvellesLignes == null || nouvellesLignes.isEmpty()) return;

        // Regroupe les nouvelles lignes par numéro de bon de commande.
        Map<String, Long> lignesParBon = nouvellesLignes.stream()
                .map(LigneFacture::getBonCommandeNumero)
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.groupingBy(n -> n, Collectors.counting()));

        for (Map.Entry<String, Long> entry : lignesParBon.entrySet()) {
            String numero = entry.getKey();
            int nbNouvelles = entry.getValue().intValue();

            bonCommandeRepository.findByNumero(numero).ifPresent(bon -> {
                int disponibles = bon.lignesDisponibles();
                if (nbNouvelles > disponibles) {
                    throw new BusinessException(
                            "Le bon de commande " + numero + " n'a que " + disponibles
                                    + " ligne(s) disponible(s), mais vous essayez d'en utiliser " + nbNouvelles
                                    + ". Le patient peut se rendre dans une autre pharmacie avec ce bon pour les médicaments restants.");
                }
                // Incrémente le compteur de lignes consommées.
                bon.setLignesUtilisees((bon.getLignesUtilisees() != null ? bon.getLignesUtilisees() : 0) + nbNouvelles);
                bon.setDateDerniereUtilisation(LocalDateTime.now());
                bon.setUpdatedAt(LocalDateTime.now());
                bonCommandeRepository.save(bon);
            });
        }
    }

    public Facture getFactureById(String id) {
        Facture facture = factureRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Facture non trouvée"));
                
        User user = getCurrentUser();
        
        if (user.getRole() == Role.PHARMACIEN && !java.util.Objects.equals(facture.getPharmacieId(), user.getPharmacieId())) {
            throw new ForbiddenException("Accès refusé");
        }
        
        if (user.getRole() == Role.SERVICE_REGIONAL && !java.util.Objects.equals(facture.getRegionId(), user.getRegionId())) {
            throw new ForbiddenException("Accès refusé");
        }
        
        return facture;
    }

    @Transactional
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

    /**
     * Contrôle « médicament déjà délivré durant le mois ».
     *
     * Le périmètre est volontairement NATIONAL (aucun filtre pharmacie ni région) : le but
     * est justement de détecter qu'un bénéficiaire se fait délivrer le même traitement dans
     * une autre pharmacie, éventuellement d'une autre région. Ne pas réutiliser
     * {@link #getAllFactures()} ici, qui restreint les résultats selon le rôle appelant.
     *
     * Le bénéficiaire est identifié par son matricule (numéro d'immatriculation) et, à défaut,
     * par son nom — cas d'un bon de commande physique où seul le nom est disponible.
     */
    public ControleDelivranceDto controlerDelivrance(String matricule, String nomPatient,
                                                     String medicament, Integer mois, Integer annee) {
        String matriculeNorm = normaliser(matricule);
        String nomNorm = normaliser(nomPatient);
        String medicamentNorm = normaliser(medicament);

        if ((matriculeNorm.isEmpty() && nomNorm.isEmpty()) || medicamentNorm.isEmpty()) {
            return ControleDelivranceDto.builder()
                    .quantiteDejaDelivree(0)
                    .delivrances(new ArrayList<>())
                    .build();
        }

        LocalDate now = LocalDate.now();
        int moisCible = mois != null ? mois : now.getMonthValue();
        int anneeCible = annee != null ? annee : now.getYear();

        // Le JSONB `lignes` est scanné par LIKE : on ne charge que les factures candidates.
        Map<String, Facture> candidates = new HashMap<>();
        for (String terme : List.of(matricule, nomPatient)) {
            if (terme == null || terme.isBlank()) continue;
            for (Facture f : factureRepository.findByLignesContaining(terme.trim())) {
                candidates.putIfAbsent(f.getId(), f);
            }
        }

        List<ControleDelivranceDto.Delivrance> delivrances = new ArrayList<>();
        int total = 0;

        for (Facture f : candidates.values()) {
            if (f.getMois() != moisCible || f.getAnnee() != anneeCible) continue;
            if (f.getLignes() == null) continue;

            for (LigneFacture l : f.getLignes()) {
                boolean memePatient = (!matriculeNorm.isEmpty() && matriculeNorm.equals(normaliser(l.getPatientMatricule())))
                        || (!nomNorm.isEmpty() && nomNorm.equals(normaliser(l.getPatientNomPrenom())));
                if (!memePatient) continue;
                if (!medicamentNorm.equals(normaliser(l.getMedicament()))) continue;

                total += l.getQuantite();
                delivrances.add(ControleDelivranceDto.Delivrance.builder()
                        .pharmacieNom(f.getPharmacieNom())
                        .medicament(l.getMedicament())
                        .quantite(l.getQuantite())
                        .build());
            }
        }

        return ControleDelivranceDto.builder()
                .quantiteDejaDelivree(total)
                .delivrances(delivrances)
                .build();
    }

    /** Comparaison insensible à la casse et aux espaces de bord. */
    private String normaliser(String valeur) {
        return valeur == null ? "" : valeur.trim().toLowerCase();
    }

    public List<Facture> getRetards() {
        User user = getCurrentUser();
        if (user.getRole() != Role.PHARMACIEN) {
            return new ArrayList<>();
        }

        LocalDate now = LocalDate.now();
        return factureRepository.findRetards(user.getPharmacieId(), now.getMonthValue(), now.getYear());
    }

    @Transactional
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

        List<LigneFacture> nouvellesLignes = new ArrayList<>();
        for (LigneFactureDto ligneDto : lignesDto) {
            // Refuse les médicaments exclus et repère ceux ajoutés par le pharmacien
            // (non répertoriés) : ces derniers sont enregistrés pour validation SEN-CSU.
            boolean ajoute = resoudreMedicamentAjoute(ligneDto.getMedicament(), ligneDto.getCodeProduit());

            LigneFacture newLigne = new LigneFacture();
            newLigne.setPatientNomPrenom(ligneDto.getPatientNomPrenom());
            newLigne.setPatientMatricule(ligneDto.getPatientMatricule());
            newLigne.setMedicament(ligneDto.getMedicament());
            newLigne.setCodeProduit(ligneDto.getCodeProduit());
            newLigne.setQuantite(ligneDto.getQuantite());
            newLigne.setPrixUnitaire(ligneDto.getPrixUnitaire());
            newLigne.setMontant(ligneDto.getQuantite() * ligneDto.getPrixUnitaire());
            newLigne.setBonCommandeNumero(ligneDto.getBonCommandeNumero());
            newLigne.setTicketCaisse(ligneDto.getTicketCaisse());
            newLigne.setBonCommande(ligneDto.getBonCommande());
            newLigne.setOrdonnance(ligneDto.getOrdonnance());
            newLigne.setAjouteParPharmacien(ajoute);

            nouvellesLignes.add(newLigne);
            facture.getLignes().add(newLigne);
        }

        // Multi-pharmacies : valide les lignes disponibles et incrémente le compteur AVANT la sauvegarde.
        validerEtConsommerLignesBons(nouvellesLignes);

        double montantTotal = facture.getLignes().stream()
                .mapToDouble(LigneFacture::getMontant)
                .sum();
        facture.setMontantTotal(montantTotal);
        facture.setUpdatedAt(LocalDateTime.now());

        return factureRepository.save(facture);
    }

    @Transactional
    public Facture updateFacture(String id, FactureRequest request) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();

        if (facture.getStatut() != StatutFacture.BROUILLON && facture.getStatut() != StatutFacture.REJETEE_SR) {
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
        // Note : updateFacture remplace toutes les lignes. La validation incrémentale s'applique
        // uniquement lors de l'ajout de nouvelles lignes (addLignesToCurrent). Ici on sauvegarde directement.
        return factureRepository.save(facture);
    }

    @Transactional
    public Facture envoyerFacture(String id) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();

        if (user.getRole() != Role.PHARMACIEN) {
            throw new ForbiddenException("Seul un pharmacien peut envoyer une facture");
        }

        // Envoi initial (BROUILLON) ou renvoi après correction (REJETEE_SR).
        boolean correction = facture.getStatut() == StatutFacture.REJETEE_SR;
        if (facture.getStatut() != StatutFacture.BROUILLON && !correction) {
            throw new BusinessException("La facture n'est pas modifiable pour envoi");
        }

        if (correction) {
            facture.setCommentaireRejet(null);
        }

        changerStatut(facture, StatutFacture.ENVOYEE, user,
                correction ? "Renvoi après correction" : "Envoi au service régional");
        return facture;
    }

    /** Service Central : VALIDEE_NC -> PAYEE. */
    @Transactional
    public Facture payerFacture(String id) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();

        if (user.getRole() != Role.SERVICE_CENTRAL) {
            throw new ForbiddenException("Seul le niveau central peut payer une facture");
        }
        if (facture.getStatut() != StatutFacture.VALIDEE_NC) {
            throw new BusinessException("Seules les factures validées par le central peuvent être payées");
        }
        changerStatut(facture, StatutFacture.PAYEE, user, "Facture payée");
        return facture;
    }

    /** Service Régional : REJETEE_NC -> REJETEE_SR (relais du rejet central vers la pharmacie). */
    @Transactional
    public Facture renvoyerAPharmacie(String id) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();

        if (user.getRole() != Role.SERVICE_REGIONAL) {
            throw new ForbiddenException("Seul le service régional peut renvoyer une facture à la pharmacie");
        }
        if (facture.getStatut() != StatutFacture.REJETEE_NC) {
            throw new BusinessException("Seules les factures rejetées par le central peuvent être renvoyées à la pharmacie");
        }
        changerStatut(facture, StatutFacture.REJETEE_SR, user,
                "Rejet du niveau central renvoyé à la pharmacie pour correction");
        return facture;
    }

    /**
     * Service Régional : REJETEE_NC -> VALIDEE_SR (renvoi direct au central après son rejet).
     * Permet au service régional de retransmettre la facture au niveau central pour réexamen,
     * sans repasser par la pharmacie. Elle réapparaît alors dans les « Factures reçues » du central.
     */
    @Transactional
    public Facture renvoyerAuCentral(String id) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();

        if (user.getRole() != Role.SERVICE_REGIONAL) {
            throw new ForbiddenException("Seul le service régional peut renvoyer une facture au central");
        }
        if (facture.getStatut() != StatutFacture.REJETEE_NC) {
            throw new BusinessException("Seules les factures rejetées par le central peuvent être renvoyées au central");
        }
        facture.setCommentaireRejet(null);
        changerStatut(facture, StatutFacture.VALIDEE_SR, user,
                "Facture renvoyée au niveau central pour réexamen");
        return facture;
    }

    /**
     * Validation consciente du niveau :
     * - Service Régional : ENVOYEE -> VALIDEE_SR (et transmise au central)
     * - Service Central  : VALIDEE_SR -> VALIDEE_NC
     */
    @Transactional
    public Facture validerFacture(String id, ValidationRequest request) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();
        String commentaire = request != null ? request.getCommentaire() : null;
        boolean hasComment = commentaire != null && !commentaire.isBlank();

        if (user.getRole() == Role.SERVICE_REGIONAL) {
            if (facture.getStatut() != StatutFacture.ENVOYEE) {
                throw new BusinessException("Seules les factures reçues (envoyées) peuvent être validées");
            }
            changerStatut(facture, StatutFacture.VALIDEE_SR, user,
                    hasComment ? commentaire : "Validée et transmise au niveau central");
        } else if (user.getRole() == Role.SERVICE_CENTRAL) {
            if (facture.getStatut() != StatutFacture.VALIDEE_SR) {
                throw new BusinessException("Seules les factures transmises peuvent être validées par le central");
            }
            changerStatut(facture, StatutFacture.VALIDEE_NC, user,
                    hasComment ? commentaire : "Validée par le niveau central");
        } else {
            throw new ForbiddenException("Accès refusé");
        }
        return facture;
    }

    /**
     * Rejet conscient du niveau :
     * - Service Régional : ENVOYEE -> REJETEE_SR (renvoyée à la pharmacie)
     * - Service Central  : VALIDEE_SR -> REJETEE_NC (reçue par le SR)
     */
    @Transactional
    public Facture rejeterFacture(String id, ValidationRequest request) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();
        String motif = request != null ? request.getCommentaire() : null;
        if (motif == null || motif.isBlank()) {
            throw new BusinessException("Le motif de rejet est obligatoire");
        }

        if (user.getRole() == Role.SERVICE_REGIONAL) {
            if (facture.getStatut() != StatutFacture.ENVOYEE) {
                throw new BusinessException("Seules les factures reçues peuvent être rejetées");
            }
            facture.setCommentaireRejet(motif);
            changerStatut(facture, StatutFacture.REJETEE_SR, user, motif);
        } else if (user.getRole() == Role.SERVICE_CENTRAL) {
            if (facture.getStatut() != StatutFacture.VALIDEE_SR) {
                throw new BusinessException("Seules les factures transmises peuvent être rejetées par le central");
            }
            facture.setCommentaireRejet(motif);
            changerStatut(facture, StatutFacture.REJETEE_NC, user, motif);
        } else {
            throw new ForbiddenException("Accès refusé");
        }
        return facture;
    }

    /**
     * Décision ligne par ligne par le Service Régional sur une facture reçue (ENVOYEE).
     * - accepter == true  : la ligne passe ACCEPTEE (motif effacé).
     * - accepter == false : la ligne passe REJETEE (motif obligatoire).
     * Ne change pas le statut global de la facture : la validation/rejet d'ensemble reste
     * une action distincte (validerFacture / rejeterFacture).
     */
    @Transactional
    public Facture deciderLigne(String id, int ligneIndex, LigneDecisionRequest request) {
        Facture facture = getFactureById(id);
        User user = getCurrentUser();

        if (user.getRole() != Role.SERVICE_REGIONAL) {
            throw new ForbiddenException("Seul le service régional peut décider d'une ligne");
        }
        if (facture.getStatut() != StatutFacture.ENVOYEE) {
            throw new BusinessException("Seules les factures reçues (envoyées) permettent une décision ligne par ligne");
        }

        List<LigneFacture> lignes = facture.getLignes();
        if (lignes == null || ligneIndex < 0 || ligneIndex >= lignes.size()) {
            throw new ResourceNotFoundException("Ligne de facture introuvable");
        }

        LigneFacture ligne = lignes.get(ligneIndex);
        if (request.isAccepter()) {
            ligne.setStatutLigne(StatutLigne.ACCEPTEE);
            ligne.setMotifRejet(null);
        } else {
            String motif = request.getMotif();
            if (motif == null || motif.isBlank()) {
                throw new BusinessException("Le motif est obligatoire pour rejeter une ligne");
            }
            ligne.setStatutLigne(StatutLigne.REJETEE);
            ligne.setMotifRejet(motif.trim());
        }

        facture.setUpdatedAt(LocalDateTime.now());
        return factureRepository.save(facture);
    }

    @Transactional
    public void deleteFacture(String id) {
        User user = getCurrentUser();
        Facture facture = getFactureById(id);
        
        // Si l'utilisateur n'est pas ADMIN
        if (user.getRole() != Role.ADMIN) {
            // Seuls les pharmaciens peuvent supprimer leurs propres factures en brouillon
            if (user.getRole() == Role.PHARMACIEN) {
                if (!java.util.Objects.equals(facture.getPharmacieId(), user.getPharmacieId())) {
                    throw new ForbiddenException("Accès refusé");
                }
                if (facture.getStatut() != StatutFacture.BROUILLON) {
                    throw new BusinessException("Seules les factures en brouillon peuvent être supprimées");
                }
            } else {
                throw new ForbiddenException("Accès refusé");
            }
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
            boolean ajoute = resoudreMedicamentAjoute(dto.getMedicament(), dto.getCodeProduit());

            double montant = dto.getQuantite() * dto.getPrixUnitaire();
            return LigneFacture.builder()
                    .patientNomPrenom(dto.getPatientNomPrenom())
                    .patientMatricule(dto.getPatientMatricule())
                    .medicament(dto.getMedicament())
                    .codeProduit(dto.getCodeProduit())
                    .quantite(dto.getQuantite())
                    .prixUnitaire(dto.getPrixUnitaire())
                    .montant(montant)
                    .bonCommandeNumero(dto.getBonCommandeNumero())
                    .ticketCaisse(dto.getTicketCaisse())
                    .bonCommande(dto.getBonCommande())
                    .ordonnance(dto.getOrdonnance())
                    .ajouteParPharmacien(ajoute)
                    .build();
        }).collect(Collectors.toList());
    }

    /**
     * Résout le statut d'un médicament saisi sur une facture :
     * <ul>
     *   <li>EXCLU : lève une exception métier (non facturable) ;</li>
     *   <li>ÉLIGIBLE : ligne normale ;</li>
     *   <li>NON RÉPERTORIÉ ou inconnu : ligne « ajoutée par le pharmacien ».</li>
     * </ul>
     * Un médicament totalement inconnu est enregistré en statut {@code NON_REPERTORIE}
     * afin que la SEN-CSU (administration) puisse le valider ou l'exclure ensuite.
     * Idempotent : un médicament déjà présent (par nom) n'est jamais dupliqué.
     *
     * @return {@code true} si la ligne doit être marquée « ajoutée par le pharmacien ».
     */
    private boolean resoudreMedicamentAjoute(String nom, String code) {
        if (nom == null || nom.isBlank()) {
            return false;
        }
        // Le nom n'est pas unique en base (imports successifs) : on lit la liste complète
        // plutôt qu'un Optional, qui échouerait sur un doublon. L'exclusion prime, puis
        // l'éligibilité ; à défaut la ligne est marquée « ajoutée par le pharmacien ».
        List<Medicament> existants = medicamentRepository.findAllByNomIgnoreCase(nom.trim());
        if (!existants.isEmpty()) {
            if (existants.stream().anyMatch(m -> m.getStatut() == StatutMedicament.EXCLU)) {
                throw new BusinessException("Le médicament " + nom + " est exclu et ne peut pas être facturé.");
            }
            if (existants.stream().anyMatch(m -> m.getStatut() == StatutMedicament.ELIGIBLE)) {
                return false;
            }
            return existants.stream().anyMatch(m -> m.getStatut() == StatutMedicament.NON_REPERTORIE);
        }

        // Médicament inconnu : on l'enregistre en NON_REPERTORIE pour revue par la SEN-CSU.
        LocalDateTime now = LocalDateTime.now();
        String codeMed = (code != null && !code.isBlank())
                ? code.trim()
                : "NR-" + System.currentTimeMillis();
        // Un code déjà pris par un autre médicament : on en génère un dédié pour éviter le conflit.
        if (medicamentRepository.existsByCodeIgnoreCase(codeMed)) {
            codeMed = "NR-" + System.currentTimeMillis() + "-" + Math.abs(nom.trim().hashCode() % 100000);
        }
        Medicament nouveau = Medicament.builder()
                .code(codeMed)
                .nom(nom.trim())
                .statut(StatutMedicament.NON_REPERTORIE)
                .description("Ajouté par un pharmacien — à valider par la SEN-CSU")
                .actif(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
        medicamentRepository.save(nouveau);
        return true;
    }

    @Transactional
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

    /**
     * Réimporte une facture corrigée au format Excel détaillé (celui produit par
     * {@code ExportService.exportFactureExcel}) : remplace l'intégralité des lignes
     * de la facture par celles du fichier, puis recalcule le montant total.
     *
     * <p>Réservé au Service Régional / Central (et Admin) : typiquement après
     * correction hors-ligne des quantités/prix. L'export Excel ne contient ni les
     * pièces justificatives ni le code produit : on les restaure depuis les lignes
     * d'origine en rapprochant par (matricule + médicament).</p>
     */
    @Transactional
    public Facture importerFactureExcel(String id, InputStream is) {
        User user = getCurrentUser();
        if (user.getRole() != Role.SERVICE_REGIONAL
                && user.getRole() != Role.SERVICE_CENTRAL
                && user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Accès refusé pour l'importation");
        }

        Facture facture = getFactureById(id);
        if (facture.getStatut() == StatutFacture.PAYEE) {
            throw new BusinessException("Impossible de modifier une facture déjà payée");
        }

        // Index des lignes existantes pour préserver pièces justificatives et code produit.
        Map<String, LigneFacture> anciennesParCle = new HashMap<>();
        if (facture.getLignes() != null) {
            for (LigneFacture l : facture.getLignes()) {
                anciennesParCle.putIfAbsent(cleLigne(l.getPatientMatricule(), l.getMedicament()), l);
            }
        }

        List<LigneFacture> nouvellesLignes = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(is)) {
            Sheet sheet = workbook.getSheetAt(0);

            // Localise la ligne d'en-tête (colonne « Medicament ») pour rester robuste
            // aux variations de mise en page ; à défaut, on retombe sur l'index connu (8).
            int headerRowIdx = -1;
            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String c4 = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(4));
                // Comparaison insensible aux accents : l'en-tête exporté est « Médicament ».
                if (c4 != null && sansAccents(c4).toLowerCase().contains("medicament")) {
                    headerRowIdx = i;
                    break;
                }
            }
            // À défaut, on retombe sur l'index d'en-tête du format exporté (ligne 10).
            if (headerRowIdx < 0) headerRowIdx = 10;

            // Le nom/matricule patient n'est renseigné que sur la 1ʳᵉ ligne d'un patient :
            // on le reporte sur les lignes suivantes (cellules laissées vides à l'export).
            String currentNom = null;
            String currentMatricule = null;

            for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String col0 = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(0));
                if (col0 != null && col0.trim().equalsIgnoreCase("TOTAL")) break;

                final String medicament = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(4));
                if (medicament == null || medicament.trim().isEmpty()) continue;
                final String medNom = medicament.trim();

                // Colonnes du format exporté : col1 = N° Bon (matricule), col3 = Nom & Prénom.
                String nom = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(3));
                String matricule = com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(1));
                if (nom != null && !nom.trim().isEmpty()) currentNom = nom.trim();
                if (matricule != null && !matricule.trim().isEmpty()) currentMatricule = matricule.trim();

                double prixUnitaire = parseNombre(com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(5)));
                int quantite = (int) Math.round(parseNombre(com.csu.pharmacie.utils.ExcelUtils.getCellStringValue(row.getCell(6))));
                if (quantite <= 0) quantite = 1;

                LigneFacture ancienne = anciennesParCle.get(cleLigne(currentMatricule, medNom));
                String codeProduit = ancienne != null ? ancienne.getCodeProduit() : null;
                if (codeProduit == null) {
                    codeProduit = medicamentRepository.findByNomIgnoreCase(medNom)
                            .map(Medicament::getCode).orElse(null);
                }

                // Refuse les exclus et repère les médicaments non répertoriés (ajoutés par le pharmacien).
                boolean ajoute = resoudreMedicamentAjoute(medNom, codeProduit);

                LigneFacture.LigneFactureBuilder b = LigneFacture.builder()
                        .patientNomPrenom(currentNom)
                        .patientMatricule(currentMatricule)
                        .medicament(medNom)
                        .codeProduit(codeProduit)
                        .quantite(quantite)
                        .prixUnitaire(prixUnitaire)
                        .montant(quantite * prixUnitaire)
                        .ajouteParPharmacien(ajoute);

                // Conserve les pièces justificatives de la ligne d'origine si retrouvée.
                if (ancienne != null) {
                    b.ticketCaisse(ancienne.getTicketCaisse())
                     .bonCommande(ancienne.getBonCommande())
                     .ordonnance(ancienne.getOrdonnance());
                }

                nouvellesLignes.add(b.build());
            }
        } catch (BusinessException | ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Erreur lors de l'importation du fichier Excel", e);
        }

        if (nouvellesLignes.isEmpty()) {
            throw new BusinessException("Aucune ligne valide trouvée dans le fichier importé");
        }

        double montantTotal = nouvellesLignes.stream().mapToDouble(LigneFacture::getMontant).sum();
        facture.setLignes(nouvellesLignes);
        facture.setMontantTotal(montantTotal);
        facture.setUpdatedAt(LocalDateTime.now());
        ajouterHistorique(facture, user, facture.getStatut(),
                "Lignes corrigées via import Excel (" + user.getRole() + ")");
        return factureRepository.save(facture);
    }

    /** Clé de rapprochement d'une ligne entre l'ancienne et la nouvelle version. */
    private String cleLigne(String matricule, String medicament) {
        return (matricule == null ? "" : matricule.trim().toLowerCase())
                + "|" + (medicament == null ? "" : medicament.trim().toLowerCase());
    }

    /** Parse une cellule numérique tolérante (espaces, virgule décimale) ; 0 si invalide. */
    private double parseNombre(String s) {
        if (s == null) return 0;
        try {
            return Double.parseDouble(s.trim().replace(" ", "").replace(",", "."));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Supprime les accents/diacritiques pour des comparaisons robustes (« Médicament » → « Medicament »). */
    private static String sansAccents(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}
