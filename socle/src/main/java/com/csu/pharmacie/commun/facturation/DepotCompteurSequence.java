package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.entity.CompteurSequence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepotCompteurSequence extends JpaRepository<CompteurSequence, String> {
}
