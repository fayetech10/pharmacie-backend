package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.UserRequest;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
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

    public User createUser(UserRequest request) {
        User current = getCurrentUser();
        if (current.getRole() == Role.SERVICE_REGIONAL) {
            if (request.getRole() != Role.BCSU) {
                throw new com.csu.pharmacie.exception.ForbiddenException("Vous ne pouvez créer que des agents BCSU");
            }
            request.setRegionId(current.getRegionId());
        }

        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ConflictException("Email déjà utilisé");
        }

        // Le mot de passe n'est optionnel qu'en modification (DTO partagé) :
        // à la création, son absence doit produire une erreur claire, pas un 500.
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new com.csu.pharmacie.exception.BusinessException("Le mot de passe est obligatoire à la création");
        }

        validerIdentite(request);

        User user = User.builder()
                .nom(request.getNom())
                .prenom(request.getPrenom())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .pharmacieId(request.getPharmacieId())
                .regionId(request.getRegionId())
                .structureSanitaireId(request.getStructureSanitaireId())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .actif(true)
                .build();

        return userRepository.save(user);
    }

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
        
        if (!user.getEmail().equals(request.getEmail())) {
            if (userRepository.findByEmail(request.getEmail()).isPresent()) {
                throw new ConflictException("Email déjà utilisé");
            }
            user.setEmail(request.getEmail());
        }

        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
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
