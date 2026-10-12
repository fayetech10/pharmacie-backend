package com.csu.pharmacie.identite.controleur;

import com.csu.pharmacie.entity.District;

import lombok.RequiredArgsConstructor;
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
import com.csu.pharmacie.commun.client.dto.DistrictDto;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.identite.dto.ResultatImport;
import com.csu.pharmacie.identite.service.ServiceDistrict;
import com.csu.pharmacie.identite.service.ServiceStructure;
import com.csu.pharmacie.identite.service.ServiceUtilisateur;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Districts sanitaires — l'échelon de consolidation (§9). */
@RestController
@RequestMapping("/api/districts")
@RequiredArgsConstructor
public class ControleurDistrict {

    private final ServiceDistrict serviceDistrict;
    private final ServiceStructure serviceStructure;

    @GetMapping
    public List<DistrictDto> lister(@RequestParam(required = false) String regionId) {
        return serviceDistrict.lister(regionId).stream()
                .map(serviceDistrict::versDto)
                .toList();
    }

    @GetMapping("/{id}")
    public DistrictDto detail(@PathVariable String id) {
        return serviceDistrict.versDto(serviceDistrict.parId(id));
    }

    /** Structures d'un district : la liste que la consolidation doit couvrir. */
    @GetMapping("/{id}/structures")
    public List<StructureDto> structures(@PathVariable String id) {
        return serviceStructure.lister(id, null, null, null).stream()
                .map(serviceStructure::versDto)
                .toList();
    }

    /**
     * Crée le district ET son compte de connexion (rôle DISTRICT). Les accès sont renvoyés une
     * seule fois, pour être communiqués au district : {@code identifiant} + {@code motDePasseTemporaire}.
     */
    @PostMapping
    public Map<String, Object> creer(@RequestBody District district,
                                     @RequestParam(defaultValue = "false") boolean avecCentre,
                                     @RequestParam(required = false) String nomCentre) {
        // avecCentre : le district est AUSSI un centre de santé — créé en même temps, siège du district.
        ServiceDistrict.DistrictCree cree = serviceDistrict.creer(district, avecCentre, nomCentre);
        Map<String, Object> reponse = new LinkedHashMap<>();
        reponse.put("district", cree.district());
        if (cree.centre() != null) {
            reponse.put("centre", Map.of("id", cree.centre().getId(),
                    "code", cree.centre().getCode(), "nom", cree.centre().getNom()));
        }
        if (cree.acces() != null) {
            reponse.put("identifiant", cree.acces().identifiant());
            reponse.put("motDePasseTemporaire", cree.acces().motDePasseTemporaire());
            reponse.put("compteCree", cree.acces().compteCree());
        }
        return reponse;
    }

    /** Accès du district : mot de passe remis au défaut, ou compte créé s'il n'en a pas encore. */
    @PostMapping("/{id}/reinitialiser-mot-de-passe")
    public Map<String, Object> reinitialiserMotDePasse(@PathVariable String id) {
        ServiceUtilisateur.AccesCompte acces = serviceDistrict.reinitialiserAcces(id);
        return Map.of(
                "identifiant", acces.identifiant(),
                "motDePasseTemporaire", acces.motDePasseTemporaire(),
                "compteCree", acces.compteCree());
    }

    @PutMapping("/{id}")
    public District modifier(@PathVariable String id, @RequestBody District modifications) {
        return serviceDistrict.modifier(id, modifications);
    }

    @PutMapping("/{id}/activation")
    public District activation(@PathVariable String id, @RequestParam boolean actif) {
        return serviceDistrict.basculerActivation(id, actif);
    }

    @PostMapping("/import")
    public ResultatImport importer(@RequestPart("fichier") MultipartFile fichier) {
        return serviceDistrict.importer(fichier);
    }
}
