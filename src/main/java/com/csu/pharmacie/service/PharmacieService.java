package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.PharmacieRequest;
import com.csu.pharmacie.entity.*;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.repository.PharmacieRepository;
import com.csu.pharmacie.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PharmacieService {

    private final PharmacieRepository pharmacieRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    public List<Pharmacie> getAllPharmacies() {
        User user = getCurrentUser();
        // Le Service Régional ne voit que les pharmacies de sa propre région
        if (user.getRole() == Role.SERVICE_REGIONAL) {
            return pharmacieRepository.findByRegionId(user.getRegionId());
        }
        return pharmacieRepository.findAll();
    }

    public Pharmacie getPharmacieById(String id) {
        return pharmacieRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacie non trouvée"));
    }

    public Pharmacie createPharmacie(PharmacieRequest request) {
        User currentUser = getCurrentUser();
        // Un Service Régional ne peut créer une pharmacie que dans sa propre région
        if (currentUser.getRole() == Role.SERVICE_REGIONAL) {
            request.setRegionId(currentUser.getRegionId());
        }

        if (pharmacieRepository.findByCode(request.getCode()).isPresent()) {
            throw new ConflictException("Code pharmacie déjà utilisé");
        }

        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ConflictException("Un compte utilisateur existe déjà avec cet email");
        }

        // 1. Créer la pharmacie
        Pharmacie pharmacie = Pharmacie.builder()
                .code(request.getCode())
                .nom(request.getNom())
                .adresse(request.getAdresse())
                .telephone(request.getTelephone())
                .email(request.getEmail())
                .regionId(request.getRegionId())
                .createdAt(LocalDateTime.now())
                .actif(true)
                .build();

        pharmacie = pharmacieRepository.save(pharmacie);

        // 2. Créer l'utilisateur Pharmacien associé
        String rawPassword = request.getPassword();
        if (rawPassword == null || rawPassword.trim().isEmpty()) {
            rawPassword = "password123"; // fallback par défaut
        }

        User user = User.builder()
                .nom(request.getNom())
                .prenom("Responsable")
                .email(request.getEmail())
                .password(passwordEncoder.encode(rawPassword))
                .role(Role.PHARMACIEN)
                .pharmacieId(pharmacie.getId())
                .regionId(pharmacie.getRegionId())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .actif(true)
                .build();

        user = userRepository.save(user);

        // 3. Mettre à jour le responsableId de la pharmacie
        pharmacie.setResponsableId(user.getId());
        return pharmacieRepository.save(pharmacie);
    }

    public Pharmacie updatePharmacie(String id, PharmacieRequest request) {
        Pharmacie pharmacie = getPharmacieById(id);
        String oldEmail = pharmacie.getEmail();

        User currentUser = getCurrentUser();
        // Un Service Régional ne peut modifier que les pharmacies de sa propre région,
        // et ne peut pas les transférer vers une autre région.
        if (currentUser.getRole() == Role.SERVICE_REGIONAL) {
            if (!currentUser.getRegionId().equals(pharmacie.getRegionId())) {
                throw new ForbiddenException("Vous ne pouvez modifier que les pharmacies de votre région");
            }
            request.setRegionId(currentUser.getRegionId());
        }

        if (!pharmacie.getCode().equals(request.getCode())) {
            if (pharmacieRepository.findByCode(request.getCode()).isPresent()) {
                throw new ConflictException("Code pharmacie déjà utilisé");
            }
            pharmacie.setCode(request.getCode());
        }

        pharmacie.setNom(request.getNom());
        pharmacie.setAdresse(request.getAdresse());
        pharmacie.setTelephone(request.getTelephone());
        pharmacie.setEmail(request.getEmail());
        pharmacie.setRegionId(request.getRegionId());

        Pharmacie saved = pharmacieRepository.save(pharmacie);

        // Mettre à jour l'utilisateur associé
        userRepository.findByEmail(oldEmail).ifPresent(user -> {
            user.setEmail(saved.getEmail());
            user.setNom(saved.getNom());
            user.setRegionId(saved.getRegionId());
            if (request.getPassword() != null && !request.getPassword().trim().isEmpty()) {
                user.setPassword(passwordEncoder.encode(request.getPassword()));
            }
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
        });

        return saved;
    }

    public void deletePharmacie(String id) {
        Pharmacie pharmacie = getPharmacieById(id);

        User currentUser = getCurrentUser();
        // Un Service Régional ne peut supprimer que les pharmacies de sa propre région
        if (currentUser.getRole() == Role.SERVICE_REGIONAL
                && !currentUser.getRegionId().equals(pharmacie.getRegionId())) {
            throw new ForbiddenException("Vous ne pouvez supprimer que les pharmacies de votre région");
        }

        // Supprimer l'utilisateur associé également
        if (pharmacie.getResponsableId() != null) {
            userRepository.deleteById(pharmacie.getResponsableId());
        } else {
            userRepository.findByEmail(pharmacie.getEmail()).ifPresent(user -> userRepository.deleteById(user.getId()));
        }
        pharmacieRepository.deleteById(id);
    }
}
