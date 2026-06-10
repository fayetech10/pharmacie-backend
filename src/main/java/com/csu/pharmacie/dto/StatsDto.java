package com.csu.pharmacie.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class StatsDto {
    private long nombreFactures;
    private double montantTotal;
    private Map<String, Long> facturesParStatut;
    private Map<String, Long> facturesParRegion;
    private List<MonthData> evolutionMensuelle;
}
