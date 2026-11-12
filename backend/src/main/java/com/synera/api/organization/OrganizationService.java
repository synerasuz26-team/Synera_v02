package com.synera.api.organization;

import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.ApiPage;
import com.synera.api.security.AuditService;
import com.synera.api.security.ResolvedScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrganizationService {
    private final JdbcTemplate jdbc;
    private final BusinessAccessService access;
    private final AuditService audit;

    public OrganizationService(JdbcTemplate jdbc, BusinessAccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    public ApiPage<OrgUnit> list(Jwt jwt, String query, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        ResolvedScope scope = access.resolve(jwt, "org_unit:view");
        BusinessAccessService.Filter scopeFilter = access.orgUnitFilter(scope, "ou");
        List<Object> arguments = new ArrayList<>(scopeFilter.arguments());
        StringBuilder where = new StringBuilder(scopeFilter.sql());
        if (query != null && !query.isBlank()) {
            where.append(" AND (LOWER(ou.code) LIKE ? OR LOWER(ou.name) LIKE ?)");
            String pattern = "%" + query.strip().toLowerCase() + "%";
            arguments.add(pattern);
            arguments.add(pattern);
        }
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_unit ou WHERE " + where, Long.class, arguments.toArray());
        arguments.add(size);
        arguments.add((long) page * size);
        List<OrgUnit> items = jdbc.query("""
                SELECT ou.id, ou.parent_id, ou.code, ou.name, ou.unit_type, ou.path, ou.status, ou.version
                FROM org_unit ou
                WHERE """ + where + """
                ORDER BY ou.path, ou.code LIMIT ? OFFSET ?
                """, this::mapOrgUnit, arguments.toArray());
        return ApiPage.of(items, page, size, total);
    }

    @Transactional
    public OrgUnit create(Jwt jwt, OrgUnitRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        String code = normalizeCode(request.code());
        String path = pathFor(actor.tenantId(), request.parentId(), code, jwt);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO org_unit (id, tenant_id, parent_id, code, name, unit_type, path, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, actor.tenantId(), request.parentId(), code, request.name().strip(),
                request.unitType().strip().toUpperCase(), path,
                request.status() == null ? "ACTIVE" : request.status());
        OrgUnit created = find(actor.tenantId(), id);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "org_unit:manage", "ORG_UNIT_CREATED",
                "org_unit", id, null, created, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return created;
    }

    @Transactional
    public OrgUnit update(
            Jwt jwt, UUID id, OrgUnitUpdateRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireOrgUnitAccess(jwt, "org_unit:manage", id);
        OrgUnit before = find(actor.tenantId(), id);
        if (before.version() != request.version()) {
            throw versionConflict(id);
        }
        String code = normalizeCode(request.code());
        String nextPath = pathFor(actor.tenantId(), request.parentId(), code, jwt);
        if (nextPath.startsWith(before.path() + "/")) {
            throw new ApiProblem("ORG_UNIT_CYCLE", "Không thể chuyển đơn vị vào chính nhánh con của nó.",
                    HttpStatus.CONFLICT);
        }
        int changed = jdbc.update("""
                UPDATE org_unit
                SET parent_id = ?, code = ?, name = ?, unit_type = ?, path = ?, status = ?,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE tenant_id = ? AND id = ? AND version = ?
                """, request.parentId(), code, request.name().strip(), request.unitType().strip().toUpperCase(),
                nextPath, request.status() == null ? before.status() : request.status(),
                actor.tenantId(), id, request.version());
        if (changed == 0) {
            throw versionConflict(id);
        }
        jdbc.update("""
                UPDATE org_unit SET path = ? || substring(path from ?), updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE tenant_id = ? AND path LIKE ?
                """, nextPath, before.path().length() + 1, actor.tenantId(), before.path() + "/%");
        OrgUnit after = find(actor.tenantId(), id);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "org_unit:manage", "ORG_UNIT_UPDATED",
                "org_unit", id, before, after, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return after;
    }

    private String pathFor(UUID tenantId, UUID parentId, String code, Jwt jwt) {
        if (parentId == null) {
            ResolvedScope scope = access.resolve(jwt, "org_unit:manage");
            if (!"A".equals(scope.scope())) {
                throw new ApiProblem("PARENT_ORG_UNIT_REQUIRED",
                        "Cần chọn đơn vị cha trong phạm vi được phân quyền.", HttpStatus.BAD_REQUEST);
            }
            return "/" + code;
        }
        access.requireOrgUnitAccess(jwt, "org_unit:manage", parentId);
        String parentPath = jdbc.query("""
                SELECT path FROM org_unit WHERE tenant_id = ? AND id = ?
                """, rs -> rs.next() ? rs.getString("path") : null, tenantId, parentId);
        if (parentPath == null) {
            throw new ApiProblem("ORG_UNIT_NOT_FOUND", "Không tìm thấy đơn vị cha.", HttpStatus.NOT_FOUND);
        }
        return parentPath + "/" + code;
    }

    private OrgUnit find(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT id, parent_id, code, name, unit_type, path, status, version
                FROM org_unit WHERE tenant_id = ? AND id = ?
                """, this::mapOrgUnit, tenantId, id).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("ORG_UNIT_NOT_FOUND", "Không tìm thấy đơn vị tổ chức.",
                        HttpStatus.NOT_FOUND));
    }

    private OrgUnit mapOrgUnit(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new OrgUnit(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("unit_type"),
                rs.getString("path"), rs.getString("status"), rs.getLong("version"));
    }

    private String normalizeCode(String code) {
        return code.strip().toUpperCase();
    }

    private ApiProblem versionConflict(UUID id) {
        return new ApiProblem("VERSION_CONFLICT", "Dữ liệu đã được người khác cập nhật.",
                HttpStatus.CONFLICT, Map.of("resource", "org_unit", "id", id.toString()));
    }

    public record OrgUnit(
            UUID id, UUID parentId, String code, String name, String unitType,
            String path, String status, long version) {
    }

    public record OrgUnitRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String code,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 160) String name,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 32) String unitType,
            UUID parentId,
            @jakarta.validation.constraints.Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record OrgUnitUpdateRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String code,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 160) String name,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 32) String unitType,
            UUID parentId,
            @jakarta.validation.constraints.Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }
}
