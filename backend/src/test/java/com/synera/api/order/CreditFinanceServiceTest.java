package com.synera.api.order;

import com.synera.api.organization.BusinessAccessService;
import com.synera.api.partner.PartnerAccessService;
import com.synera.api.security.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreditFinanceServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BusinessAccessService access = mock(BusinessAccessService.class);
    private final PartnerAccessService partnerAccess = mock(PartnerAccessService.class);
    private final AuditService audit = mock(AuditService.class);
    private final CreditFinanceService service = new CreditFinanceService(
            jdbc, access, partnerAccess, audit);

    @Test
    void onlyNppActorsCanDeclarePayments() {
        UUID userId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(userId.toString()).build();
        when(access.actor(jwt)).thenReturn(new BusinessAccessService.Actor(
                userId, UUID.randomUUID(), "NSX"));
        CreditFinanceService.DeclarePaymentRequest request =
                new CreditFinanceService.DeclarePaymentRequest(
                        UUID.randomUUID(), null, 1000, "REF-1", "Đã chuyển");

        assertThrows(AccessDeniedException.class,
                () -> service.declarePayment(jwt, request, mock(HttpServletRequest.class)));

        verifyNoInteractions(jdbc, partnerAccess, audit);
    }
}
