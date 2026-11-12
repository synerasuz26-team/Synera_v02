package com.synera.api.order;

import com.synera.api.catalog.EffectivePricingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellOutOrderControllerApiTest {
    private final SellOutOrderService orders = mock(SellOutOrderService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SellOutOrderController(orders))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPassesIdempotencyKeyAndServerPricedLinesToService() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID nppId = UUID.randomUUID();
        UUID outletId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(userId.toString()).claim("tenant_id", tenantId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt, null, AuthorityUtils.NO_AUTHORITIES));
        SellOutOrderService.OrderCreateRequest request = new SellOutOrderService.OrderCreateRequest(
                nppId, outletId, List.of(new EffectivePricingService.CalculationLine(
                productId, "THUNG", 2)), null, null, true, "Tạo đơn");
        when(orders.create(org.mockito.ArgumentMatchers.eq(jwt),
                org.mockito.ArgumentMatchers.eq("mobile-01"),
                org.mockito.ArgumentMatchers.any(SellOutOrderService.OrderCreateRequest.class),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SellOutOrderService.OrderDetail(
                        UUID.randomUUID(), nppId, nppId, outletId, "SO-TEST", "SUBMITTED", userId,
                        1, 200, 0, 200, "", null, OffsetDateTime.now(), null, OffsetDateTime.now(),
                        OffsetDateTime.now(), List.of(), List.of(), List.of(), List.of()));

        mvc.perform(post("/api/v1/orders/sell-out")
                        .header("Idempotency-Key", "mobile-01")
                        .contentType("application/json")
                        .content("""
                                {
                                  "nppTenantId": "%s",
                                  "outletId": "%s",
                                  "lines": [{"productId": "%s", "unitCode": "THUNG", "quantity": 2}],
                                  "submit": true,
                                  "reason": "Tạo đơn"
                                }
                                """.formatted(nppId, outletId, productId)))
                .andExpect(status().isOk());

        verify(orders).create(org.mockito.ArgumentMatchers.eq(jwt),
                org.mockito.ArgumentMatchers.eq("mobile-01"),
                org.mockito.ArgumentMatchers.eq(request),
                org.mockito.ArgumentMatchers.any());
    }
}
