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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InventorySelloutControllerApiTest {
    private final InventorySelloutService data = mock(InventorySelloutService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new InventorySelloutController(data))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void inventoryListDelegatesServerPagingAndFilters() throws Exception {
        Jwt jwt = authenticatedJwt();
        UUID nppId = UUID.randomUUID();

        mvc.perform(get("/api/v1/inventory")
                        .param("nppId", nppId.toString())
                        .param("sku", "NS500")
                        .param("page", "2")
                        .param("size", "25"))
                .andExpect(status().isOk());

        verify(data).listInventory(jwt, nppId, "NS500", 2, 25);
    }

    @Test
    void selloutRejectsNonPositiveQuantitiesBeforeCallingService() throws Exception {
        mvc.perform(post("/api/v1/sellout-records")
                        .contentType("application/json")
                        .content("""
                                {
                                  "sku": "NS500",
                                  "unitCode": "thung",
                                  "quantity": 0,
                                  "outletCode": "OUT-1"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(data);
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
