package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.ConstatRequest;
import com.csu.pharmacie.entity.Constat;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.ConstatRepository;
import com.csu.pharmacie.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Constats d'activité des agents BCSU : chaque agent saisit et consulte ses propres
 * constats ; l'ADMIN voit l'ensemble. Le contrôle d'accès fin est réalisé ici — le
 * SecurityConfig se contente de réserver l'écriture aux rôles BCSU / ADMIN.
 */
@Service
@RequiredArgsConstructor
public class ConstatService {

    private final ConstatRepository constatRepository;
    private final UserRepository userRepository;
    private final NumeroSequenceService numeroSequenceService;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    private void checkAgentAutorise(User user) {
        if (user.getRole() != Role.BCSU && user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Seul un agent BCSU peut enregistrer des constats");
        }
    }

    public List<Constat> getAllForCurrentUser() {
        User user = getCurrentUser();
        if (user.getRole() == Role.ADMIN) {
            return constatRepository.findAllByOrderByCreatedAtDesc();
        }
        checkAgentAutorise(user);
        return constatRepository.findByCreatedByOrderByCreatedAtDesc(user.getId());
    }

    @Transactional
    public Constat creer(ConstatRequest request) {
        User user = getCurrentUser();
        checkAgentAutorise(user);

        String structureNom = numeroSequenceService.structureDeLAgent(user)
                .map(StructureSanitaire::getNom).orElse("");

        Constat constat = Constat.builder()
                .regime(request.getRegime())
                .description(request.getDescription().trim())
                .agentNom(user.getNom())
                .agentPrenom(user.getPrenom())
                .structureNom(structureNom)
                .createdAt(LocalDateTime.now())
                .createdBy(user.getId())
                .build();
        return constatRepository.save(constat);
    }

    @Transactional
    public void supprimer(String id) {
        User user = getCurrentUser();
        checkAgentAutorise(user);
        Constat constat = constatRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Constat non trouvé"));
        // Un agent BCSU ne peut supprimer que ses propres constats (l'ADMIN, tous).
        if (user.getRole() == Role.BCSU && !Objects.equals(constat.getCreatedBy(), user.getId())) {
            throw new ForbiddenException("Accès refusé");
        }
        constatRepository.deleteById(id);
    }
}
