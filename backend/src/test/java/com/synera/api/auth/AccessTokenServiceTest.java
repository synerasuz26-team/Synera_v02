package com.synera.api.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessTokenServiceTest {
    @Test
    void signsRequiredIdentityScopeAndDeviceClaimsForFifteenMinutes() {
        byte[] keyBytes = "a-secret-key-that-is-at-least-thirty-two-bytes".getBytes(StandardCharsets.UTF_8);
        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        AccessTokenService service = new AccessTokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key)), clock);
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        AuthIdentity identity = new AuthIdentity(
                userId, "Nguyen Van A", "a@example.com", "hash", "ACTIVE",
                tenantId, "NPP", "NPP_A", "NPP A", "ACTIVE",
                List.of(new AuthIdentity.Role("NPP_OWNER", "Owner", 100)),
                Map.of("order_so:view", "A"),
                List.of(unitId),
                List.of(routeId));

        AccessTokenService.Token token = service.issue(identity, "mobile-device");
        JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        Jwt decoded = decoder.decode(token.value());

        assertEquals(userId.toString(), decoded.getSubject());
        assertEquals(tenantId.toString(), decoded.getClaimAsString("tenant_id"));
        assertEquals("NPP", decoded.getClaimAsString("tenant_type"));
        assertEquals(List.of("NPP_OWNER"), decoded.getClaimAsStringList("roles"));
        assertEquals(List.of(unitId.toString()), decoded.getClaimAsStringList("org_unit_ids"));
        assertEquals(List.of(routeId.toString()), decoded.getClaimAsStringList("route_ids"));
        assertEquals("A", decoded.getClaimAsMap("permission_scopes").get("order_so:view"));
        assertEquals("mobile-device", decoded.getClaimAsString("device_id"));
        assertEquals(Duration.ofMinutes(15).toSeconds(), token.expiresIn());
    }
}
