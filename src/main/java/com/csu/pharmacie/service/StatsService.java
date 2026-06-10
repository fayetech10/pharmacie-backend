package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.MonthData;
import com.csu.pharmacie.dto.StatsDto;
import com.csu.pharmacie.entity.Facture;
import com.csu.pharmacie.repository.FactureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatsService {

    private final FactureRepository factureRepository;

    public StatsDto getStatsNational() {
        List<Facture> factures = factureRepository.findAll();
        return computeStats(factures);
    }

    public StatsDto getStatsRegional(String regionId) {
        List<Facture> factures = factureRepository.findByRegionId(regionId);
        return computeStats(factures);
    }

    private StatsDto computeStats(List<Facture> factures) {
        long nombreFactures = factures.size();
        double montantTotal = factures.stream().mapToDouble(Facture::getMontantTotal).sum();

        Map<String, Long> facturesParStatut = factures.stream()
                .collect(Collectors.groupingBy(f -> f.getStatut().name(), Collectors.counting()));

        Map<String, Long> facturesParRegion = factures.stream()
                .collect(Collectors.groupingBy(Facture::getRegionId, Collectors.counting()));

        return StatsDto.builder()
                .nombreFactures(nombreFactures)
                .montantTotal(montantTotal)
                .facturesParStatut(facturesParStatut)
                .facturesParRegion(facturesParRegion)
                .build();
    }

    public List<MonthData> getEvolutionMensuelle(String regionId, int annee) {
        List<Facture> factures = (regionId == null || regionId.isEmpty())
                ? factureRepository.findAll()
                : factureRepository.findByRegionId(regionId);

        List<Facture> facturesAnnee = factures.stream()
                .filter(f -> f.getAnnee() == annee)
                .collect(Collectors.toList());

        Map<Integer, List<Facture>> byMonth = facturesAnnee.stream()
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
}
