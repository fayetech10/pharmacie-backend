package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.LoginRequest;
import com.csu.pharmacie.dto.LoginResponse;
import com.csu.pharmacie.dto.RegisterRequest;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

    public LoginResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));

        UserDetails userDetails = userDetailsService.loadUserByUsername(request.getEmail());
        String token = jwtUtil.generateToken(userDetails);

        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .nom(user.getNom())
                .prenom(user.getPrenom())
                .role(user.getRole())
                .pharmacieId(user.getPharmacieId())
                .regionId(user.getRegionId())
                .build();
    }

    public User register(RegisterRequest request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ConflictException("L'email est déjà utilisé");
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
}
