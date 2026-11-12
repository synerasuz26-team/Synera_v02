package com.synera.api.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AuthIdentityRepository {
    private final JdbcTemplate jdbc;

    public AuthIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AuthIdentity> findByEmail(String email) {
        List<UserRow> users = jdbc.query("""
                SELECT u.id, u.full_name, u.email, u.password_hash, u.status AS user_status,
                       t.id AS tenant_id, t.tenant_type, t.code AS tenant_code, t.name AS tenant_name,
                       t.status AS tenant_status
                FROM app_users u
                JOIN tenant t ON t.id = u.tenant_id
                WHERE u.email = ?
                """, this::mapUser, email);
        return users.stream().findFirst().map(this::expand);
    }

    public Optional<AuthIdentity> findById(UUID userId) {
        List<UserRow> users = jdbc.query("""
                SELECT u.id, u.full_name, u.email, u.password_hash, u.status AS user_status,
                       t.id AS tenant_id, t.tenant_type, t.code AS tenant_code, t.name AS tenant_name,
                       t.status AS tenant_status
                FROM app_users u
                JOIN tenant t ON t.id = u.tenant_id
                WHERE u.id = ?
                """, this::mapUser, userId);
        return users.stream().findFirst().map(this::expand);
    }

    private UserRow mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserRow(
                rs.getObject("id", UUID.class),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("user_status"),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_type"),
                rs.getString("tenant_code"),
                rs.getString("tenant_name"),
                rs.getString("tenant_status"));
    }

    private AuthIdentity expand(UserRow user) {
        UUID userId = user.id();
        UUID tenantId = user.tenantId();
        List<AuthIdentity.Role> roles = jdbc.query("""
                SELECT r.code, r.name, r.assignment_level
                FROM user_role ur
                JOIN role r ON r.code = ur.role_code
                WHERE ur.tenant_id = ? AND ur.user_id = ?
                ORDER BY r.code
                """, (roleRs, roleRow) -> new AuthIdentity.Role(
                roleRs.getString("code"),
                roleRs.getString("name"),
                roleRs.getInt("assignment_level")), tenantId, userId);

        Map<String, String> permissionScopes = new LinkedHashMap<>();
        jdbc.query("""
                SELECT rp.permission_code, rp.scope_code
                FROM user_role ur
                JOIN role_permission rp ON rp.role_code = ur.role_code
                WHERE ur.tenant_id = ? AND ur.user_id = ?
                ORDER BY rp.permission_code, rp.scope_code
                """, permissionRs -> {
            String permission = permissionRs.getString("permission_code");
            String scope = permissionRs.getString("scope_code");
            permissionScopes.merge(permission, scope, AuthIdentityRepository::broaderScope);
        }, tenantId, userId);

        List<UUID> orgUnitIds = jdbc.query("""
                SELECT org_unit_id FROM user_org_unit
                WHERE tenant_id = ? AND user_id = ? ORDER BY org_unit_id
                """, (unitRs, unitRow) -> unitRs.getObject(1, UUID.class), tenantId, userId);
        List<UUID> routeIds = jdbc.query("""
                SELECT route_id FROM user_route
                WHERE tenant_id = ? AND user_id = ? ORDER BY route_id
                """, (routeRs, routeRow) -> routeRs.getObject(1, UUID.class), tenantId, userId);

        return new AuthIdentity(
                user.id(),
                user.fullName(),
                user.email(),
                user.passwordHash(),
                user.userStatus(),
                user.tenantId(),
                user.tenantType(),
                user.tenantCode(),
                user.tenantName(),
                user.tenantStatus(),
                roles,
                Map.copyOf(permissionScopes),
                orgUnitIds,
                routeIds);
    }

    private record UserRow(
            UUID id,
            String fullName,
            String email,
            String passwordHash,
            String userStatus,
            UUID tenantId,
            String tenantType,
            String tenantCode,
            String tenantName,
            String tenantStatus) {
    }

    private static String broaderScope(String left, String right) {
        return scopeRank(left) <= scopeRank(right) ? left : right;
    }

    private static int scopeRank(String scope) {
        return switch (scope) {
            case "A" -> 0;
            case "R" -> 1;
            case "T" -> 2;
            case "O" -> 3;
            default -> throw new IllegalStateException("Phạm vi quyền không hợp lệ: " + scope);
        };
    }
}
