package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmail(String email);
    List<User> findByRole(Role role);
    List<User> findByPharmacieId(String pharmacieId);
}
