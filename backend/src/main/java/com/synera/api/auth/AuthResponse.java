package com.synera.api.auth;

import java.util.UUID;

public record AuthResponse(
        UUID id,
        String fullName,
        String email,
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String deviceId) {
    public static AuthResponse from(AuthSession session) {
        AuthIdentity identity = session.identity();
        return new AuthResponse(identity.id(), identity.fullName(), identity.email(),
                session.accessToken(), session.refreshToken(), "Bearer", session.expiresIn(), session.deviceId());
    }
}
