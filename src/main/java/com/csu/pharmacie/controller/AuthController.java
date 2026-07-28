package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.ChangePasswordRequest;
import com.csu.pharmacie.dto.LoginRequest;
import com.csu.pharmacie.dto.LoginResponse;
import com.csu.pharmacie.dto.RegisterRequest;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/register")
    public ResponseEntity<User> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    /** Changement de mot de passe de l'utilisateur connecté (imposé à la première connexion). */
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ResponseEntity.noContent().build();
    }
}
