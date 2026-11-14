package com.synera.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private final JdbcTemplate jdbc;
    private final AuthIdentityRepository identities;
    private final AccessTokenService accessTokens;
    private final Clock clock;
    private final Duration lifetime;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            JdbcTemplate jdbc,
            AuthIdentityRepository identities,
            AccessTokenService accessTokens,
            @Value("${app.auth.refresh-token-days:30}") long refreshTokenDays,
            Clock clock) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.accessTokens = accessTokens;
        this.clock = clock;
        this.lifetime = Duration.ofDays(refreshTokenDays);
    }

    @Transactional
    public AuthSession createSession(AuthIdentity identity, String deviceId) {
        UUID familyId = UUID.randomUUID();
        String rawToken = newRefreshToken();
        insert(identity, familyId, deviceId, rawToken, null, null);
        AccessTokenService.Token accessToken = accessTokens.issue(identity, deviceId);
        return new AuthSession(identity, accessToken.value(), rawToken, deviceId, accessToken.expiresIn());
    }

    @Transactional(noRollbackFor = {InvalidRefreshTokenException.class, RefreshTokenReuseException.class})
    public AuthSession rotate(String rawToken, String requestedDeviceId) {
        List<TokenRecord> matches = jdbc.query("""
                SELECT id, tenant_id, user_id, token_hash, expires_at, revoked_at,
                       family_id, device_id, replaced_by
                FROM refresh_token
                WHERE token_hash = ?
                FOR UPDATE
                """, this::mapToken, hash(rawToken));
        if (matches.isEmpty()) {
            throw new InvalidRefreshTokenException();
        }
        TokenRecord current = matches.get(0);
        if (current.revokedAt() != null) {
            revokeFamily(current.familyId());
            throw new RefreshTokenReuseException();
        }
        if (current.expiresAt().isBefore(clock.instant())) {
            revokeFamily(current.familyId());
            throw new InvalidRefreshTokenException();
        }
        if (requestedDeviceId != null && !requestedDeviceId.isBlank()
                && !current.deviceId().equals(requestedDeviceId.strip())) {
            throw new InvalidRefreshTokenException();
        }
        AuthIdentity identity = identities.findById(current.userId())
                .filter(user -> user.userStatus().equals("ACTIVE") && user.tenantStatus().equals("ACTIVE"))
                .orElseThrow(() -> {
                    revokeFamily(current.familyId());
                    return new InvalidRefreshTokenException();
                });
        String replacement = newRefreshToken();
        UUID replacementId = UUID.randomUUID();
        insert(identity, current.familyId(), current.deviceId(), replacement, current.id(), replacementId);
        jdbc.update("""
                UPDATE refresh_token SET revoked_at = ?, replaced_by = ?
                WHERE id = ? AND revoked_at IS NULL
                """, java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC), replacementId, current.id());
        AccessTokenService.Token accessToken = accessTokens.issue(identity, current.deviceId());
        return new AuthSession(identity, accessToken.value(), replacement, current.deviceId(),
                accessToken.expiresIn());
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public void logout(String rawToken, UUID userId, String deviceId) {
        if (rawToken != null && !rawToken.isBlank()) {
            List<UUID> families = jdbc.query("""
                    SELECT family_id FROM refresh_token WHERE token_hash = ? FOR UPDATE
                    """, (rs, row) -> rs.getObject(1, UUID.class), hash(rawToken));
            if (!families.isEmpty()) {
                jdbc.update("UPDATE push_device SET active=false,updated_at=CURRENT_TIMESTAMP WHERE (user_id,device_id) IN(SELECT user_id,device_id FROM refresh_token WHERE family_id=?)",families.get(0));
                revokeFamily(families.get(0));
                return;
            }
        }
        if (userId != null && deviceId != null && !deviceId.isBlank()) {
            jdbc.update("UPDATE push_device SET active=false,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND device_id=?",userId,deviceId);
            jdbc.update("""
                    UPDATE refresh_token SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP)
                    WHERE user_id = ? AND device_id = ?
                    """, userId, deviceId);
        }
    }

    private void insert(
            AuthIdentity identity,
            UUID familyId,
            String deviceId,
            String rawToken,
            UUID rotatedFrom,
            UUID preferredId) {
        UUID id = preferredId == null ? UUID.randomUUID() : preferredId;
        jdbc.update("""
                INSERT INTO refresh_token (
                    id, tenant_id, user_id, token_hash, expires_at, family_id, device_id, rotated_from
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, identity.tenantId(), identity.id(), hash(rawToken),
                java.time.OffsetDateTime.ofInstant(clock.instant().plus(lifetime), java.time.ZoneOffset.UTC), familyId, deviceId, rotatedFrom);
    }

    private void revokeFamily(UUID familyId) {
        jdbc.update("""
                UPDATE refresh_token SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP)
                WHERE family_id = ?
                """, familyId);
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Không hỗ trợ thuật toán băm SHA-256.", exception);
        }
    }

    private TokenRecord mapToken(ResultSet rs, int rowNum) throws SQLException {
        return new TokenRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                rs.getObject("family_id", UUID.class),
                rs.getString("device_id"));
    }

    private record TokenRecord(
            UUID id,
            UUID tenantId,
            UUID userId,
            Instant expiresAt,
            Instant revokedAt,
            UUID familyId,
            String deviceId) {
    }
}
