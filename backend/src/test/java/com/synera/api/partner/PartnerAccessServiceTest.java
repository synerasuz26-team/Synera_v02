package com.synera.api.partner;

import com.synera.api.organization.BusinessAccessService;
import com.synera.api.security.ResolvedScope;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PartnerAccessServiceTest {
    private final BusinessAccessService business = mock(BusinessAccessService.class);
    private final PartnerAccessService access = new PartnerAccessService(mock(JdbcTemplate.class), business);

    @Test
    void routeScopedOutletFilterRestrictsTenantAndAssignedRoutes() {
        UUID tenantId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        Jwt jwt = jwt(tenantId);
        when(business.businessToday()).thenReturn(LocalDate.of(2026, 11, 12));
        when(business.resolve(jwt, "outlet:view")).thenReturn(
                new ResolvedScope("T", tenantId, UUID.randomUUID(), Set.of(), List.of(), Set.of(routeId)));

        PartnerAccessService.Filter filter = access.outlets(jwt,
                new BusinessAccessService.Actor(UUID.randomUUID(), tenantId, "NSX"), "outlet:view", "o");

        assertTrue(filter.sql().contains("o.created_by_tenant_id = ? OR EXISTS"));
        assertTrue(filter.sql().contains("ro.tenant_id = ? AND ro.outlet_id = o.id"));
        assertTrue(filter.sql().contains("ro.route_id IN (?)"));
        assertEquals(10, filter.arguments().size());
        assertEquals(tenantId, filter.arguments().get(0));
        assertEquals(routeId, filter.arguments().get(7));
    }

    @Test
    void distributorOutletFilterIsLimitedToItsOwnActiveAndPendingAssignments() {
        UUID tenantId = UUID.randomUUID();
        Jwt jwt = jwt(tenantId);
        LocalDate today = LocalDate.of(2026, 11, 12);
        when(business.businessToday()).thenReturn(today);

        PartnerAccessService.Filter filter = access.outlets(jwt,
                new BusinessAccessService.Actor(UUID.randomUUID(), tenantId, "NPP"), "outlet:view", "o");

        assertTrue(filter.sql().contains("onp.npp_tenant_id = ?"));
        assertTrue(filter.sql().contains("onp.status IN ('ACTIVE', 'PENDING')"));
        assertEquals(List.of(tenantId, today, today), filter.arguments());
    }

    private Jwt jwt(UUID tenantId) {
        return Jwt.withTokenValue("token").header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("permission_scopes", Map.of())
                .build();
    }
}
