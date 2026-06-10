package com.csu.pharmacie.dto;

import com.csu.pharmacie.entity.Role;
import lombok.Data;

@Data
public class UserRequest {
    private String nom;
    private String prenom;
    private String email;
    private String password;
    private Role role;
    private String pharmacieId;
    private String regionId;
}
