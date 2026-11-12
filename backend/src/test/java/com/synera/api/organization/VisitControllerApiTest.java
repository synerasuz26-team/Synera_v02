package com.synera.api.organization;

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

class VisitControllerApiTest {
    private final VisitService visits = mock(VisitService.class);
    private final BusinessAccessService access = mock(BusinessAccessService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new VisitController(visits, access))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void checkInEndpointPassesGpsCoordinatesToVisitService() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(userId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt, null, AuthorityUtils.NO_AUTHORITIES));
        when(visits.checkIn(eq(jwt), eq(visitId), any(VisitService.GpsActionRequest.class), any()))
                .thenReturn(new VisitService.VisitActionResult(visitId, "IN_PROGRESS", null, 24, false));

        mvc.perform(post("/api/v1/visits/{visitId}/check-in", visitId)
                        .contentType("application/json")
                        .content("""
                                {"latitude":10.75,"longitude":106.66,"reason":null}
                                """))
                .andExpect(status().isOk());

        verify(visits).checkIn(eq(jwt), eq(visitId),
                eq(new VisitService.GpsActionRequest(10.75, 106.66, null)), any());
    }
}
