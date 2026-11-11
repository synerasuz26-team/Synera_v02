package com.synera.api.auth;

import java.util.UUID;

public record AuthResponse(UUID id, String fullName, String email) {
    public static AuthResponse from(AuthenticatedUser user) {
        return new AuthResponse(user.getId(), user.getFullName(), user.getUsername());
    }
}
