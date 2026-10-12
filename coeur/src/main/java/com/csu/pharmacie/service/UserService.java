package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.UserRequest;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.RegionRepository;
import com.csu.pharmacie.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RegionRepository regionRepository;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    /** Paramétrage (cachet + signature) d'un agent, pour affichage sur les lettres de garantie. */
    public Map<String, String> getParametrage(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
        Map<String, String> result = new HashMap<>();
        result.put("cachetImage", user.getCachetImage());
        result.put("signatureImage", user.getSignatureImage());
        return result;
    }

    /** Met à jour le cachet et la signature de l'utilisateur courant (paramétrage BCSU). */
    public Map<String, String> updateParametrage(Map<String, String> request) {
        User current = getCurrentUser();
        if (request.containsKey("cachetImage")) {
            current.setCachetImage(request.get("cachetImage"));
        }
        if (request.containsKey("signatureImage")) {
            current.setSignatureImage(request.get("signatureImage"));
        }
        current.setUpdatedAt(LocalDateTime.now());
        userRepository.save(current);
        return getParametrage(current.getId());
    }

    public List<User> getAllUsers() {
        User current = getCurrentUser();
        if (current.getRole() == Role.SERVICE_REGIONAL) {
            return userRepository.findByRoleAndRegionId(Role.BCSU, current.getRegionId());
        }
        return userRepository.findAll();
    }

    public User getUserById(String id) {
        User current = getCurrentUser();
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
        if (current.getRole() == Role.SERVICE_REGIONAL) {
            if (!current.getRegionId().equals(user.getRegionId()) || user.getRole() != Role.BCSU) {
                throw new com.csu.pharmacie.exception.ForbiddenException("Accès refusé");
            }
        }
        return user;
    }

    /** Agents BCSU visibles : tous pour l'Admin, ceux de sa région pour le Service Régional. */
    public List<User> getBcsuForCurrentUser() {
        User current = getCurrentUser();
        if (current.getRole() == Role.SERVICE_REGIONAL) {
            return userRepository.findByRoleAndRegionId(Role.BCSU, current.getRegionId());
        }
        return userRepository.findByRole(Role.BCSU);
    }

    public CompteCree createUser(UserRequest request) {
        User current = getCurrentUser();
        if (current.getRole() == Role.SERVICE_REGIONAL) {
            if (request.getRole() != Role.BCSU) {
                throw new com.csu.pharmacie.exception.ForbiddenException("Vous ne pouvez créer que des agents BCSU");
            }
            request.setRegionId(current.getRegionId());
        }

        // Email FACULTATIF : les comptes se connectent avec un identifiant simple généré
        // ci-dessous. S'il est fourni, il doit rester unique.
        String email = request.getEmail() == null ? null : request.getEmail().trim();
        if (email != null && !email.isBlank() && userRepository.findByEmail(email).isPresent()) {
            throw new ConflictException("Email déjà utilisé");
        }

        // Mot de passe FACULTATIF : à défaut, le mot de passe par défaut (password123), renvoyé
        // à l'administrateur pour qu'il le communique. Dans tous les cas
        // mustChangePassword impose son changement à la première connexion.
        boolean motDePasseGenere = request.getPassword() == null || request.getPassword().isBlank();
        String motDePasseClair = motDePasseGenere ? PharmacieService.MOT_DE_PASSE_GENERIQUE : request.getPassword();

        validerIdentite(request);

        // Identifiant de connexion simple (prenom.nom), généré et unique : l'utilisateur se
        // connecte avec, sans email. À défaut d'email, il sert aussi de valeur au champ email,
        // qui ne doit jamais être nul (il porte le sujet du jeton).
        String username = genererUsername(request);
        String emailCompte = (email == null || email.isBlank()) ? username : email;

        User user = User.builder()
                .nom(request.getNom())
                .prenom(request.getPrenom())
                .telephone(request.getTelephone())
                .email(emailCompte)
                .username(username)
                .password(passwordEncoder.encode(motDePasseClair))
                .role(request.getRole())
                .pharmacieId(request.getPharmacieId())
                .regionId(request.getRegionId())
                .structureSanitaireId(request.getStructureSanitaireId())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .actif(true)
                // Le mot de passe est saisi par l'administrateur, donc connu d'un tiers :
                // le titulaire doit choisir le sien avant d'entrer.
                .mustChangePassword(true)
                .build();

        User saved = userRepository.save(user);
        return new CompteCree(saved, motDePasseGenere ? motDePasseClair : null);
    }

    /**
     * Identifiant de connexion simple et unique, dérivé du nom : {@code prenom.nom}
     * (sans accents ni séparateurs, en minuscules), suffixe numérique en cas de collision.
     * Un compte Service Régional, qui n'a pas de nom, prend les initiales de sa région :
     * {@code service_regional_th} pour Thiès — et non plus {@code service_regional1}, qui ne
     * disait pas de quelle région il s'agissait. À défaut, on retombe sur la partie locale de
     * l'email, puis sur le rôle. L'unicité est vérifiée sur username ET email, car
     * l'identifiant sert d'email quand aucun n'est fourni.
     */
    private String genererUsername(UserRequest request) {
        String prenom = normaliser(request.getPrenom());
        String nom = normaliser(request.getNom());
        String initialesRegion = request.getRole() == Role.SERVICE_REGIONAL
                ? initialesRegion(request.getRegionId()) : "";
        String base;
        if (!initialesRegion.isBlank()) {
            base = "service_regional_" + initialesRegion;
        } else if (!prenom.isBlank() && !nom.isBlank()) {
            base = (prenom + "." + nom).toLowerCase();
        } else if (request.getEmail() != null && request.getEmail().contains("@")) {
            base = normaliser(request.getEmail().substring(0, request.getEmail().indexOf('@'))).toLowerCase();
        } else if (request.getRole() != null) {
            base = request.getRole().name().toLowerCase();
        } else {
            base = "compte";
        }
        if (base.isBlank()) {
            base = "compte";
        }
        String candidat = base;
        int suffixe = 1;
        while (userRepository.existsByUsernameIgnoreCase(candidat)
                || userRepository.findByEmail(candidat).isPresent()) {
            candidat = base + suffixe++;
        }
        return candidat;
    }

    /**
     * Initiales d'une région, en minuscules : son code (« TH » → « th »), à défaut la
     * première lettre de chaque mot de son nom (« Saint-Louis » → « sl »).
     */
    private String initialesRegion(String regionId) {
        if (regionId == null || regionId.isBlank()) {
            return "";
        }
        return regionRepository.findById(regionId)
                .map(region -> {
                    String code = normaliser(region.getCode());
                    if (!code.isBlank()) {
                        return code.toLowerCase();
                    }
                    StringBuilder initiales = new StringBuilder();
                    for (String mot : (region.getNom() == null ? "" : region.getNom()).split("[\\s'-]+")) {
                        String lettres = normaliser(mot);
                        if (!lettres.isBlank()) {
                            initiales.append(lettres.charAt(0));
                        }
                    }
                    return initiales.toString().toLowerCase();
                })
                .orElse("");
    }

    /** Minuscules ASCII sans accents ni séparateurs (les caractères spéciaux cassent l'identifiant). */
    private String normaliser(String valeur) {
        if (valeur == null) {
            return "";
        }
        String sansAccent = java.text.Normalizer.normalize(valeur, java.text.Normalizer.Form.NFD)
                .replaceAll("[\\u0300-\\u036f]", "");
        return sansAccent.replaceAll("[^A-Za-z0-9]", "");
    }

    /** Compte créé + mot de passe temporaire (non nul uniquement lorsqu'il a été généré). */
    public record CompteCree(User utilisateur, String motDePasseTemporaire) {}

    /**
     * nom/prénom sont facultatifs pour un Service Régional (compte de région, sans
     * personne physique) mais obligatoires pour tous les autres rôles. Aligne le backend
     * sur le formulaire, qui masque ces champs uniquement pour le Service Régional.
     */
    private void validerIdentite(UserRequest request) {
        if (request.getRole() != Role.SERVICE_REGIONAL
                && (request.getNom() == null || request.getNom().isBlank()
                    || request.getPrenom() == null || request.getPrenom().isBlank())) {
            throw new com.csu.pharmacie.exception.BusinessException("Le nom et le prénom sont obligatoires");
        }
    }

    public User updateUser(String id, UserRequest request) {
        User current = getCurrentUser();
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
        
        if (current.getRole() == Role.SERVICE_REGIONAL) {
            if (!current.getRegionId().equals(user.getRegionId()) || user.getRole() != Role.BCSU) {
                throw new com.csu.pharmacie.exception.ForbiddenException("Accès refusé");
            }
            if (request.getRole() != Role.BCSU) {
                throw new com.csu.pharmacie.exception.ForbiddenException("Vous ne pouvez modifier que des agents BCSU");
            }
            request.setRegionId(current.getRegionId());
        }
        
        validerIdentite(request);
        user.setNom(request.getNom());
        user.setPrenom(request.getPrenom());
        user.setTelephone(request.getTelephone());
        
        // Email facultatif : on ne le modifie que si un nouvel email non vide est fourni
        // (sinon on conserve l'existant et l'identifiant username reste inchangé).
        String nouvelEmail = request.getEmail() == null ? null : request.getEmail().trim();
        if (nouvelEmail != null && !nouvelEmail.isBlank() && !nouvelEmail.equalsIgnoreCase(user.getEmail())) {
            if (userRepository.findByEmail(nouvelEmail).isPresent()) {
                throw new ConflictException("Email déjà utilisé");
            }
            user.setEmail(nouvelEmail);
        }

        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            // Réinitialisation par un administrateur (/api/users est réservé à ADMIN et
            // SERVICE_REGIONAL) : le mot de passe est de nouveau connu d'un tiers, donc
            // de nouveau provisoire. Même règle qu'à la création.
            user.setMustChangePassword(true);
        }

        user.setRole(request.getRole());
        user.setPharmacieId(request.getPharmacieId());
        user.setRegionId(request.getRegionId());
        user.setStructureSanitaireId(request.getStructureSanitaireId());
        user.setUpdatedAt(LocalDateTime.now());

        return userRepository.save(user);
    }

    public void deleteUser(String id) {
        User current = getCurrentUser();
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
        if (current.getRole() == Role.SERVICE_REGIONAL) {
            if (!current.getRegionId().equals(user.getRegionId()) || user.getRole() != Role.BCSU) {
                throw new com.csu.pharmacie.exception.ForbiddenException("Accès refusé");
            }
        }
        userRepository.deleteById(id);
    }
}
