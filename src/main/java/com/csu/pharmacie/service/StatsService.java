package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.MedicamentStat;
import com.csu.pharmacie.dto.MonthData;
import com.csu.pharmacie.dto.PharmacieStat;
import com.csu.pharmacie.dto.RegionStat;
import com.csu.pharmacie.dto.StatsDto;
import com.csu.pharmacie.dto.AgentStatDto;
import com.csu.pharmacie.entity.Facture;
import com.csu.pharmacie.entity.HistoriqueAction;
import com.csu.pharmacie.entity.LigneFacture;
import com.csu.pharmacie.entity.Region;
import com.csu.pharmacie.entity.StatutFacture;
import com.csu.pharmacie.entity.StatutLigne;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.Pointage;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.BonCommande;
import com.csu.pharmacie.repository.FactureRepository;
import com.csu.pharmacie.repository.RegionRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.repository.PatientRepository;
import com.csu.pharmacie.repository.LettreGarantieRepository;
import com.csu.pharmacie.repository.FeuilleSoinsRepository;
import com.csu.pharmacie.repository.PointageRepository;
import com.csu.pharmacie.repository.StructureSanitaireRepository;
import com.csu.pharmacie.repository.BonCommandeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatsService {

    private final FactureRepository factureRepository;
    private final RegionRepository regionRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final LettreGarantieRepository lettreGarantieRepository;
    private final FeuilleSoinsRepository feuilleSoinsRepository;
    private final PointageRepository pointageRepository;
    private final StructureSanitaireRepository structureSanitaireRepository;
    private final BonCommandeRepository bonCommandeRepository;

    public StatsDto getStatsNational() {
        return computeStats(factureRepository.findAll(), null, null);
    }

    public StatsDto getStatsRegional(String regionId) {
        return computeStats(factureRepository.findByRegionId(regionId), regionId, null);
    }

    public StatsDto getStatsPharmacie(String pharmacieId) {
        return computeStats(factureRepository.findByPharmacieId(pharmacieId), null, pharmacieId);
    }

    public List<AgentStatDto> getStatsAgents() {
        List<User> bcsuAgents = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.BCSU)
                .collect(Collectors.toList());

        // Précharger la structure de rattachement pour chaque agent BCSU
        Map<String, String> bcsuStructureMap = structureSanitaireRepository.findAll().stream()
                .filter(s -> s.getBcsuId() != null)
                .collect(Collectors.toMap(StructureSanitaire::getBcsuId, StructureSanitaire::getNom, (a, b) -> a));

        List<AgentStatDto> stats = new ArrayList<>();
        for (User u : bcsuAgents) {
            String uid = u.getId();
            long patients = patientRepository.countByCreatedBy(uid);
            long lettres = lettreGarantieRepository.countByCreatedBy(uid);
            long feuilles = feuilleSoinsRepository.countByCreatedBy(uid);
            
            // Calculer les heures travaillées de la semaine
            LocalDateTime oneWeekAgo = LocalDateTime.now().minusDays(7);
            List<Pointage> pointages = pointageRepository.findByUserIdOrderByDateDesc(uid).stream()
                    .filter(p -> p.getDate().isAfter(oneWeekAgo.toLocalDate()) || p.getDate().isEqual(oneWeekAgo.toLocalDate()))
                    .collect(Collectors.toList());
            
            double heuresSemaine = 0;
            for (Pointage p : pointages) {
                if (p.getHeureArrivee() != null && p.getHeureDepart() != null) {
                    heuresSemaine += Duration.between(p.getHeureArrivee(), p.getHeureDepart()).toMinutes() / 60.0;
                }
            }

            // Temps moyen : fictif ou calculé plus tard. 0 pour l'instant
            double tempsMoyen = 0;

            // Résoudre le nom de la structure via bcsuId
            String structureNom = bcsuStructureMap.getOrDefault(uid, "Non rattaché");

            stats.add(AgentStatDto.builder()
                    .id(uid)
                    .nom(u.getPrenom() + " " + u.getNom())
                    .structure(structureNom)
                    .dossiersTraites(patients)
                    .lettresEmises(lettres)
                    .feuillesSoins(feuilles)
                    .anomalies(0)
                    .tempsMoyen(tempsMoyen)
                    .heuresTravailleesSemaine(round1(heuresSemaine))
                    .build());
        }
        return stats;
    }

    private StatsDto computeStats(List<Facture> factures, String regionId, String pharmacieId) {
        long nombreFactures = factures.size();
        double montantTotal = factures.stream().mapToDouble(Facture::getMontantTotal).sum();
        double montantCsu = montantTotal / 2.0;
        double montantMoyen = nombreFactures > 0 ? montantTotal / nombreFactures : 0;

        Map<String, Long> facturesParStatut = factures.stream()
                .collect(Collectors.groupingBy(f -> f.getStatut().name(), Collectors.counting()));

        Map<String, Double> montantParStatut = factures.stream()
                .collect(Collectors.groupingBy(f -> f.getStatut().name(),
                        Collectors.summingDouble(Facture::getMontantTotal)));

        // Taux de validation / rejet sur les factures traitées (hors brouillon)
        long traitees = factures.stream().filter(f -> f.getStatut() != StatutFacture.BROUILLON).count();
        long validees = factures.stream().filter(StatsService::estValidee).count();
        long rejetees = factures.stream().filter(StatsService::estRejetee).count();
        double tauxValidation = traitees > 0 ? round1(validees * 100.0 / traitees) : 0;
        double tauxRejet = traitees > 0 ? round1(rejetees * 100.0 / traitees) : 0;

        // Qualité au niveau des lignes
        long lignesAcceptees = 0;
        long lignesRejetees = 0;
        for (Facture f : factures) {
            if (f.getLignes() == null) continue;
            for (LigneFacture l : f.getLignes()) {
                if (l.getStatutLigne() == StatutLigne.ACCEPTEE) lignesAcceptees++;
                else if (l.getStatutLigne() == StatutLigne.REJETEE) lignesRejetees++;
            }
        }

        // --- NOUVEAUX KPI : Patients, Lettres, Feuilles selon Scope ---
        long totalPatients = 0;
        long totalLettres = 0;
        long totalFeuilles = 0;

        if (regionId != null && !regionId.isEmpty()) {
            List<String> structIds = structureSanitaireRepository.findByRegionId(regionId).stream()
                    .map(StructureSanitaire::getId)
                    .collect(Collectors.toList());
            List<String> uids = userRepository.findAll().stream()
                    .filter(u -> regionId.equals(u.getRegionId()))
                    .map(User::getId)
                    .collect(Collectors.toList());

            if (!uids.isEmpty()) {
                totalLettres = lettreGarantieRepository.findAll().stream()
                        .filter(l -> uids.contains(l.getCreatedBy()))
                        .count();
                totalPatients = patientRepository.findAll().stream()
                        .filter(p -> uids.contains(p.getCreatedBy()))
                        .count();
            }
            if (!structIds.isEmpty()) {
                totalFeuilles = feuilleSoinsRepository.findAll().stream()
                        .filter(fs -> structIds.contains(fs.getStructureSanitaireId()))
                        .count();
            }
        } else if (pharmacieId != null && !pharmacieId.isEmpty()) {
            totalPatients = factures.stream()
                    .filter(f -> f.getLignes() != null)
                    .flatMap(f -> f.getLignes().stream())
                    .map(LigneFacture::getPatientMatricule)
                    .filter(m -> m != null && !m.isBlank())
                    .distinct()
                    .count();
        } else {
            totalPatients = patientRepository.count();
            totalLettres = lettreGarantieRepository.count();
            totalFeuilles = feuilleSoinsRepository.count();
        }

        // --- REPARTITIONS PAR REGIME ---
        Map<String, Long> lignesParRegime = new HashMap<>();
        Map<String, Double> montantParRegime = new HashMap<>();

        List<String> bonNums = factures.stream()
                .filter(f -> f.getLignes() != null)
                .flatMap(f -> f.getLignes().stream())
                .map(LigneFacture::getBonCommandeNumero)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .collect(Collectors.toList());

        Map<String, String> bonRegimeMap = new HashMap<>();
        if (!bonNums.isEmpty()) {
            try {
                bonRegimeMap = bonCommandeRepository.findByNumeroIn(bonNums).stream()
                        .filter(b -> b.getRegime() != null)
                        .collect(Collectors.toMap(BonCommande::getNumero, b -> b.getRegime().name(), (a, b) -> a));
            } catch (Exception e) {
                // Sourdine
            }
        }

        List<String> matricules = factures.stream()
                .filter(f -> f.getLignes() != null)
                .flatMap(f -> f.getLignes().stream())
                .filter(l -> l.getBonCommandeNumero() == null || l.getBonCommandeNumero().isBlank())
                .map(LigneFacture::getPatientMatricule)
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .collect(Collectors.toList());

        Map<String, String> patientRegimeMap = new HashMap<>();
        if (!matricules.isEmpty()) {
            try {
                List<com.csu.pharmacie.entity.Patient> patients = new ArrayList<>();
                patients.addAll(patientRepository.findByNumeroAssureIn(matricules));
                patients.addAll(patientRepository.findByNumeroCniIn(matricules));
                for (com.csu.pharmacie.entity.Patient p : patients) {
                    if (p.getRegime() != null) {
                        if (p.getNumeroAssure() != null) {
                            patientRegimeMap.put(p.getNumeroAssure(), p.getRegime().name());
                        }
                        if (p.getNumeroCni() != null) {
                            patientRegimeMap.put(p.getNumeroCni(), p.getRegime().name());
                        }
                    }
                }
            } catch (Exception e) {
                // Sourdine
            }
        }

        for (Facture f : factures) {
            if (f.getLignes() == null) continue;
            for (LigneFacture l : f.getLignes()) {
                String regime = null;
                if (l.getBonCommandeNumero() != null && !l.getBonCommandeNumero().isBlank()) {
                    regime = bonRegimeMap.get(l.getBonCommandeNumero());
                }
                if (regime == null && l.getPatientMatricule() != null && !l.getPatientMatricule().isBlank()) {
                    regime = patientRegimeMap.get(l.getPatientMatricule());
                }
                if (regime == null) {
                    regime = "CONTRIBUTIF";
                }

                lignesParRegime.put(regime, lignesParRegime.getOrDefault(regime, 0L) + l.getQuantite());
                montantParRegime.put(regime, montantParRegime.getOrDefault(regime, 0.0) + l.getMontant());
            }
        }

        return StatsDto.builder()
                .nombreFactures(nombreFactures)
                .montantTotal(montantTotal)
                .montantCsu(montantCsu)
                .montantMoyen(montantMoyen)
                .totalPatients(totalPatients)
                .totalLettresGarantie(totalLettres)
                .totalFeuillesSoins(totalFeuilles)
                .tauxValidation(tauxValidation)
                .tauxRejet(tauxRejet)
                .delaiMoyenTraitementJours(computeDelaiMoyen(factures))
                .lignesAcceptees(lignesAcceptees)
                .lignesRejetees(lignesRejetees)
                .facturesParStatut(facturesParStatut)
                .montantParStatut(montantParStatut)
                .lignesParRegime(lignesParRegime)
                .montantParRegime(montantParRegime)
                .parRegion(computeParRegion(factures))
                .topPharmacies(computeTopPharmacies(factures, 8))
                .topMedicaments(computeTopMedicaments(factures, 8))
                .build();
    }

    private static boolean estValidee(Facture f) {
        StatutFacture s = f.getStatut();
        return s == StatutFacture.VALIDEE_SR || s == StatutFacture.VALIDEE_NC || s == StatutFacture.PAYEE;
    }

    private static boolean estRejetee(Facture f) {
        StatutFacture s = f.getStatut();
        return s == StatutFacture.REJETEE_SR || s == StatutFacture.REJETEE_NC;
    }

    /** Délai moyen (en jours) entre l'envoi d'une facture et la 1ère décision du SR. */
    private double computeDelaiMoyen(List<Facture> factures) {
        List<Long> delaisHeures = new ArrayList<>();
        for (Facture f : factures) {
            List<HistoriqueAction> h = f.getHistorique();
            if (h == null || h.isEmpty()) continue;
            LocalDateTime envoi = h.stream()
                    .filter(a -> a.getStatut() == StatutFacture.ENVOYEE && a.getDate() != null)
                    .map(HistoriqueAction::getDate)
                    .min(LocalDateTime::compareTo).orElse(null);
            if (envoi == null) continue;
            LocalDateTime decision = h.stream()
                    .filter(a -> a.getDate() != null
                            && (a.getStatut() == StatutFacture.VALIDEE_SR || a.getStatut() == StatutFacture.REJETEE_SR)
                            && !a.getDate().isBefore(envoi))
                    .map(HistoriqueAction::getDate)
                    .min(LocalDateTime::compareTo).orElse(null);
            if (decision == null) continue;
            delaisHeures.add(Duration.between(envoi, decision).toHours());
        }
        if (delaisHeures.isEmpty()) return 0;
        double avgHours = delaisHeures.stream().mapToLong(Long::longValue).average().orElse(0);
        return round1(avgHours / 24.0);
    }

    /** Top médicaments agrégés sur toutes les lignes, triés par montant décroissant. */
    private List<MedicamentStat> computeTopMedicaments(List<Facture> factures, int limit) {
        Map<String, MedicamentStat> agg = new HashMap<>();
        for (Facture f : factures) {
            if (f.getLignes() == null) continue;
            for (LigneFacture l : f.getLignes()) {
                String nom = l.getMedicament();
                if (nom == null || nom.isBlank()) continue;
                MedicamentStat ms = agg.computeIfAbsent(nom.trim().toUpperCase(),
                        n -> MedicamentStat.builder().nom(n).quantite(0).montant(0).nombreLignes(0).build());
                ms.setQuantite(ms.getQuantite() + l.getQuantite());
                ms.setMontant(ms.getMontant() + l.getMontant());
                ms.setNombreLignes(ms.getNombreLignes() + 1);
            }
        }
        return agg.values().stream()
                .sorted((a, b) -> Double.compare(b.getMontant(), a.getMontant()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /** Top pharmacies par montant décroissant. */
    private List<PharmacieStat> computeTopPharmacies(List<Facture> factures, int limit) {
        Map<String, PharmacieStat> agg = new HashMap<>();
        for (Facture f : factures) {
            String id = f.getPharmacieId();
            if (id == null) continue;
            PharmacieStat ps = agg.computeIfAbsent(id,
                    k -> PharmacieStat.builder().id(k).nom(f.getPharmacieNom()).nombreFactures(0).montant(0).build());
            if (ps.getNom() == null && f.getPharmacieNom() != null) ps.setNom(f.getPharmacieNom());
            ps.setNombreFactures(ps.getNombreFactures() + 1);
            ps.setMontant(ps.getMontant() + f.getMontantTotal());
        }
        return agg.values().stream()
                .sorted((a, b) -> Double.compare(b.getMontant(), a.getMontant()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /** Activité par région avec nom résolu, triée par nombre de factures décroissant. */
    private List<RegionStat> computeParRegion(List<Facture> factures) {
        Map<String, String> nomsRegions = regionRepository.findAll().stream()
                .collect(Collectors.toMap(Region::getId, Region::getNom, (a, b) -> a));
        Map<String, RegionStat> agg = new HashMap<>();
        for (Facture f : factures) {
            String rid = f.getRegionId();
            if (rid == null) continue;
            RegionStat rs = agg.computeIfAbsent(rid,
                    k -> RegionStat.builder().id(k).nom(nomsRegions.getOrDefault(k, k)).nombreFactures(0).montant(0).build());
            rs.setNombreFactures(rs.getNombreFactures() + 1);
            rs.setMontant(rs.getMontant() + f.getMontantTotal());
        }
        return agg.values().stream()
                .sorted((a, b) -> Long.compare(b.getNombreFactures(), a.getNombreFactures()))
                .collect(Collectors.toList());
    }

    public List<MonthData> getEvolutionMensuelle(String regionId, String pharmacieId, int annee) {
        List<Facture> factures;
        if (pharmacieId != null && !pharmacieId.isEmpty()) {
            factures = factureRepository.findByPharmacieId(pharmacieId);
        } else if (regionId != null && !regionId.isEmpty()) {
            factures = factureRepository.findByRegionId(regionId);
        } else {
            factures = factureRepository.findAll();
        }

        Map<Integer, List<Facture>> byMonth = factures.stream()
                .filter(f -> f.getAnnee() == annee)
                .collect(Collectors.groupingBy(Facture::getMois));

        List<MonthData> evolution = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            List<Facture> facturesMois = byMonth.getOrDefault(i, new ArrayList<>());
            long count = facturesMois.size();
            double montant = facturesMois.stream().mapToDouble(Facture::getMontantTotal).sum();
            evolution.add(new MonthData(i, annee, count, montant));
        }
        return evolution;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
