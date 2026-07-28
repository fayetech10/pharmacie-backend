package com.csu.pharmacie.repository;

import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmail(String email);

    /** Identifiant de connexion alternatif attribué à l'import (pharmacies, structures). */
    Optional<User> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    List<User> findByRole(Role role);
    List<User> findByRoleAndRegionId(Role role, String regionId);
    List<User> findByPharmacieId(String pharmacieId);
    List<User> findByRegionId(String regionId);
    /** Utilisateurs rattachés à une structure sanitaire (agents BCSU + compte structure). */
    List<User> findByStructureSanitaireId(String structureSanitaireId);
}
