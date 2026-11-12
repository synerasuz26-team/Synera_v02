package com.synera.api.catalog;

import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.CatalogAccessService.Actor;
import com.synera.api.security.PermissionScopeResolver;
import com.synera.api.security.ResolvedScope;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScopedCatalogWorkflowPolicyTest {
    private final CatalogAccessService access = mock(CatalogAccessService.class);
    private final CatalogScopeService catalogScopes = mock(CatalogScopeService.class);
    private final PermissionScopeResolver permissionScopes = mock(PermissionScopeResolver.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ScopedCatalogWorkflowPolicy policy =
            new ScopedCatalogWorkflowPolicy(access, catalogScopes, permissionScopes, jdbc);

    @Test
    void routeScopedWorkflowRequiresNppAndOutletContext() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Jwt jwt = jwt(userId, tenantId);
        when(access.actor(jwt)).thenReturn(new Actor(userId, tenantId, "NSX"));
        when(permissionScopes.resolveScope(jwt, "promotion:approve"))
                .thenReturn(new ResolvedScope("T", tenantId, userId, Set.of(), List.of(), Set.of(UUID.randomUUID())));

        ApiProblem problem = assertThrows(ApiProblem.class, () -> policy.requireRecordAccess(
                jwt, "promotion:approve", "promotion_scope", "promotion_id",
                tenantId, UUID.randomUUID(), null, null));

        assertEquals("OUTLET_CONTEXT_REQUIRED", problem.code());
    }

    @Test
    void routeScopedWorkflowChecksRequestedAndStoredOutletScope() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        UUID nppId = UUID.randomUUID();
        UUID outletId = UUID.randomUUID();
        Jwt jwt = jwt(userId, tenantId);
        when(access.actor(jwt)).thenReturn(new Actor(userId, tenantId, "NSX"));
        when(permissionScopes.resolveScope(jwt, "promotion:approve"))
                .thenReturn(new ResolvedScope("T", tenantId, userId, Set.of(), List.of(), Set.of(UUID.randomUUID())));
        when(catalogScopes.list("promotion_scope", "promotion_id", tenantId, recordId))
                .thenReturn(List.of(new CatalogScopeService.CatalogScope("OUTLET", outletId)));
        when(catalogScopes.matches(eq("promotion_scope"), eq("promotion_id"), eq(tenantId),
                eq(recordId), eq(nppId), eq(outletId), any()))
                .thenReturn(true);

        assertDoesNotThrow(() -> policy.requireRecordAccess(
                jwt, "promotion:approve", "promotion_scope", "promotion_id",
                tenantId, recordId, nppId, outletId));

        verify(access).requireScopedOutlet(jwt, "promotion:approve", outletId);
    }

    private Jwt jwt(UUID userId, UUID tenantId) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("tenant_id", tenantId.toString())
                .build();
    }
}
