package com.synera.api.organization;

import com.synera.api.security.PermissionScopeResolver;
import com.synera.api.security.ResolvedScope;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BusinessAccessServiceTest {
    private final BusinessAccessService access = new BusinessAccessService(
            mock(JdbcTemplate.class), mock(PermissionScopeResolver.class));

    @Test
    void ownerVisitFilterAlwaysRestrictsToTenantAndOwner() {
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        BusinessAccessService.Filter filter = access.visitFilter(
                new ResolvedScope("O", tenantId, ownerId, Set.of(), List.of(), Set.of()), "v");

        assertEquals("v.tenant_id = ? AND v.sales_user_id = ?", filter.sql());
        assertEquals(List.of(tenantId, ownerId), filter.arguments());
    }

    @Test
    void routeVisitFilterAlwaysRestrictsToTenantAndAssignedRoutes() {
        UUID tenantId = UUID.randomUUID();
        UUID firstRoute = UUID.randomUUID();
        UUID secondRoute = UUID.randomUUID();
        BusinessAccessService.Filter filter = access.visitFilter(
                new ResolvedScope("T", tenantId, null, Set.of(), List.of(),
                        Set.of(firstRoute, secondRoute)), "v");

        assertTrue(filter.sql().contains("v.tenant_id = ? AND v.route_id IN (?,?)"));
        assertEquals(tenantId, filter.arguments().get(0));
        assertTrue(filter.arguments().containsAll(List.of(firstRoute, secondRoute)));
    }

    @Test
    void routeScopeWithoutAssignmentsFailsClosed() {
        UUID tenantId = UUID.randomUUID();
        BusinessAccessService.Filter filter = access.visitFilter(
                new ResolvedScope("T", tenantId, null, Set.of(), List.of(), Set.of()), "v");

        assertEquals("v.tenant_id = ? AND FALSE", filter.sql());
        assertEquals(List.of(tenantId), filter.arguments());
    }
}
