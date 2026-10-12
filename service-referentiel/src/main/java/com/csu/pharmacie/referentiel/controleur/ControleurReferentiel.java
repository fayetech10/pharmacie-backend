package com.csu.pharmacie.referentiel.controleur;

import com.csu.pharmacie.entity.Prestation;
import com.csu.pharmacie.entity.ServiceMedical;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.csu.pharmacie.commun.client.dto.PrestationDto;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.referentiel.depot.DepotServiceMedical;
import com.csu.pharmacie.referentiel.dto.ResultatImportReferentiel;
import com.csu.pharmacie.referentiel.service.ServicePrestation;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Référentiel Service → Prestation → Prix (§5). */
@RestController
@RequestMapping("/api/referentiel")
@RequiredArgsConstructor
public class ControleurReferentiel {

    private final ServicePrestation servicePrestation;
    private final DepotServiceMedical depotService;

    // ────────────────────────────── services ──────────────────────────────

    @GetMapping("/services")
    public List<ServiceMedical> services(@RequestParam(defaultValue = "true") boolean actifSeulement) {
        List<ServiceMedical> services = actifSeulement ? depotService.findByActifTrue() : depotService.findAll();
        return services.stream()
                .sorted(Comparator.comparingInt(ServiceMedical::getOrdre)
                        .thenComparing(s -> s.getNom() == null ? "" : s.getNom()))
                .toList();
    }

    @PostMapping("/services")
    public ServiceMedical creerService(@RequestBody ServiceMedical service) {
        if (service.getCode() == null || service.getCode().isBlank()) {
            throw ExceptionMetier.invalide("Le code du service est obligatoire");
        }
        if (depotService.existsByCodeIgnoreCase(service.getCode())) {
            throw ExceptionMetier.conflit("Un service porte déjà le code « " + service.getCode() + " »");
        }
        service.setId(null);
        return depotService.save(service);
    }

    @PutMapping("/services/{id}")
    public ServiceMedical modifierService(@PathVariable String id, @RequestBody ServiceMedical modifications) {
        ServiceMedical service = depotService.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("Service introuvable : " + id));
        if (modifications.getNom() != null) {
            service.setNom(modifications.getNom());
        }
        service.setOrdre(modifications.getOrdre());
        service.setActif(modifications.isActif());
        return depotService.save(service);
    }

    // ────────────────────────────── prestations ──────────────────────────────

    /** Recherche paginée, filtrable par service (§5). */
    @GetMapping("/prestations")
    public Page<Prestation> prestations(@RequestParam(required = false) String serviceId,
                                        @RequestParam(required = false) String recherche,
                                        @RequestParam(defaultValue = "false") boolean actifSeulement,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int taille) {
        return servicePrestation.rechercher(serviceId, recherche, actifSeulement, page, taille);
    }

    /**
     * Référentiel complet et actif, en un seul appel : c'est ce que le dashboard met en
     * cache pour permettre la saisie hors ligne (§12).
     */
    @GetMapping("/prestations/cache")
    public List<PrestationDto> cache() {
        return servicePrestation.referentielComplet();
    }

    @GetMapping("/prestations/{id}")
    public Prestation detail(@PathVariable String id) {
        return servicePrestation.parId(id);
    }

    @PostMapping("/prestations")
    public Prestation creer(@RequestBody Prestation prestation) {
        return servicePrestation.creer(prestation);
    }

    @PutMapping("/prestations/{id}")
    public Prestation modifier(@PathVariable String id, @RequestBody Prestation modifications) {
        return servicePrestation.modifier(id, modifications);
    }

    @PutMapping("/prestations/{id}/activation")
    public Prestation activation(@PathVariable String id, @RequestParam boolean actif) {
        return servicePrestation.basculerActivation(id, actif);
    }

    @DeleteMapping("/prestations/{id}")
    public Map<String, String> supprimer(@PathVariable String id) {
        servicePrestation.supprimer(id);
        return Map.of("message", "Prestation supprimée");
    }

    @PostMapping("/prestations/import")
    public ResultatImportReferentiel importer(@RequestPart("fichier") MultipartFile fichier) {
        return servicePrestation.importer(fichier);
    }
}
