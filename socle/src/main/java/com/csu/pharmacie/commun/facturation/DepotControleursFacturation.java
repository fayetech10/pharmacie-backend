package com.csu.pharmacie.commun.facturation;

import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Comptes contrôleurs disponibles pour l'affectation automatique des factures. */
public interface DepotControleursFacturation extends JpaRepository<User, String> {

    List<User> findByRoleAndRegionId(Role role, String regionId);
}
