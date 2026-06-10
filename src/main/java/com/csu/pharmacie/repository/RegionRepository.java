package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.Region;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RegionRepository extends MongoRepository<Region, String> {
    Optional<Region> findByCode(String code);
}
