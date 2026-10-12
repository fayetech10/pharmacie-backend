package com.csu.pharmacie.identite.controleur;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.identite.dto.ResultatImport;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.identite.service.ServiceUtilisateur;

import java.util.List;
import java.util.Map;

/** Administration des comptes. Réservé aux rôles ADMIN et SERVICE_REGIONAL (voir la sécurité). */
@RestController
@RequestMapping("/api/utilisateurs")
@RequiredArgsConstructor
public class ControleurUtilisateur {

    private final ServiceUtilisateur serviceUtilisateur;

    @GetMapping
    public List<User> lister(@RequestParam(required = false) Role role,
                                    @RequestParam(required = false) String structureId,
                                    @RequestParam(required = false) String districtId) {
        return serviceUtilisateur.lister(role, structureId, districtId);
    }

    @GetMapping("/{id}")
    public User detail(@PathVariable String id) {
        return serviceUtilisateur.parId(id);
    }

    /**
     * Crée un compte. Le mot de passe temporaire n'apparaît que dans cette réponse :
     * il n'est stocké qu'en clair nulle part et ne pourra pas être relu ensuite.
     */
    @PostMapping
    public Map<String, Object> creer(@RequestBody User demande) {
        ServiceUtilisateur.CompteCree cree = serviceUtilisateur.creer(demande);
        return Map.of(
                "utilisateur", cree.utilisateur(),
                "motDePasseTemporaire", cree.motDePasseTemporaire() == null ? "" : cree.motDePasseTemporaire());
    }

    @PutMapping("/{id}")
    public User modifier(@PathVariable String id, @RequestBody User modifications) {
        return serviceUtilisateur.modifier(id, modifications);
    }

    @PutMapping("/{id}/activation")
    public User activation(@PathVariable String id, @RequestParam boolean actif) {
        return serviceUtilisateur.basculerActivation(id, actif);
    }

    @PostMapping("/{id}/reinitialiser-mot-de-passe")
    public Map<String, String> reinitialiser(@PathVariable String id) {
        return Map.of("motDePasseTemporaire", serviceUtilisateur.reinitialiserMotDePasse(id));
    }

    /** Crée en une fois les comptes des structures qui n'en ont pas (§6). */
    @PostMapping("/comptes-structures")
    public ResultatImport creerComptesStructures(@RequestParam(required = false) String districtId) {
        return serviceUtilisateur.creerComptesStructures(districtId);
    }

    /** Désactivation « douce » : le compte est conservé pour la traçabilité des factures. */
    @DeleteMapping("/{id}")
    public User desactiver(@PathVariable String id) {
        return serviceUtilisateur.basculerActivation(id, false);
    }
}
