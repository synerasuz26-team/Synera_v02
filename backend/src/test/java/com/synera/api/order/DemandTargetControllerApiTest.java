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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemandTargetControllerApiTest {
    private final DemandTargetService planning = mock(DemandTargetService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DemandTargetController(planning))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void demandPlanListPassesDateAndPageFilters() throws Exception {
        Jwt jwt = authenticatedJwt();
        UUID nppId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);

        mvc.perform(get("/api/v1/demand-plans")
                        .param("nppId", nppId.toString())
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk());

        verify(planning).listDemandPlans(jwt, nppId, from, to, 1, 20);
    }

    @Test
    void targetListPassesPeriodAndPageFilters() throws Exception {
        Jwt jwt = authenticatedJwt();
        UUID periodId = UUID.randomUUID();

        mvc.perform(get("/api/v1/targets")
                        .param("periodId", periodId.toString())
                        .param("page", "3")
                        .param("size", "15"))
                .andExpect(status().isOk());

        verify(planning).listTargets(jwt, periodId, 3, 15);
    }

    private Jwt authenticatedJwt() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt, null, AuthorityUtils.NO_AUTHORITIES));
        return jwt;
    }
}
