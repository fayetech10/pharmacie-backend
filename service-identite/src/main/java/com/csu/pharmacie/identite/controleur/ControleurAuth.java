package com.csu.pharmacie.identite.controleur;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.csu.pharmacie.identite.dto.DtosAuth;
import com.csu.pharmacie.identite.service.ServiceAuthentification;

import java.util.Map;

/** Authentification (§1). Seule {@code /login} est publique. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class ControleurAuth {

    private final ServiceAuthentification serviceAuthentification;

    @PostMapping("/login")
    public DtosAuth.ReponseConnexion connexion(@Valid @RequestBody DtosAuth.RequeteConnexion requete) {
        return serviceAuthentification.connecter(requete);
    }

    /** Rafraîchit le contexte (utile après un changement de rattachement côté administration). */
    @GetMapping("/moi")
    public DtosAuth.ReponseConnexion moi() {
        return serviceAuthentification.moi();
    }

    /**
     * Deux chemins pour un seul traitement. « /change-password » est celui du cœur,
     * et c'est celui que le tableau de bord appelle depuis toujours : le supprimer en
     * extrayant le service a enfermé les comptes neufs dans l'écran « choisissez votre
     * mot de passe » — la requête partait, échouait, et {@code mustChangePassword}
     * restait vrai indéfiniment.
     *
     * <p>L'alias tient la promesse de l'architecture : le dashboard appelle les mêmes
     * URLs qu'avant. Il pourra disparaître le jour où le frontend visera le nom français.</p>
     */
    @PostMapping({"/changer-mot-de-passe", "/change-password"})
    public ResponseEntity<Map<String, String>> changerMotDePasse(
            @Valid @RequestBody DtosAuth.RequeteChangementMotDePasse requete) {
        serviceAuthentification.changerMotDePasse(requete);
        return ResponseEntity.ok(Map.of("message", "Mot de passe modifié"));
    }
}
