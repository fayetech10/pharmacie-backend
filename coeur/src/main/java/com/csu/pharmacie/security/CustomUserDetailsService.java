package com.csu.pharmacie.security;

import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * L'identifiant saisi peut être l'email OU le username attribué à l'import.
     * Le principal renvoyé reste TOUJOURS l'email : c'est lui qui sert de sujet au JWT
     * et de clé dans tous les getCurrentUser() du projet (qui font findByEmail).
     */
    @Override
    public UserDetails loadUserByUsername(String identifiant) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(identifiant)
                .or(() -> userRepository.findByUsernameIgnoreCase(identifiant))
                .orElseThrow(() -> new UsernameNotFoundException("Utilisateur non trouvé : " + identifiant));

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                user.isActif(),
                true,
                true,
                true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }
}
