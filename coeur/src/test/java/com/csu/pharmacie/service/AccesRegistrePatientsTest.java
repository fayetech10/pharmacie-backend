package com.csu.pharmacie.service;

import com.csu.pharmacie.entity.Patient;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.repository.BonCommandeRepository;
import com.csu.pharmacie.repository.FactureRepository;
import com.csu.pharmacie.repository.FactureStructureRepository;
import com.csu.pharmacie.repository.FeuilleSoinsRepository;
import com.csu.pharmacie.repository.LettreGarantieRepository;
import com.csu.pharmacie.repository.PatientRepository;
import com.csu.pharmacie.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cloisonnement du registre des patients (données personnelles : identité, CNI,
 * n° d'assuré, date de naissance).
 *
 * Le registre complet ne doit être accessible qu'aux rôles de supervision. Un pharmacien
 * — qui n'a aucun écran « Patients » — pouvait auparavant lister l'intégralité du registre
 * via l'API : ce test empêche la régression. La recherche ciblée, elle, doit rester ouverte
 * (retrouver le bénéficiaire d'une ordonnance est un besoin métier réel).
 */
class AccesRegistrePatientsTest {

    private PatientRepository patientRepository;
    private UserRepository userRepository;
    private PatientService service;

    @BeforeEach
    void setUp() {
        patientRepository = mock(PatientRepository.class);
        userRepository = mock(UserRepository.class);

        service = new PatientService(
                patientRepository,
                userRepository,
                mock(LettreGarantieRepository.class),
                mock(BonCommandeRepository.class),
                mock(FactureRepository.class),
                mock(FactureStructureRepository.class),
                mock(FeuilleSoinsRepository.class),
                mock(NumeroSequenceService.class),
                mock(com.csu.pharmacie.repository.PieceDossierRepository.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Authentifie un utilisateur du rôle demandé (le principal JWT est l'email). */
    private void connecter(Role role, String structureSanitaireId) {
        String email = role.name().toLowerCase() + "@test.sn";
        User user = User.builder()
                .id("U-" + role.name())
                .email(email)
                .role(role)
                .structureSanitaireId(structureSanitaireId)
                .actif(true)
                .build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    /** Un pharmacien n'a aucune raison de parcourir tout le registre des patients. */
    @Test
    void lePharmacienNePeutPasListerLeRegistreComplet() {
        connecter(Role.PHARMACIEN, null);

        assertThrows(ForbiddenException.class, () -> service.getAll());
        verify(patientRepository, never()).findAll();
    }

    /** Les rôles de supervision conservent la vue d'ensemble. */
    @Test
    void lesRolesDeSupervisionAccedentAuRegistre() {
        when(patientRepository.findAll()).thenReturn(List.of(Patient.builder().id("P1").nom("Diop").build()));

        for (Role role : List.of(Role.ADMIN, Role.SERVICE_REGIONAL, Role.SERVICE_CENTRAL)) {
            SecurityContextHolder.clearContext();
            connecter(role, null);
            assertEquals(1, service.getAll().size(), "le rôle " + role + " doit voir le registre");
        }
    }

    /** Un agent BCSU reste limité à son périmètre (ses patients et ceux de sa structure). */
    @Test
    void lAgentBcsuResteLimiteASonPerimetre() {
        connecter(Role.BCSU, "S1");
        when(userRepository.findByStructureSanitaireId("S1")).thenReturn(List.of());
        when(patientRepository.findByCreatedByIn(any())).thenReturn(List.of());

        service.getAll();

        verify(patientRepository, never()).findAll();
        verify(patientRepository).findByCreatedByIn(Set.of("U-BCSU"));
    }

    /** Une structure sanitaire ne voit que les patients liés à ses propres factures. */
    @Test
    void laStructureNeVoitQueSesPatients() {
        connecter(Role.STRUCTURE_SANITAIRE, "S1");
        when(patientRepository.findDistinctByStructureSanitaireId("S1")).thenReturn(List.of());

        service.getAll();

        verify(patientRepository, never()).findAll();
        verify(patientRepository).findDistinctByStructureSanitaireId("S1");
    }

    /** La recherche ciblée reste ouverte : elle sert à retrouver un bénéficiaire précis. */
    @Test
    void laRechercheCibleeResteOuverteAuPharmacien() {
        connecter(Role.PHARMACIEN, null);
        when(patientRepository.findByNumeroCni(anyString())).thenReturn(Optional.empty());
        when(patientRepository.findByNumeroAssure(anyString())).thenReturn(Optional.empty());
        when(patientRepository.findByNomContainingIgnoreCaseOrPrenomContainingIgnoreCase(anyString(), anyString()))
                .thenReturn(List.of(Patient.builder().id("P1").nom("Diop").prenom("Awa").build()));

        assertEquals(1, service.search("Diop").size());
    }
}
