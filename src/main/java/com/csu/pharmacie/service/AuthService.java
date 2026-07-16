package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.LoginRequest;
import com.csu.pharmacie.dto.LoginResponse;
import com.csu.pharmacie.dto.RegisterRequest;
import com.csu.pharmacie.entity.Pointage;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.PointageRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PointageRepository pointageRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

    @org.springframework.transaction.annotation.Transactional
    public LoginResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));

        UserDetails userDetails = userDetailsService.loadUserByUsername(request.getEmail());
        String token = jwtUtil.generateToken(userDetails);

        Pointage pointage = controlerPointage(user);

        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .nom(user.getNom())
                .prenom(user.getPrenom())
                .role(user.getRole())
                .pharmacieId(user.getPharmacieId())
                .regionId(user.getRegionId())
                .structureSanitaireId(user.getStructureSanitaireId())
                .pointageHeure(pointage != null && pointage.getHeureArrivee() != null
                        ? pointage.getHeureArrivee().format(DateTimeFormatter.ofPattern("HH:mm"))
                        : null)
                .premierPointage(pointage != null && pointage.getHeureArrivee() == null)
                .pointageDepartHeure(pointage != null && pointage.getHeureDepart() != null
                        ? pointage.getHeureDepart().format(DateTimeFormatter.ofPattern("HH:mm"))
                        : null)
                .build();
    }

    /**
     * Contrôle du pointage à la connexion (cahier des charges, espace BCSU) :
     * la première connexion de la journée crée le pointage (sans heure d'arrivée/départ),
     * les suivantes mettent simplement à jour la dernière connexion.
     * Le pointage effectif d'arrivée/départ se fait via actions explicites de l'agent.
     */
    private Pointage controlerPointage(User user) {
        if (user.getRole() != Role.BCSU) {
            return null;
        }
        LocalDateTime maintenant = LocalDateTime.now();
        Pointage pointage = pointageRepository.findByUserIdAndDate(user.getId(), LocalDate.now())
                .map(p -> {
                    p.setDerniereConnexion(maintenant);
                    return p;
                })
                .orElseGet(() -> Pointage.builder()
                        .userId(user.getId())
                        .userNom(user.getNom())
                        .userPrenom(user.getPrenom())
                        .role(user.getRole())
                        .date(LocalDate.now())
                        .derniereConnexion(maintenant)
                        .build());
        return pointageRepository.save(pointage);
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
                .structureSanitaireId(request.getStructureSanitaireId())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .actif(true)
                .build();

        return userRepository.save(user);
    }
}
