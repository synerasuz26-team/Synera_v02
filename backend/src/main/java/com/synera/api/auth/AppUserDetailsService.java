package com.synera.api.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AppUserDetailsService implements UserDetailsService {
    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        AppUser user = users.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
        return new AuthenticatedUser(user);
    }
}
