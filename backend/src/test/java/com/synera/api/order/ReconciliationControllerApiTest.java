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

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReconciliationControllerApiTest {
    private final ReconciliationService reconciliation = mock(ReconciliationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ReconciliationController(reconciliation))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void closePeriodDelegatesVersionedRequestToService() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(userId.toString()).claim("tenant_id", UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt, null, AuthorityUtils.NO_AUTHORITIES));
        ReconciliationService.ActionRequest request =
                new ReconciliationService.ActionRequest(4, "Đã đối soát đầy đủ");
        when(reconciliation.closePeriod(eq(jwt), eq(periodId), eq(request), any())).thenReturn(null);

        mvc.perform(post("/api/v1/periods/{periodId}/close", periodId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "version": 4,
                                  "reason": "Đã đối soát đầy đủ"
                                }
                                """))
                .andExpect(status().isOk());

        verify(reconciliation).closePeriod(eq(jwt), eq(periodId), eq(request), any());
    }

    @Test
    void periodCreationCarriesCalendarRangeToService() throws Exception {
        UUID userId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(userId.toString()).claim("tenant_id", UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt, null, AuthorityUtils.NO_AUTHORITIES));
        ReconciliationService.CreatePeriodRequest request =
                new ReconciliationService.CreatePeriodRequest(
                        "DAY", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12), "daily close");
        when(reconciliation.createPeriod(eq(jwt), eq(request), any())).thenReturn(null);

        mvc.perform(post("/api/v1/periods")
                        .contentType("application/json")
                        .content("""
                                {
                                  "periodType": "DAY",
                                  "startsOn": "2026-11-12",
                                  "endsOn": "2026-11-12",
                                  "reason": "daily close"
                                }
                                """))
                .andExpect(status().isOk());

        verify(reconciliation).createPeriod(eq(jwt), eq(request), any());
    }
}
