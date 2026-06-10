package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.MonthData;
import com.csu.pharmacie.dto.StatsDto;
import com.csu.pharmacie.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/national")
    public ResponseEntity<StatsDto> getStatsNational() {
        return ResponseEntity.ok(statsService.getStatsNational());
    }

    @GetMapping("/regional")
    public ResponseEntity<StatsDto> getStatsRegional(@RequestParam String regionId) {
        return ResponseEntity.ok(statsService.getStatsRegional(regionId));
    }

    @GetMapping("/evolution")
    public ResponseEntity<List<MonthData>> getEvolutionMensuelle(
            @RequestParam(required = false) String regionId, 
            @RequestParam int annee) {
        return ResponseEntity.ok(statsService.getEvolutionMensuelle(regionId, annee));
    }
}
