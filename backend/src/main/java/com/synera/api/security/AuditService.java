package com.synera.api.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void recordIfSensitive(
            UUID tenantId,
            UUID actorId,
            String permission,
            String action,
            String resourceType,
            UUID resourceId,
            Object oldValue,
            Object newValue,
            String reason,
            String ipAddress,
            String deviceId,
            String userAgent) {
        Boolean sensitive = jdbc.queryForObject(
                "SELECT sensitive FROM permission WHERE code = ?", Boolean.class, permission);
        if (!Boolean.TRUE.equals(sensitive)) {
            return;
        }
        jdbc.update("""
                INSERT INTO audit_log (
                    id, tenant_id, actor_id, permission_code, action, resource_type, resource_id,
                    old_values, new_values, reason, ip_address, device_id, user_agent
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::inet, ?, ?)
                """, UUID.randomUUID(), tenantId, actorId, permission, action, resourceType, resourceId,
                json(oldValue), json(newValue), reason, ipAddress, deviceId,
                userAgent == null ? "" : userAgent);
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể ghi giá trị audit.", exception);
        }
    }
}
