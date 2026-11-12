package com.synera.api.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class PermissionAuthorization {
    public boolean hasPermission(Authentication authentication, String permission) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("PERM_" + permission));
    }
}
