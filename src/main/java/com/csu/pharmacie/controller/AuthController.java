package com.csu.pharmacie.controller;

import com.csu.pharmacie.dto.LoginRequest;
import com.csu.pharmacie.dto.LoginResponse;
import com.csu.pharmacie.dto.RegisterRequest;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/register")
    public ResponseEntity<User> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }
}
