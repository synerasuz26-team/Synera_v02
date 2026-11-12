package com.synera.api.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;

@Service
public class SystemSeedService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SystemSeedService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void seed() {
        JsonNode seed = readSeed();
        seedRoles(seed.path("roles"));
        seedPermissions(seed.path("permissions"));
        seedRolePermissions(seed.path("rolePermissions"));
        seedOrderTransitions(seed.path("orderTransitions"));
    }

    private JsonNode readSeed() {
        try (InputStream input = new ClassPathResource("seed/system-seed.json").getInputStream()) {
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Không đọc được dữ liệu seed hệ thống.", exception);
        }
    }

    private void seedRoles(JsonNode rows) {
        rows.forEach(row -> jdbc.update("""
                INSERT INTO role (code, name, tenant_type, default_scope, description)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (code) DO UPDATE SET
                    name = EXCLUDED.name,
                    tenant_type = EXCLUDED.tenant_type,
                    default_scope = EXCLUDED.default_scope,
                    description = EXCLUDED.description
                """,
                row.path("code").asText(),
                row.path("name").asText(),
                row.path("tenantType").asText(),
                row.path("defaultScope").asText(),
                row.path("description").asText()));
    }

    private void seedPermissions(JsonNode rows) {
        rows.forEach(row -> jdbc.update("""
                INSERT INTO permission (code, module, description, sensitive, phase, notes)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (code) DO UPDATE SET
                    module = EXCLUDED.module,
                    description = EXCLUDED.description,
                    sensitive = EXCLUDED.sensitive,
                    phase = EXCLUDED.phase,
                    notes = EXCLUDED.notes
                """,
                row.path("code").asText(),
                row.path("module").asText(),
                row.path("description").asText(),
                row.path("sensitive").asBoolean(),
                row.path("phase").asText(),
                row.path("notes").asText()));
    }

    private void seedRolePermissions(JsonNode rows) {
        rows.forEach(row -> jdbc.update("""
                INSERT INTO role_permission (role_code, permission_code, scope_code)
                VALUES (?, ?, ?)
                ON CONFLICT (role_code, permission_code) DO UPDATE
                    SET scope_code = EXCLUDED.scope_code
                """,
                row.path("roleCode").asText(),
                row.path("permissionCode").asText(),
                row.path("scope").asText()));
    }

    private void seedOrderTransitions(JsonNode rows) {
        rows.forEach(row -> jdbc.update("""
                INSERT INTO order_status_transition (
                    flow_type, from_status, to_status, action, required_permission, actor, notes
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (flow_type, from_status, to_status) DO UPDATE SET
                    action = EXCLUDED.action,
                    required_permission = EXCLUDED.required_permission,
                    actor = EXCLUDED.actor,
                    notes = EXCLUDED.notes
                """,
                row.path("flowType").asText(),
                row.path("fromStatus").asText(),
                row.path("toStatus").asText(),
                row.path("action").asText(),
                row.path("requiredPermission").isNull()
                        ? null
                        : row.path("requiredPermission").asText(),
                row.path("actor").asText(),
                row.path("notes").asText()));
    }
}
