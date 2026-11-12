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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellInOrderControllerApiTest {
    private final SellInOrderService orders = mock(SellInOrderService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SellInOrderController(orders))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPassesIdempotencyKeyAndRequestToSellInService() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID nsxId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(userId.toString()).claim("tenant_id", UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt, null, AuthorityUtils.NO_AUTHORITIES));
        SellInOrderService.CreateRequest request = new SellInOrderService.CreateRequest(
                nsxId, List.of(new EffectivePricingService.CalculationLine(productId, "THUNG", 2)),
                "Đặt hàng", "Tạo đơn", true);
        when(orders.create(eq(jwt), eq("npp-req-01"),
                any(SellInOrderService.CreateRequest.class), any())).thenReturn(null);

        mvc.perform(post("/api/v1/orders/sell-in")
                        .header("Idempotency-Key", "npp-req-01")
                        .contentType("application/json")
                        .content("""
                                {
                                  "nsxTenantId": "%s",
                                  "lines": [{"productId": "%s", "unitCode": "THUNG", "quantity": 2}],
                                  "note": "Đặt hàng",
                                  "reason": "Tạo đơn",
                                  "submit": true
                                }
                                """.formatted(nsxId, productId)))
                .andExpect(status().isOk());

        verify(orders).create(eq(jwt), eq("npp-req-01"), eq(request), any());
    }
}
