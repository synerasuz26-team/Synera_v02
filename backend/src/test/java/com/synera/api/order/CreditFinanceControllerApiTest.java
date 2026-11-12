package com.synera.api.order;

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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CreditFinanceControllerApiTest {
    private final CreditFinanceService finance = mock(CreditFinanceService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new CreditFinanceController(finance))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void paymentDeclarationDelegatesValidatedPaymentToFinanceService() throws Exception {
        UUID userId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(userId.toString()).claim("tenant_id", UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt, null, AuthorityUtils.NO_AUTHORITIES));
        CreditFinanceService.DeclarePaymentRequest request =
                new CreditFinanceService.DeclarePaymentRequest(null, null, 125000L, "BANK-001", "Đã chuyển");
        when(finance.declarePayment(eq(jwt), any(CreditFinanceService.DeclarePaymentRequest.class), any()))
                .thenReturn(null);

        mvc.perform(post("/api/v1/payments")
                        .contentType("application/json")
                        .content("""
                                {
                                  "amountVnd": 125000,
                                  "referenceCode": "BANK-001",
                                  "reason": "Đã chuyển"
                                }
                                """))
                .andExpect(status().isOk());

        verify(finance).declarePayment(eq(jwt), eq(request), any());
    }
}
