package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.UserRequest;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User getUserById(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    public User createUser(UserRequest request) {
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
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .actif(true)
                .build();

        return userRepository.save(user);
    }

    public User updateUser(String id, UserRequest request) {
        User user = getUserById(id);
        
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
        user.setUpdatedAt(LocalDateTime.now());

        return userRepository.save(user);
    }

    public void deleteUser(String id) {
        userRepository.deleteById(id);
    }
}
