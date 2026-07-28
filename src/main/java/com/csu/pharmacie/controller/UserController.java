package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.UserRequest;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    /**
     * Agents BCSU sélectionnables dans le formulaire des structures sanitaires.
     * Accessible au Service Régional (limité à sa région) en plus de l'Admin,
     * contrairement à la liste complète des utilisateurs (réservée à l'Admin).
     */
    @GetMapping("/bcsu")
    public ResponseEntity<List<User>> getBcsuUsers() {
        return ResponseEntity.ok(userService.getBcsuForCurrentUser());
    }

    /** Cachet et signature d'un agent (affichés sur ses lettres de garantie). */
    @GetMapping("/{id}/parametrage")
    public ResponseEntity<java.util.Map<String, String>> getParametrage(@PathVariable String id) {
        return ResponseEntity.ok(userService.getParametrage(id));
    }

    /** Met à jour le cachet et la signature de l'utilisateur courant (paramétrage BCSU). */
    @PutMapping("/parametrage")
    public ResponseEntity<java.util.Map<String, String>> updateParametrage(
            @RequestBody java.util.Map<String, String> request) {
        return ResponseEntity.ok(userService.updateParametrage(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable String id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @PostMapping
    public ResponseEntity<User> createUser(@Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(userService.createUser(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> updateUser(@PathVariable String id, @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        userService.deleteUser(id);
        return ResponseEntity.ok().build();
    }
}
