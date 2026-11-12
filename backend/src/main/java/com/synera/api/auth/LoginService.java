package com.synera.api.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

@Service
public class LoginService {
    private static final String DUMMY_PASSWORD_HASH =
            "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final AuthIdentityRepository identities;
    private final MigratingPasswordEncoder passwordEncoder;
    private final LoginAttemptService attempts;
    private final RefreshTokenService refreshTokens;
    private final JdbcTemplate jdbc;

    public LoginService(
            AuthIdentityRepository identities,
            MigratingPasswordEncoder passwordEncoder,
            LoginAttemptService attempts,
            RefreshTokenService refreshTokens,
            JdbcTemplate jdbc) {
        this.identities = identities;
        this.passwordEncoder = passwordEncoder;
        this.attempts = attempts;
        this.refreshTokens = refreshTokens;
        this.jdbc = jdbc;
    }

    public AuthSession login(String email, String password, String requestedDeviceId) {
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        if (attempts.isLocked(normalizedEmail)) {
            throw invalidCredentials();
        }

        AuthIdentity identity = identities.findByEmail(normalizedEmail).orElse(null);
        if (identity == null) {
            passwordEncoder.matches(password, DUMMY_PASSWORD_HASH);
            attempts.recordFailure(normalizedEmail);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(password, identity.passwordHash())
                || !identity.userStatus().equals("ACTIVE")
                || !identity.tenantStatus().equals("ACTIVE")
                || identity.roles().isEmpty()) {
            attempts.recordFailure(normalizedEmail);
            throw invalidCredentials();
        }

        if (passwordEncoder.upgradeEncoding(identity.passwordHash())
                && passwordEncoder.canEncode(password)) {
            jdbc.update("UPDATE app_users SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    passwordEncoder.encode(password), identity.id());
        }
        String deviceId = requestedDeviceId == null || requestedDeviceId.isBlank()
                ? UUID.randomUUID().toString()
                : requestedDeviceId.strip();
        if (deviceId.length() > 128 || deviceId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Mã thiết bị không hợp lệ.");
        }
        attempts.reset(normalizedEmail);
        return refreshTokens.createSession(identity, deviceId);
    }

    private static BadCredentialsException invalidCredentials() {
        return new BadCredentialsException("Email hoặc mật khẩu không đúng.");
    }
}
