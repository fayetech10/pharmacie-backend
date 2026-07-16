package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.StructureSanitaireRequest;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.StructureSanitaireRepository;
import com.csu.pharmacie.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StructureSanitaireService {

    private final StructureSanitaireRepository structureRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    public List<StructureSanitaire> getAll() {
        User user = getCurrentUser();
        // Le Service Régional ne voit que les structures de sa propre région.
        if (user.getRole() == Role.SERVICE_REGIONAL) {
            return structureRepository.findByRegionId(user.getRegionId());
        }
        return structureRepository.findAll();
    }

    /** Structures rattachées à un agent BCSU précis (par son userId). */
    public List<StructureSanitaire> getByBcsuId(String bcsuId) {
        return structureRepository.findByBcsuId(bcsuId);
    }

    public StructureSanitaire getById(String id) {
        return structureRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Structure sanitaire non trouvée"));
    }

    @Transactional
    public StructureSanitaire create(StructureSanitaireRequest request) {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.SERVICE_REGIONAL) {
            request.setRegionId(currentUser.getRegionId());
        }

        if (structureRepository.findByCode(request.getCode()).isPresent()) {
            throw new ConflictException("Code structure déjà utilisé");
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()
                && userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ConflictException("Un compte utilisateur existe déjà avec cet email");
        }

        StructureSanitaire structure = StructureSanitaire.builder()
                .code(request.getCode())
                .nom(request.getNom())
                .adresse(request.getAdresse())
                .telephone(request.getTelephone())
                .email(request.getEmail())
                .regionId(request.getRegionId())
                .bcsuId(request.getBcsuId())
                .actif(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        structure = structureRepository.save(structure);

        // Crée le compte agent (rôle STRUCTURE_SANITAIRE) si un email est fourni.
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            String rawPassword = (request.getPassword() == null || request.getPassword().isBlank())
                    ? "password123" : request.getPassword();
            User agent = User.builder()
                    .nom(request.getNom())
                    .prenom("Agent")
                    .email(request.getEmail())
                    .password(passwordEncoder.encode(rawPassword))
                    .role(Role.STRUCTURE_SANITAIRE)
                    .regionId(request.getRegionId())
                    .structureSanitaireId(structure.getId())
                    .actif(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            agent = userRepository.save(agent);
            structure.setResponsableId(agent.getId());
            structure = structureRepository.save(structure);
        }
        return structure;
    }

    @Transactional
    public StructureSanitaire update(String id, StructureSanitaireRequest request) {
        StructureSanitaire structure = getById(id);
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.SERVICE_REGIONAL) {
            if (!currentUser.getRegionId().equals(structure.getRegionId())) {
                throw new ForbiddenException("Vous ne pouvez modifier que les structures de votre région");
            }
            request.setRegionId(currentUser.getRegionId());
        }

        if (!structure.getCode().equals(request.getCode())
                && structureRepository.findByCode(request.getCode()).isPresent()) {
            throw new ConflictException("Code structure déjà utilisé");
        }

        String oldEmail = structure.getEmail();
        structure.setCode(request.getCode());
        structure.setNom(request.getNom());
        structure.setAdresse(request.getAdresse());
        structure.setTelephone(request.getTelephone());
        structure.setEmail(request.getEmail());
        structure.setRegionId(request.getRegionId());
        structure.setBcsuId(request.getBcsuId());
        structure.setUpdatedAt(LocalDateTime.now());
        StructureSanitaire saved = structureRepository.save(structure);

        // Répercute sur le compte agent associé.
        if (oldEmail != null && !oldEmail.isBlank()) {
            userRepository.findByEmail(oldEmail).ifPresent(agent -> {
                agent.setEmail(saved.getEmail());
                agent.setNom(saved.getNom());
                agent.setRegionId(saved.getRegionId());
                if (request.getPassword() != null && !request.getPassword().isBlank()) {
                    agent.setPassword(passwordEncoder.encode(request.getPassword()));
                }
                agent.setUpdatedAt(LocalDateTime.now());
                userRepository.save(agent);
            });
        }
        return saved;
    }

    @Transactional
    public void delete(String id) {
        StructureSanitaire structure = getById(id);
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.SERVICE_REGIONAL
                && !currentUser.getRegionId().equals(structure.getRegionId())) {
            throw new ForbiddenException("Vous ne pouvez supprimer que les structures de votre région");
        }
        if (structure.getResponsableId() != null) {
            userRepository.deleteById(structure.getResponsableId());
        } else if (structure.getEmail() != null) {
            userRepository.findByEmail(structure.getEmail())
                    .ifPresent(u -> userRepository.deleteById(u.getId()));
        }
        structureRepository.deleteById(id);
    }
}
