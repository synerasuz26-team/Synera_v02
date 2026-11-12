package com.synera.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class AuditServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = new AuditService(jdbc, new ObjectMapper());

    @Test
    void recordsSensitiveChangesWithDeviceContext() {
        when(jdbc.queryForObject("SELECT sensitive FROM permission WHERE code = ?", Boolean.class, "role:assign"))
                .thenReturn(true);

        audit.recordIfSensitive(UUID.randomUUID(), UUID.randomUUID(), "role:assign", "ROLE_ASSIGNED",
                "USER_ROLE", UUID.randomUUID(), null, Map.of("roleCode", "NPP_STAFF"),
                "Phân công nhân sự", "127.0.0.1", "device-1", "test-agent");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), parameters.capture());
        assertTrue(sql.getValue().contains("device_id"));
        assertTrue(Arrays.asList(parameters.getValue()).contains("device-1"));
    }

    @Test
    void skipsAuditInsertWhenPermissionIsNotSensitive() {
        when(jdbc.queryForObject("SELECT sensitive FROM permission WHERE code = ?", Boolean.class, "product:view"))
                .thenReturn(false);

        audit.recordIfSensitive(UUID.randomUUID(), UUID.randomUUID(), "product:view", "VIEWED",
                "PRODUCT", UUID.randomUUID(), null, null, "", "127.0.0.1", null, "test-agent");

        verify(jdbc, never()).update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
    }
}
