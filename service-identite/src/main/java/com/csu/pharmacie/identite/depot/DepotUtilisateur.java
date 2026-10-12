package com.csu.pharmacie.identite.depot;

import org.springframework.data.jpa.repository.JpaRepository;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.User;

import java.util.List;
import java.util.Optional;

public interface DepotUtilisateur extends JpaRepository<User, String> {

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    List<User> findByRole(Role role);

    List<User> findByStructureSanitaireId(String structureSanitaireId);

    List<User> findByRegionId(String regionId);

    List<User> findByDistrictId(String districtId);
}
