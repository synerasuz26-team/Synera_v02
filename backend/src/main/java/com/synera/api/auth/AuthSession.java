package com.synera.api.auth;

public record AuthSession(
        AuthIdentity identity,
        String accessToken,
        String refreshToken,
        String deviceId,
        long expiresIn) {
}
