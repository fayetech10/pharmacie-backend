package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.Pharmacie;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PharmacieRepository extends MongoRepository<Pharmacie, String> {
    List<Pharmacie> findByRegionId(String regionId);
    Optional<Pharmacie> findByCode(String code);
}
