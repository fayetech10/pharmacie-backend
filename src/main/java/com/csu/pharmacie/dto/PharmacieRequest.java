package com.csu.pharmacie.dto;

import lombok.Data;

@Data
public class PharmacieRequest {
    private String code;
    private String nom;
    private String adresse;
    private String telephone;
    private String email;
    private String regionId;
    private String password;
}
