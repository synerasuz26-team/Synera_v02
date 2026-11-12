package com.synera.api.auth;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class AccessTokenService {
    private static final Duration LIFETIME = Duration.ofMinutes(15);

    private final JwtEncoder encoder;
    private final Clock clock;

    public AccessTokenService(JwtEncoder encoder, Clock clock) {
        this.encoder = encoder;
        this.clock = clock;
    }

    public Token issue(AuthIdentity identity, String deviceId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(LIFETIME);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("synera-api")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(identity.id().toString())
                .claim("tenant_id", identity.tenantId().toString())
                .claim("tenant_type", identity.tenantType())
                .claim("roles", identity.roleCodes())
                .claim("org_unit_ids", ids(identity.orgUnitIds()))
                .claim("route_ids", ids(identity.routeIds()))
                .claim("permission_scopes", identity.permissionScopes())
                .claim("device_id", deviceId)
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Token(value, LIFETIME.toSeconds());
    }

    private static List<String> ids(List<java.util.UUID> ids) {
        return ids.stream().map(java.util.UUID::toString).toList();
    }

    public record Token(String value, long expiresIn) {
    }
}
