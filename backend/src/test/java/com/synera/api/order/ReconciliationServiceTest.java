package com.synera.api.order;

import com.synera.api.auth.ApiProblem;
import com.synera.api.organization.BusinessAccessService;
import com.synera.api.partner.PartnerAccessService;
import com.synera.api.security.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BusinessAccessService access = mock(BusinessAccessService.class);
    private final ReconciliationService service = new ReconciliationService(
            jdbc, access, mock(PartnerAccessService.class), mock(AuditService.class),
            mock(OrderFlowService.class));

    @Test
    void rejectsNonCanonicalPeriodWithoutWriting() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(userId.toString()).claim("tenant_id", tenantId.toString()).build();
        when(access.actor(jwt)).thenReturn(new BusinessAccessService.Actor(userId, tenantId, "NSX"));

        ApiProblem problem = assertThrows(ApiProblem.class, () -> service.createPeriod(
                jwt, new ReconciliationService.CreatePeriodRequest(
                        "WEEK", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 16), "test"),
                mock(HttpServletRequest.class)));

        assertEquals("PERIOD_RANGE_NOT_CANONICAL", problem.code());
        verify(jdbc, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void reportsOnlyDifferencesGreaterThanFivePercent() {
        ReconciliationService.ReportRow atThreshold = service.reportRow(
                UUID.randomUUID(), "NPP", 0, 0, 105, 0, 0, 0,
                OffsetDateTime.now(ZoneOffset.UTC), 100L);
        ReconciliationService.ReportRow aboveThreshold = service.reportRow(
                UUID.randomUUID(), "NPP", 0, 0, 106, 0, 0, 0,
                OffsetDateTime.now(ZoneOffset.UTC), 100L);

        assertEquals("5.00", atThreshold.variancePercent().toPlainString());
        assertFalse(atThreshold.warningOverFivePercent());
        assertEquals("6.00", aboveThreshold.variancePercent().toPlainString());
        assertTrue(aboveThreshold.warningOverFivePercent());
    }
}
