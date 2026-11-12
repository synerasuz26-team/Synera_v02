package com.synera.api.order;

import com.synera.api.catalog.EffectivePricingService;
import com.synera.api.organization.BusinessAccessService;
import com.synera.api.partner.PartnerAccessService;
import com.synera.api.security.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SellInOrderServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BusinessAccessService access = mock(BusinessAccessService.class);
    private final PartnerAccessService partnerAccess = mock(PartnerAccessService.class);
    private final EffectivePricingService pricing = mock(EffectivePricingService.class);
    private final OrderFlowService orderFlow = mock(OrderFlowService.class);
    private final AuditService audit = mock(AuditService.class);
    private final SellInOrderService service = new SellInOrderService(
            jdbc, access, partnerAccess, pricing, orderFlow, audit);

    @Test
    void onlyNppActorsCanCreateSellInOrders() {
        UUID userId = UUID.randomUUID();
        UUID nsxId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(userId.toString()).build();
        when(access.actor(jwt)).thenReturn(new BusinessAccessService.Actor(
                userId, UUID.randomUUID(), "NSX"));
        SellInOrderService.CreateRequest request = new SellInOrderService.CreateRequest(
                nsxId, List.of(new EffectivePricingService.CalculationLine(
                UUID.randomUUID(), "THUNG", 1)), "", "Tạo đơn", true);

        assertThrows(AccessDeniedException.class,
                () -> service.create(jwt, "request-1", request, mock(HttpServletRequest.class)));

        verifyNoInteractions(jdbc, partnerAccess, pricing, orderFlow, audit);
    }
}
