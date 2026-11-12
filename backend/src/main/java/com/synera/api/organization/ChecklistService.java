package com.synera.api.organization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class ChecklistService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final BusinessAccessService access;
    private final AuditService audit;

    public ChecklistService(
            JdbcTemplate jdbc, ObjectMapper objectMapper,
            BusinessAccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.access = access;
        this.audit = audit;
    }

    public ApiPage<ChecklistTemplate> list(Jwt jwt, String query, int page, int size) {
        BusinessAccessService.Actor actor = authorizedActor(jwt);
        List<Object> arguments = new ArrayList<>();
        String where = "tenant_id = ?";
        arguments.add(actor.tenantId());
        if (query != null && !query.isBlank()) {
            where += " AND (LOWER(code) LIKE ? OR LOWER(name) LIKE ?)";
            String pattern = "%" + query.strip().toLowerCase() + "%";
            arguments.add(pattern);
            arguments.add(pattern);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM checklist_template WHERE " + where,
                Long.class, arguments.toArray());
        arguments.add(size);
        arguments.add((long) page * size);
        List<ChecklistTemplate> items = jdbc.query("""
                SELECT id, code, name, definition, status, version
                FROM checklist_template WHERE
                """ + where + " ORDER BY code LIMIT ? OFFSET ?",
                (rs, row) -> new ChecklistTemplate(rs.getObject("id", UUID.class),
                        rs.getString("code"), rs.getString("name"), readJson(rs.getString("definition")),
                        rs.getString("status"), rs.getLong("version")), arguments.toArray());
        return ApiPage.of(items, page, size, total);
    }

    @Transactional
    public ChecklistTemplate create(Jwt jwt, ChecklistTemplateRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = authorizedActor(jwt);
        String code = normalizeCode(request.code());
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM checklist_template WHERE tenant_id = ? AND code = ?)
                """, Boolean.class, actor.tenantId(), code);
        if (Boolean.TRUE.equals(exists)) {
            throw new ApiProblem("CHECKLIST_CODE_EXISTS", "Mã checklist đã tồn tại.",
                    HttpStatus.CONFLICT);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO checklist_template (id, tenant_id, code, name, definition, created_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, id, actor.tenantId(), code, request.name().strip(), toJson(request.definition()),
                actor.userId());
        ChecklistTemplate created = find(actor.tenantId(), id);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "checklist:manage", "CHECKLIST_CREATED",
                "checklist_template", id, null, created, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return created;
    }

    @Transactional
    public ChecklistTemplate update(
            Jwt jwt, UUID id, ChecklistTemplateUpdateRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = authorizedActor(jwt);
        ChecklistTemplate before = find(actor.tenantId(), id);
        int changed = jdbc.update("""
                UPDATE checklist_template
                SET name = ?, definition = ?::jsonb, status = ?, updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE tenant_id = ? AND id = ? AND version = ?
                """, request.name().strip(), toJson(request.definition()), request.status(),
                actor.tenantId(), id, request.version());
        if (changed == 0) {
            throw new ApiProblem("VERSION_CONFLICT", "Checklist đã được người khác cập nhật.",
                    HttpStatus.CONFLICT, Map.of("resource", "checklist_template", "id", id.toString()));
        }
        ChecklistTemplate after = find(actor.tenantId(), id);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "checklist:manage", "CHECKLIST_UPDATED",
                "checklist_template", id, before, after, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return after;
    }

    private BusinessAccessService.Actor authorizedActor(Jwt jwt) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        ResolvedScope scope = access.resolve(jwt, "checklist:manage");
        if (!"A".equals(scope.scope()) || !actor.tenantId().equals(scope.tenantId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Không được phép quản lý checklist ngoài tenant.");
        }
        return actor;
    }

    private ChecklistTemplate find(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT id, code, name, definition, status, version
                FROM checklist_template WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> new ChecklistTemplate(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("name"), readJson(rs.getString("definition")),
                rs.getString("status"), rs.getLong("version")), tenantId, id).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("CHECKLIST_NOT_FOUND", "Không tìm thấy checklist.",
                        HttpStatus.NOT_FOUND));
    }

    private String toJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiProblem("INVALID_CHECKLIST_DEFINITION", "Nội dung checklist không hợp lệ.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Dữ liệu checklist trong database không hợp lệ.", exception);
        }
    }

    private String normalizeCode(String code) {
        return code.strip().toUpperCase();
    }

    public record ChecklistTemplate(
            UUID id, String code, String name, JsonNode definition, String status, long version) {
    }

    public record ChecklistTemplateRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80) String code,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 160) String name,
            @jakarta.validation.constraints.NotNull JsonNode definition,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record ChecklistTemplateUpdateRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 160) String name,
            @jakarta.validation.constraints.NotNull JsonNode definition,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }
}
