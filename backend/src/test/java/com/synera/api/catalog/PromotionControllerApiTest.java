package com.synera.api.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PromotionControllerApiTest {
    private final PromotionService promotions = mock(PromotionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PromotionController(promotions))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listEndpointPassesDistributorOutletAndPagingFiltersToService() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID nppId = UUID.randomUUID();
        UUID outletId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("tenant_id", tenantId.toString())
                .build();
        when(promotions.list(jwt, nppId, outletId, 2, 5))
                .thenReturn(ApiPage.of(List.of(), 2, 5, 0));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt, null, AuthorityUtils.NO_AUTHORITIES));

        mvc.perform(get("/api/v1/promotions")
                        .param("nppId", nppId.toString())
                        .param("outletId", outletId.toString())
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk());

        verify(promotions).list(jwt, nppId, outletId, 2, 5);
    }
}
