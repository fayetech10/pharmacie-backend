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
import java.util.List;

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
