package com.synera.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AuthorizationPolicyTest {
    private final RoleAssignmentPolicy roleAssignmentPolicy = new RoleAssignmentPolicy();

    @Test
    void tenantScopeComesOnlyFromTheVerifiedTokenClaims() {
        UUID userId = UUID.randomUUID();
        UUID tokenTenantId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("tenant_id", tokenTenantId.toString())
                .claim("permission_scopes", Map.of("order_so:view", "A"))
                .build();

        ResolvedScope scope = new PermissionScopeResolver(mock(JdbcTemplate.class))
                .resolveScope(jwt, "order_so:view");

        assertEquals(tokenTenantId, scope.tenantId());
        assertTrue(scope.hasRows());
    }

    @Test
    void aScopeWithNoAssignedOrganizationOrRoutesFailsClosed() {
        UUID tenantId = UUID.randomUUID();
        Jwt regional = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("permission_scopes", Map.of("order_so:view", "R"))
                .claim("org_unit_ids", java.util.List.of())
                .build();
        Jwt routeScoped = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("permission_scopes", Map.of("visit:view", "T"))
                .claim("route_ids", java.util.List.of())
                .build();

        PermissionScopeResolver resolver = new PermissionScopeResolver(mock(JdbcTemplate.class));
        assertFalse(resolver.resolveScope(regional, "order_so:view").hasRows());
        assertFalse(resolver.resolveScope(routeScoped, "visit:view").hasRows());
    }

    @Test
    void scopeResolutionRejectsPermissionsNotGrantedInTheToken() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", UUID.randomUUID().toString())
                .claim("permission_scopes", Map.of())
                .build();

        assertThrows(AccessDeniedException.class,
                () -> new PermissionScopeResolver(mock(JdbcTemplate.class)).resolveScope(jwt, "order_si:view"));
    }

    @Test
    void roleAssignmentRejectsSelfCrossTenantCrossTypeAndHigherLevelGrants() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();

        roleAssignmentPolicy.validate(actorId, targetId, tenantId, tenantId, "NPP", "NPP", 100, 20);
        assertThrows(AccessDeniedException.class,
                () -> roleAssignmentPolicy.validate(actorId, actorId, tenantId, tenantId, "NPP", "NPP", 100, 20));
        assertThrows(AccessDeniedException.class,
                () -> roleAssignmentPolicy.validate(actorId, targetId, tenantId, otherTenantId, "NPP", "NPP", 100, 20));
        assertThrows(AccessDeniedException.class,
                () -> roleAssignmentPolicy.validate(actorId, targetId, tenantId, tenantId, "NPP", "NSX", 100, 20));
        assertThrows(AccessDeniedException.class,
                () -> roleAssignmentPolicy.validate(actorId, targetId, tenantId, tenantId, "NPP", "NPP", 20, 100));
    }
}
