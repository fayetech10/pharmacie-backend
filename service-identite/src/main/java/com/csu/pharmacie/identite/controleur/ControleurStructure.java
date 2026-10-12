package com.csu.pharmacie.identite.controleur;

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
import com.csu.pharmacie.commun.client.dto.StructureDto;
import com.csu.pharmacie.entity.TypeStructure;
import com.csu.pharmacie.identite.dto.RequeteStructure;
import com.csu.pharmacie.identite.dto.ResultatImport;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.identite.service.ServiceStructure;
import com.csu.pharmacie.identite.service.ServiceUtilisateur;

import java.util.List;
import java.util.Map;

/**
 * Structures sanitaires (§6) : rattachement aux districts et aux services régionaux,
 * activation, création individuelle ou en masse.
 */
@RestController
@RequestMapping("/api/structures-sanitaires")
@RequiredArgsConstructor
public class ControleurStructure {

    private final ServiceStructure serviceStructure;

    @GetMapping
    public List<StructureSanitaire> lister(@RequestParam(required = false) String districtId,
                                           @RequestParam(required = false) String regionId,
                                           @RequestParam(required = false) TypeStructure type,
                                           @RequestParam(required = false) String recherche) {
        return serviceStructure.lister(districtId, regionId, type, recherche);
    }

    /** Version enrichie (libellés district/région/rattachement) pour les écrans de liste. */
    @GetMapping("/details")
    public List<StructureDto> listerDetaille(@RequestParam(required = false) String districtId,
                                             @RequestParam(required = false) String regionId,
                                             @RequestParam(required = false) TypeStructure type,
                                             @RequestParam(required = false) String recherche) {
        return serviceStructure.lister(districtId, regionId, type, recherche).stream()
                .map(serviceStructure::versDto)
                .toList();
    }

    /**
     * Structures rattachées à un agent BCSU — permet au frontend de résoudre le nom de
     * la structure d'un agent à partir de son userId, pour tout compte authentifié.
     *
     * <p>Déclaré ici et pas seulement dans le cœur : la passerelle route l'intégralité de
     * {@code /api/structures-sanitaires/**} vers ce service, si bien que la version du
     * cœur n'était jamais atteinte et que l'appel remontait en 500.</p>
     */
    @GetMapping("/by-bcsu/{bcsuId}")
    public List<StructureSanitaire> parBcsu(@PathVariable String bcsuId) {
        return serviceStructure.parBcsu(bcsuId);
    }

    @GetMapping("/{id}")
    public StructureDto detail(@PathVariable String id) {
        return serviceStructure.versDto(serviceStructure.parId(id));
    }

    /** Postes de santé polarisés par un centre — alimente l'écran « Factures des postes ». */
    @GetMapping("/{id}/postes")
    public List<StructureDto> postes(@PathVariable String id) {
        return serviceStructure.postesRattaches(id).stream()
                .map(serviceStructure::versDto)
                .toList();
    }

    @PostMapping
    public StructureSanitaire creer(@RequestBody RequeteStructure requete) {
        return serviceStructure.creer(requete.getStructure(), requete.getPassword());
    }

    @PutMapping("/{id}")
    public StructureSanitaire modifier(@PathVariable String id, @RequestBody StructureSanitaire modifications) {
        return serviceStructure.modifier(id, modifications);
    }

    /** Active / désactive la structure — et ses comptes de connexion avec elle. */
    @PutMapping("/{id}/activation")
    public StructureSanitaire activation(@PathVariable String id, @RequestParam boolean actif) {
        return serviceStructure.basculerActivation(id, actif);
    }

    /**
     * Réinitialise le mot de passe du compte de connexion de la structure, et crée ce compte
     * s'il manque. Le mot de passe temporaire n'apparaît que dans cette réponse.
     */
    @PostMapping("/{id}/reinitialiser-mot-de-passe")
    public Map<String, Object> reinitialiserMotDePasse(@PathVariable String id) {
        ServiceUtilisateur.AccesCompte acces = serviceStructure.reinitialiserMotDePasseCompte(id);
        return Map.of(
                "identifiant", acces.identifiant(),
                "motDePasseTemporaire", acces.motDePasseTemporaire(),
                "compteCree", acces.compteCree());
    }


    @PostMapping("/import")
    public ResultatImport importer(@RequestPart("fichier") MultipartFile fichier) {
        return serviceStructure.importer(fichier);
    }
}
