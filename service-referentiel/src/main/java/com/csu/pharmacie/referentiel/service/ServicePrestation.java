package com.csu.pharmacie.referentiel.service;

import com.csu.pharmacie.entity.Prestation;
import com.csu.pharmacie.entity.ServiceMedical;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.csu.pharmacie.commun.client.dto.PrestationDto;
import com.csu.pharmacie.commun.export.LecteurExcel;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.referentiel.depot.DepotPrestation;
import com.csu.pharmacie.referentiel.depot.DepotServiceMedical;
import com.csu.pharmacie.referentiel.dto.ResultatImportReferentiel;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Référentiel Service → Prestation → Prix (§5).
 *
 * <p>Deux points méritent l'attention :</p>
 * <ul>
 *   <li>on ne <em>supprime</em> jamais une prestation déjà facturée — on la désactive.
 *       Une suppression rendrait illisibles toutes les factures qui la citent ;</li>
 *   <li>une prestation dépositaire sans article de stock associé est refusée : elle ne
 *       pourrait pas déclencher la sortie de stock que le §14 exige.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ServicePrestation {

    private final DepotPrestation depotPrestation;
    private final DepotServiceMedical depotService;

    // ────────────────────────────── lecture ──────────────────────────────

    public Page<Prestation> rechercher(String serviceId, String recherche, boolean actifSeulement,
                                       int page, int taille) {
        String requete = recherche == null ? "" : recherche.trim().toLowerCase(Locale.ROOT);
        String service = serviceId == null || serviceId.isBlank() ? null : serviceId;
        return depotPrestation.rechercher(service, requete, actifSeulement,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, taille), 200),
                        Sort.by("designation")));
    }

    /**
     * Référentiel complet et actif, destiné à la mise en cache hors ligne du dashboard
     * (§12 : « mise en cache des services, prestations et prix »).
     */
    public List<PrestationDto> referentielComplet() {
        Map<String, String> nomsServices = nomsServices();
        return depotPrestation.findByActifTrue().stream()
                .map(p -> versDto(p, nomsServices.get(p.getServiceId())))
                .toList();
    }

    public List<Prestation> parService(String serviceId) {
        return depotPrestation.findByServiceIdAndActifTrue(serviceId);
    }

    public Prestation parId(String id) {
        return depotPrestation.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Prestation introuvable : " + id));
    }

    /** Tarification en lot — le point d'entrée qu'appellent les services de facturation. */
    public List<PrestationDto> tarifs(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<String, String> nomsServices = nomsServices();
        return depotPrestation.findByIdIn(ids).stream()
                .map(p -> versDto(p, nomsServices.get(p.getServiceId())))
                .toList();
    }

    // ────────────────────────────── écriture ──────────────────────────────

    @Transactional
    public Prestation creer(Prestation prestation) {
        normaliser(prestation);
        if (prestation.getCode() == null || prestation.getCode().isBlank()) {
            throw ExceptionMetier.invalide("Le code de la prestation est obligatoire");
        }
        if (depotPrestation.existsByCodeIgnoreCase(prestation.getCode())) {
            throw ExceptionMetier.conflit("Une prestation porte déjà le code « " + prestation.getCode() + " »");
        }
        verifier(prestation);
        prestation.setId(null);
        return depotPrestation.save(prestation);
    }

    @Transactional
    public Prestation modifier(String id, Prestation modifications) {
        Prestation prestation = parId(id);
        normaliser(modifications);
        if (modifications.getCode() != null && !modifications.getCode().equalsIgnoreCase(prestation.getCode())) {
            if (depotPrestation.existsByCodeIgnoreCase(modifications.getCode())) {
                throw ExceptionMetier.conflit(
                        "Une prestation porte déjà le code « " + modifications.getCode() + " »");
            }
            prestation.setCode(modifications.getCode());
        }
        if (modifications.getDesignation() != null) {
            prestation.setDesignation(modifications.getDesignation());
        }
        prestation.setServiceId(modifications.getServiceId());
        prestation.setPrix(modifications.getPrix());
        prestation.setDepositaire(modifications.isDepositaire());
        prestation.setMedicamentId(modifications.getMedicamentId());
        verifier(prestation);
        return depotPrestation.save(prestation);
    }

    @Transactional
    public Prestation basculerActivation(String id, boolean actif) {
        Prestation prestation = parId(id);
        prestation.setActif(actif);
        return depotPrestation.save(prestation);
    }

    /**
     * Suppression définitive — réservée aux prestations créées par erreur et jamais
     * utilisées. Le §5 la demande explicitement ; la désactivation reste la voie normale.
     */
    @Transactional
    public void supprimer(String id) {
        depotPrestation.delete(parId(id));
    }

    // ────────────────────────────── import Excel ──────────────────────────────

    /**
     * Import du référentiel (§5). Le service est retrouvé par son code <em>ou</em> par
     * son nom, et créé au vol s'il n'existe pas : les fichiers reçus listent le service
     * en toutes lettres (« Maternité »), rarement par code.
     */
    @Transactional
    public ResultatImportReferentiel importer(MultipartFile fichier) {
        ResultatImportReferentiel resultat = new ResultatImportReferentiel();
        List<LecteurExcel.LigneImport> lignes;
        try {
            lignes = LecteurExcel.lire(fichier.getInputStream());
        } catch (IOException e) {
            throw ExceptionMetier.invalide("Lecture du fichier impossible : " + e.getMessage());
        }
        resultat.setLignesLues(lignes.size());

        Map<String, String> servicesConnus = new HashMap<>();
        for (ServiceMedical service : depotService.findAll()) {
            servicesConnus.put(cle(service.getCode()), service.getId());
            servicesConnus.put(cle(service.getNom()), service.getId());
        }

        for (LecteurExcel.LigneImport ligne : lignes) {
            try {
                String designation = ligne.texte("designation", "prestation", "libelle", "acte", "nom");
                if (designation == null) {
                    resultat.erreur(ligne.numeroLigne(), "colonne « Désignation » (ou « Prestation ») obligatoire");
                    continue;
                }
                String code = ligne.texte("code", "codeacte", "codeprestation");
                if (code == null) {
                    // Sans code fourni, on en dérive un stable depuis la désignation :
                    // réimporter le même fichier met alors à jour au lieu de dupliquer.
                    code = codeDepuisDesignation(designation);
                }

                String libelleService = ligne.texte("service", "servicemedical", "categorie");
                String serviceId = null;
                if (libelleService != null) {
                    serviceId = servicesConnus.get(cle(libelleService));
                    if (serviceId == null) {
                        ServiceMedical cree = depotService.save(ServiceMedical.builder()
                                .code(codeDepuisDesignation(libelleService))
                                .nom(libelleService)
                                .actif(true)
                                .build());
                        serviceId = cree.getId();
                        servicesConnus.put(cle(cree.getCode()), serviceId);
                        servicesConnus.put(cle(cree.getNom()), serviceId);
                        resultat.setServicesCrees(resultat.getServicesCrees() + 1);
                    }
                }

                Optional<Prestation> existante = depotPrestation.findByCodeIgnoreCase(code);
                Prestation prestation = existante.orElseGet(Prestation::new);
                prestation.setCode(code.trim().toUpperCase(Locale.ROOT));
                prestation.setDesignation(designation.trim());
                prestation.setServiceId(serviceId);
                prestation.setPrix(ligne.nombre("prix", "prixunitaire", "tarif", "montant"));
                prestation.setDepositaire(ligne.booleen("depositaire", "pharmacie", "stock"));
                prestation.setMedicamentId(ligne.texte("medicament", "codemedicament", "article"));
                prestation.setActif(true);

                if (prestation.isDepositaire() && (prestation.getMedicamentId() == null
                        || prestation.getMedicamentId().isBlank())) {
                    resultat.erreur(ligne.numeroLigne(), "prestation dépositaire sans code médicament : "
                            + "la sortie de stock ne pourrait pas être déclenchée");
                    continue;
                }

                depotPrestation.save(prestation);
                if (existante.isPresent()) {
                    resultat.setMisAJour(resultat.getMisAJour() + 1);
                } else {
                    resultat.setCrees(resultat.getCrees() + 1);
                }
            } catch (ExceptionMetier e) {
                resultat.erreur(ligne.numeroLigne(), e.getMessage());
            }
        }
        return resultat;
    }

    // ────────────────────────────── utilitaires ──────────────────────────────

    public PrestationDto versDto(Prestation prestation, String nomService) {
        return PrestationDto.builder()
                .id(prestation.getId())
                .code(prestation.getCode())
                .designation(prestation.getDesignation())
                .serviceId(prestation.getServiceId())
                .serviceNom(nomService)
                .prix(prestation.getPrix())
                .depositaire(prestation.isDepositaire())
                .medicamentId(prestation.getMedicamentId())
                .actif(prestation.isActif())
                .build();
    }

    public Map<String, String> nomsServices() {
        Map<String, String> noms = new HashMap<>();
        depotService.findAll().forEach(s -> noms.put(s.getId(), s.getNom()));
        return noms;
    }

    private void normaliser(Prestation prestation) {
        if (prestation.getCode() != null) {
            prestation.setCode(prestation.getCode().trim().toUpperCase(Locale.ROOT));
        }
        if (prestation.getDesignation() != null) {
            prestation.setDesignation(prestation.getDesignation().trim());
        }
        if (prestation.getServiceId() != null && prestation.getServiceId().isBlank()) {
            prestation.setServiceId(null);
        }
    }

    private void verifier(Prestation prestation) {
        if (prestation.getPrix() < 0) {
            throw ExceptionMetier.invalide("Le prix ne peut pas être négatif");
        }
        if (prestation.getServiceId() != null && depotService.findById(prestation.getServiceId()).isEmpty()) {
            throw ExceptionMetier.invalide("Service introuvable");
        }
        if (prestation.isDepositaire()
                && (prestation.getMedicamentId() == null || prestation.getMedicamentId().isBlank())) {
            throw ExceptionMetier.invalide("Une prestation dépositaire doit référencer un article de stock : "
                    + "c'est lui qui sera décrémenté à la facturation.");
        }
    }

    private static String cle(String valeur) {
        return valeur == null ? "" : valeur.trim().toLowerCase(Locale.ROOT);
    }

    private static String codeDepuisDesignation(String designation) {
        String base = java.text.Normalizer.normalize(designation, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
        return base.length() > 40 ? base.substring(0, 40) : base;
    }
}
