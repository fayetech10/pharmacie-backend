package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.ChangePasswordRequest;
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
    private final com.csu.pharmacie.repository.StructureSanitaireRepository structureRepository;
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

        // Le champ « email » de la requête peut porter l'email OU le username attribué à l'import.
        User user = userRepository.findByEmail(request.getEmail())
                .or(() -> userRepository.findByUsernameIgnoreCase(request.getEmail()))
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));

        UserDetails userDetails = userDetailsService.loadUserByUsername(request.getEmail());
        String token = jwtUtil.generateToken(userDetails);

        String structType = null;
        String structNom = null;
        if (user.getRole() == Role.STRUCTURE_SANITAIRE && user.getStructureSanitaireId() != null) {
            com.csu.pharmacie.entity.StructureSanitaire ss = structureRepository.findById(user.getStructureSanitaireId()).orElse(null);
            if (ss != null) {
                structType = ss.getTypeStructure();
                structNom = ss.getNom();
            }
        }

        Pointage pointage = controlerPointage(user);

        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .mustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()))
                .nom(user.getNom())
                .prenom(user.getPrenom())
                .role(user.getRole())
                .pharmacieId(user.getPharmacieId())
                .regionId(user.getRegionId())
                .structureSanitaireId(user.getStructureSanitaireId())
                .structureType(structType)
                .structureNom(structNom)
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


    /**
     * Changement de mot de passe par l'utilisateur connecté. Lève le drapeau
     * mustChangePassword posé à la création en masse (mot de passe générique).
     */
    public void changePassword(ChangePasswordRequest request) {
        String email = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));

        if (!passwordEncoder.matches(request.getAncienMotDePasse(), user.getPassword())) {
            throw new com.csu.pharmacie.exception.BusinessException("Mot de passe actuel incorrect");
        }
        if (passwordEncoder.matches(request.getNouveauMotDePasse(), user.getPassword())) {
            throw new com.csu.pharmacie.exception.BusinessException(
                    "Le nouveau mot de passe doit être différent de l'ancien");
        }

        user.setPassword(passwordEncoder.encode(request.getNouveauMotDePasse()));
        user.setMustChangePassword(false);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
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
