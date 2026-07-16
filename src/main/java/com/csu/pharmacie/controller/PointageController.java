package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.PointageDto;
import com.csu.pharmacie.entity.Pointage;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.PointageRepository;
import com.csu.pharmacie.repository.StructureSanitaireRepository;
import com.csu.pharmacie.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/pointages")
@RequiredArgsConstructor
public class PointageController {

    private final PointageRepository pointageRepository;
    private final UserRepository userRepository;
    private final StructureSanitaireRepository structureSanitaireRepository;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    /** Pointages d'une journée (défaut : aujourd'hui). Réservé ADMIN et Service Régional. */
    @GetMapping
    public ResponseEntity<List<PointageDto>> getByDate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        User user = getCurrentUser();
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.SERVICE_REGIONAL && user.getRole() != Role.SERVICE_CENTRAL) {
            throw new ForbiddenException("Accès refusé");
        }
        List<Pointage> pointages = pointageRepository.findByDateOrderByHeureArriveeAsc(date != null ? date : LocalDate.now());
        
        // Précharger la structure de rattachement pour chaque agent BCSU
        Map<String, String> bcsuStructureMap = structureSanitaireRepository.findAll().stream()
                .filter(s -> s.getBcsuId() != null)
                .collect(Collectors.toMap(StructureSanitaire::getBcsuId, StructureSanitaire::getNom, (a, b) -> a));

        List<PointageDto> dtos = pointages.stream().map(p -> {
            String structNom = bcsuStructureMap.getOrDefault(p.getUserId(), "Non rattaché");
            return toDto(p, structNom);
        }).collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    /** Historique des pointages de l'agent connecté. */
    @GetMapping("/mes-pointages")
    public ResponseEntity<List<Pointage>> mesPointages() {
        User user = getCurrentUser();
        return ResponseEntity.ok(pointageRepository.findByUserIdOrderByDateDesc(user.getId()));
    }

    /** Historique des pointages d'un agent spécifique (pour les modales détails). */
    @GetMapping("/agent/{userId}")
    public ResponseEntity<List<Pointage>> getPointagesByAgent(@PathVariable String userId) {
        return ResponseEntity.ok(pointageRepository.findByUserIdOrderByDateDesc(userId));
    }

    /** Enregistrer l'arrivée (check-in) de l'agent connecté. */
    @PostMapping("/arrivee")
    public ResponseEntity<Pointage> marquerArrivee() {
        User user = getCurrentUser();
        LocalDateTime maintenant = LocalDateTime.now();
        Pointage pointage = pointageRepository.findByUserIdAndDate(user.getId(), LocalDate.now())
                .orElseGet(() -> Pointage.builder()
                        .userId(user.getId())
                        .userNom(user.getNom())
                        .userPrenom(user.getPrenom())
                        .role(user.getRole())
                        .date(LocalDate.now())
                        .build());
        pointage.setHeureArrivee(maintenant);
        pointage.setDerniereConnexion(maintenant);
        return ResponseEntity.ok(pointageRepository.save(pointage));
    }

    /** Enregistrer le départ (check-out) de l'agent connecté. */
    @PostMapping("/depart")
    public ResponseEntity<Pointage> marquerDepart() {
        User user = getCurrentUser();
        LocalDateTime maintenant = LocalDateTime.now();
        Pointage pointage = pointageRepository.findByUserIdAndDate(user.getId(), LocalDate.now())
                .orElseGet(() -> Pointage.builder()
                        .userId(user.getId())
                        .userNom(user.getNom())
                        .userPrenom(user.getPrenom())
                        .role(user.getRole())
                        .date(LocalDate.now())
                        .build());
        pointage.setHeureDepart(maintenant);
        pointage.setDerniereConnexion(maintenant);
        return ResponseEntity.ok(pointageRepository.save(pointage));
    }

    private PointageDto toDto(Pointage p, String structureNom) {
        return PointageDto.builder()
                .id(p.getId())
                .userId(p.getUserId())
                .userNom(p.getUserNom())
                .userPrenom(p.getUserPrenom())
                .role(p.getRole())
                .date(p.getDate())
                .heureArrivee(p.getHeureArrivee())
                .heureDepart(p.getHeureDepart())
                .derniereConnexion(p.getDerniereConnexion())
                .structureNom(structureNom)
                .build();
    }
}
