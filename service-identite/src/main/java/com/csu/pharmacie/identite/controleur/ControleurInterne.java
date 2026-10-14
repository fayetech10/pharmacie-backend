package com.csu.pharmacie.identite.controleur;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.csu.pharmacie.commun.client.dto.DistrictDto;
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.identite.depot.DepotDistrict;
import com.csu.pharmacie.identite.depot.DepotStructure;
import com.csu.pharmacie.identite.service.ServiceDistrict;
import com.csu.pharmacie.identite.service.ServiceStructure;

import java.util.List;

/**
 * API consommée par les autres microservices (jamais exposée par la passerelle).
 *
 * <p>Elle reste soumise à l'authentification : l'appelant rejoue le jeton de
 * l'utilisateur d'origine. La différence avec l'API publique est le contrat, pas le
 * niveau de confiance — ces réponses sont des DTO stables, pas les entités internes.</p>
 */
@RestController
@RequestMapping("/interne")
@RequiredArgsConstructor
public class ControleurInterne {

    private final ServiceStructure serviceStructure;
    private final ServiceDistrict serviceDistrict;
    private final DepotStructure depotStructure;
    private final DepotDistrict depotDistrict;

    @GetMapping("/structures/{id}")
    public StructureDto structure(@PathVariable String id) {
        return serviceStructure.versDto(serviceStructure.parId(id));
    }

    /**
     * Recherche de structures par rattachement, district ou région.
     * Ce point d'entrée ne filtre pas selon l'utilisateur courant : le service appelant
     * connaît son propre périmètre et l'a déjà validé (un centre ne demande que ses postes).
     */
    @GetMapping("/structures")
    public List<StructureDto> structures(@RequestParam(required = false) String rattachementId,
                                         @RequestParam(required = false) String districtId,
                                         @RequestParam(required = false) String regionId) {
        List<StructureSanitaire> structures;
        if (rattachementId != null && !rattachementId.isBlank()) {
            structures = depotStructure.findByStructureRattachementId(rattachementId);
        } else if (districtId != null && !districtId.isBlank()) {
            structures = depotStructure.findByDistrictId(districtId);
        } else if (regionId != null && !regionId.isBlank()) {
            structures = depotStructure.findByRegionId(regionId);
        } else {
            throw ExceptionMetier.invalide(
                    "Préciser au moins un critère : rattachementId, districtId ou regionId");
        }
        return structures.stream().map(serviceStructure::versDto).toList();
    }

    @GetMapping("/districts/{id}")
    public DistrictDto district(@PathVariable String id) {
        return serviceDistrict.versDto(serviceDistrict.parId(id));
    }

    /**
     * Districts d'une région (activités du réseau du service régional). Comme pour les
     * structures, aucun filtrage selon l'appelant : le service appelant a déjà imposé la région.
     */
    @GetMapping("/districts")
    public List<DistrictDto> districts(@RequestParam String regionId) {
        if (regionId == null || regionId.isBlank()) {
            throw ExceptionMetier.invalide("Préciser la région : regionId");
        }
        return depotDistrict.findByRegionId(regionId).stream().map(serviceDistrict::versDto).toList();
    }
}
