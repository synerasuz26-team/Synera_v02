package com.synera.api.auth;

import jakarta.validation.constraints.Size;

public record LogoutRequest(@Size(max = 128) String refreshToken, @Size(max = 128) String deviceId) {
}
