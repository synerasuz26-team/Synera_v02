package com.synera.api.security;

import com.synera.api.auth.AuthIdentity;
import com.synera.api.auth.AuthIdentityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class RoleAssignmentService {
    private final JdbcTemplate jdbc;
    private final AuthIdentityRepository identities;
    private final RoleAssignmentPolicy policy;
    private final AuditService audit;

    public RoleAssignmentService(
            JdbcTemplate jdbc,
            AuthIdentityRepository identities,
            RoleAssignmentPolicy policy,
            AuditService audit) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.policy = policy;
        this.audit = audit;
    }

    @Transactional
    public void assign(
            UUID actorId, UUID targetId, String roleCode, String reason,
            String ip, String deviceId, String userAgent) {
        AuthIdentity actor = identities.findById(actorId)
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy người gán vai."));
        Target target = jdbc.query("""
                SELECT u.tenant_id, t.tenant_type
                FROM app_users u JOIN tenant t ON t.id = u.tenant_id
                WHERE u.id = ?
                """, rs -> rs.next()
                        ? new Target(rs.getObject("tenant_id", UUID.class), rs.getString("tenant_type"))
                        : null, targetId);
        if (target == null) {
            throw new IllegalArgumentException("Không tìm thấy tài khoản cần gán vai.");
        }
        Role role = jdbc.query("""
                SELECT tenant_type, assignment_level FROM role WHERE code = ?
                """, rs -> rs.next() ? new Role(rs.getString("tenant_type"), rs.getInt("assignment_level")) : null,
                roleCode);
        if (role == null) {
            throw new IllegalArgumentException("Vai không tồn tại.");
        }
        int actorMaxLevel = actor.roles().stream()
                .mapToInt(AuthIdentity.Role::assignmentLevel)
                .max()
                .orElse(0);
        policy.validate(actorId, targetId, actor.tenantId(), target.tenantId(), actor.tenantType(),
                role.tenantType(), actorMaxLevel, role.assignmentLevel());
        int inserted = jdbc.update("""
                INSERT INTO user_role (id, tenant_id, user_id, role_code, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id, role_code) DO NOTHING
                """, UUID.randomUUID(), target.tenantId(), targetId, roleCode, actorId);
        if (inserted > 0) {
            audit.recordIfSensitive(target.tenantId(), actorId, "role:assign", "ROLE_ASSIGNED",
                    "USER_ROLE", targetId,
                    null, Map.of("userId", targetId, "roleCode", roleCode),
                    reason, ip, deviceId, userAgent);
        }
    }

    private record Target(UUID tenantId, String tenantType) {
    }

    private record Role(String tenantType, int assignmentLevel) {
    }
}
