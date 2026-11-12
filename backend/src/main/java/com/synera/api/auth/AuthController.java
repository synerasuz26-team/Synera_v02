package com.synera.api.auth;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping({"/api/auth", "/api/v1/auth"})
public class AuthController {
    private final LoginService loginService;
    private final RefreshTokenService refreshTokens;
    private final AuthIdentityRepository identities;

    public AuthController(
            LoginService loginService,
            RefreshTokenService refreshTokens,
            AuthIdentityRepository identities) {
        this.loginService = loginService;
        this.refreshTokens = refreshTokens;
        this.identities = identities;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf() {
        return new CsrfResponse("stateless");
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register() {
        throw new RegistrationDisabledException();
    }

    @PostMapping("/login")
    public AuthResponse login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader) {
        String deviceId = request.deviceId() == null || request.deviceId().isBlank()
                ? deviceHeader
                : request.deviceId();
        return AuthResponse.from(loginService.login(request.email(), request.password(), deviceId));
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return AuthResponse.from(refreshTokens.rotate(request.refreshToken(), request.deviceId()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Valid @RequestBody(required = false) LogoutRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = jwt == null ? null : UUID.fromString(jwt.getSubject());
        String deviceId = request != null && request.deviceId() != null
                ? request.deviceId()
                : jwt == null ? null : jwt.getClaimAsString("device_id");
        String refreshToken = request == null ? null : request.refreshToken();
        refreshTokens.logout(refreshToken, userId, deviceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AuthMeResponse currentUser(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        AuthIdentity identity = identities.findById(userId)
                .filter(user -> user.userStatus().equals("ACTIVE") && user.tenantStatus().equals("ACTIVE"))
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException(
                        "Tài khoản không còn hiệu lực."));
        return AuthMeResponse.from(identity);
    }

    public record CsrfResponse(String token) {
    }
}
