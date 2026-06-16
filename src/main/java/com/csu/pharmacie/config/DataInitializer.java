package com.csu.pharmacie.config;

import com.csu.pharmacie.entity.*;
import com.csu.pharmacie.repository.FactureRepository;
import com.csu.pharmacie.repository.PharmacieRepository;
import com.csu.pharmacie.repository.RegionRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.repository.MedicamentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RegionRepository regionRepository;
    private final UserRepository userRepository;
    private final PharmacieRepository pharmacieRepository;
    private final FactureRepository factureRepository;
    private final MedicamentRepository medicamentRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        if (regionRepository.count() == 0) {
            initRegions();
        }
        
        if (userRepository.count() == 0) {
            initUsersAndPharmacies();
        }
        
        if (medicamentRepository.count() == 0) {
            initMedicaments();
        }
    }

    private void initMedicaments() {
        List<Medicament> medicaments = Arrays.asList(
            Medicament.builder().code("MED001").nom("Paracétamol 500mg").statut(StatutMedicament.ELIGIBLE).description("Antalgique").actif(true).createdAt(LocalDateTime.now()).build(),
            Medicament.builder().code("MED002").nom("Ibuprofène 400mg").statut(StatutMedicament.ELIGIBLE).description("Anti-inflammatoire").actif(true).createdAt(LocalDateTime.now()).build(),
            Medicament.builder().code("MED003").nom("Amoxicilline 1g").statut(StatutMedicament.ELIGIBLE).description("Antibiotique").actif(true).createdAt(LocalDateTime.now()).build(),
            Medicament.builder().code("MED004").nom("Vitamine C").statut(StatutMedicament.EXCLU).description("Complément").actif(true).createdAt(LocalDateTime.now()).build(),
            Medicament.builder().code("MED005").nom("Sirop pour la toux X").statut(StatutMedicament.EXCLU).description("Sirop non remboursable").actif(true).createdAt(LocalDateTime.now()).build()
        );
        medicamentRepository.saveAll(medicaments);
    }

    private void initRegions() {
        List<Region> regions = Arrays.asList(
                Region.builder().code("DK").nom("Dakar").build(),
                Region.builder().code("TH").nom("Thiès").build(),
                Region.builder().code("SL").nom("Saint-Louis").build(),
                Region.builder().code("DI").nom("Diourbel").build(),
                Region.builder().code("ZI").nom("Ziguinchor").build(),
                Region.builder().code("LO").nom("Louga").build(),
                Region.builder().code("FA").nom("Fatick").build(),
                Region.builder().code("KA").nom("Kaolack").build(),
                Region.builder().code("KD").nom("Kolda").build(),
                Region.builder().code("TA").nom("Tambacounda").build(),
                Region.builder().code("KE").nom("Kédougou").build(),
                Region.builder().code("KF").nom("Kaffrine").build(),
                Region.builder().code("SE").nom("Sédhiou").build(),
                Region.builder().code("MA").nom("Matam").build()
        );
        regionRepository.saveAll(regions);
    }

    private void initUsersAndPharmacies() {
        Region dakar = regionRepository.findByCode("DK").orElseThrow();

        // Seul compte créé par défaut : l'administrateur.
        // Les comptes Service Central / Service Régional / Pharmacien sont ensuite
        // créés par l'admin via POST /api/auth/register (réservé au rôle ADMIN).
        User admin = User.builder()
                .nom("Admin")
                .prenom("Super")
                .email("admin@csu.sn")
                .password(passwordEncoder.encode("password123"))
                .role(Role.ADMIN)
                .actif(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        userRepository.save(admin);

        // Pharmacie
        Pharmacie pharmacie = Pharmacie.builder()
                .code("PH001")
                .nom("Pharmacie Guigon")
                .adresse("Plateau, Dakar")
                .telephone("338000000")
                .email("contact@guigon.sn")
                .regionId(dakar.getId())
                .createdAt(LocalDateTime.now())
                .actif(true)
                .build();
        pharmacie = pharmacieRepository.save(pharmacie);
    }
}
