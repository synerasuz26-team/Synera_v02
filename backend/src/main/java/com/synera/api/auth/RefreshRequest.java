package com.synera.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(
        @NotBlank @Size(max = 128) String refreshToken,
        @Size(max = 128) String deviceId) {
}
