package com.synera.api.catalog;

import com.synera.api.auth.ApiProblem;
import com.synera.api.security.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PriceListService {
    private final JdbcTemplate jdbc;
    private final CatalogAccessService access;
    private final CatalogScopeService catalogScopes;
    private final ScopedCatalogWorkflowPolicy workflowPolicy;
    private final AuditService audit;

    public PriceListService(
            JdbcTemplate jdbc, CatalogAccessService access, CatalogScopeService catalogScopes,
            ScopedCatalogWorkflowPolicy workflowPolicy, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.catalogScopes = catalogScopes;
        this.workflowPolicy = workflowPolicy;
        this.audit = audit;
    }

    public ApiPage<PriceListSummary> list(
            Jwt jwt, UUID nppId, UUID outletId, String status, int page, int size,
            HttpServletRequest servletRequest) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        String permission = readPermission(jwt);
        workflowPolicy.requireSelectedOutlet(jwt, permission, nppId, outletId);
        List<Object> arguments = new ArrayList<>();
        StringBuilder filter = new StringBuilder("pl.tenant_id = ?");
        arguments.add(actor.tenantId());
        if (!"A".equals(permissionScope(jwt, permission))) {
            filter.append("""
                     AND EXISTS (
                         SELECT 1 FROM price_list_scope s
                         WHERE s.tenant_id = pl.tenant_id AND s.price_list_id = pl.id
                     )
                     AND """ + catalogScopes.matchesPredicate("price_list_scope", "price_list_id", "pl"));
            arguments.addAll(catalogScopes.matchArguments(nppId, outletId,
                    java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)));
        }
        if (status != null) {
            filter.append(" AND pl.status = ?");
            arguments.add(status);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM price_list pl WHERE " + filter,
                Long.class, arguments.toArray());
        arguments.add(size);
        arguments.add((long) page * size);
        List<PriceListSummary> items = jdbc.query("""
                SELECT id, code, name, status, created_by, approved_by, effective_from, effective_to, version
                FROM price_list pl WHERE """ + filter + """
                ORDER BY pl.updated_at DESC, pl.code
                LIMIT ? OFFSET ?
                """, (rs, row) -> summary(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("status"), rs.getObject("created_by", UUID.class),
                rs.getObject("approved_by", UUID.class), rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class), rs.getLong("version")), arguments.toArray());
        ApiPage<PriceListSummary> result = ApiPage.of(items, page, size, total);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), permission, "PRICE_LIST_LIST_VIEWED",
                "price_list", null, null, Map.of("status", status == null ? "" : status,
                        "page", page, "size", size, "totalElements", total),
                "Tra cứu danh sách bảng giá", servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return result;
    }

    public PriceListDetail get(
            Jwt jwt, UUID id, UUID nppId, UUID outletId, HttpServletRequest servletRequest) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        PriceListDetail result = detail(actor.tenantId(), id);
        String permission = readPermission(jwt);
        workflowPolicy.requireRecordAccess(
                jwt, permission, "price_list_scope", "price_list_id",
                actor.tenantId(), id, nppId, outletId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), permission, "PRICE_LIST_VIEWED",
                "price_list", id, null, result.priceList(), "Tra cứu bảng giá",
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    private PriceListDetail detail(UUID tenantId, UUID id) {
        PriceListSummary summary = find(tenantId, id);
        List<PriceLine> lines = jdbc.query("""
                SELECT product_id, unit_code, sellin_price_vnd, sellout_price_vnd
                FROM price_list_line WHERE tenant_id = ? AND price_list_id = ?
                ORDER BY product_id, unit_code
                """, (rs, row) -> new PriceLine(
                rs.getObject("product_id", UUID.class), rs.getString("unit_code"),
                (Long) rs.getObject("sellin_price_vnd"), (Long) rs.getObject("sellout_price_vnd")),
                tenantId, id);
        List<PriceScope> scopes = jdbc.query("""
                SELECT scope_type, scope_id FROM price_list_scope
                WHERE tenant_id = ? AND price_list_id = ?
                ORDER BY scope_type, scope_id
                """, (rs, row) -> new PriceScope(rs.getString("scope_type"),
                rs.getObject("scope_id", UUID.class)), tenantId, id);
        return new PriceListDetail(summary, lines, scopes);
    }

    @Transactional
    public PriceListDetail create(Jwt jwt, PriceListRequest request, HttpServletRequest servletRequest) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        validateLines(actor.tenantId(), request.lines());
        List<PriceScope> scopes = request.scopes() == null ? List.of() : request.scopes();
        List<CatalogScopeService.CatalogScope> catalogScopeValues = scopes.stream()
                .map(scope -> new CatalogScopeService.CatalogScope(scope.scopeType(), scope.scopeId())).toList();
        catalogScopes.validate(actor, catalogScopeValues);
        workflowPolicy.requireScopeCoverage(jwt, "price_list:manage", actor.tenantId(), catalogScopeValues);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO price_list (id, tenant_id, code, name, status, created_by)
                VALUES (?, ?, ?, ?, 'DRAFT', ?)
                """, id, actor.tenantId(), request.code().strip(), request.name().strip(), actor.userId());
        for (PriceLine line : request.lines()) {
            jdbc.update("""
                    INSERT INTO price_list_line (
                        id, tenant_id, price_list_id, product_id, unit_code,
                        sellin_price_vnd, sellout_price_vnd
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), actor.tenantId(), id, line.productId(),
                    line.unitCode().strip().toUpperCase(), line.sellinPriceVnd(), line.selloutPriceVnd());
        }
        saveScopes(actor.tenantId(), id, scopes);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "price_list:manage", "PRICE_LIST_CREATED",
                "price_list", id, null, request, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return detail(actor.tenantId(), id);
    }

    @Transactional
    public PriceListDetail submit(
            Jwt jwt, UUID id, UUID nppId, UUID outletId,
            WorkflowRequest request, HttpServletRequest servletRequest) {
        return transition(jwt, id, nppId, outletId, request, servletRequest,
                "DRAFT", "PENDING_APPROVAL", "PRICE_LIST_SUBMITTED", "price_list:manage", false, null);
    }

    @Transactional
    public PriceListDetail approve(
            Jwt jwt, UUID id, UUID nppId, UUID outletId,
            WorkflowRequest request, HttpServletRequest servletRequest) {
        return transition(jwt, id, nppId, outletId, request, servletRequest,
                "PENDING_APPROVAL", "APPROVED", "PRICE_LIST_APPROVED", "price_list:approve", true, null);
    }

    @Transactional
    public PriceListDetail reject(
            Jwt jwt, UUID id, UUID nppId, UUID outletId,
            WorkflowRequest request, HttpServletRequest servletRequest) {
        return transition(jwt, id, nppId, outletId, request, servletRequest,
                "PENDING_APPROVAL", "REJECTED", "PRICE_LIST_REJECTED", "price_list:approve", true, null);
    }

    @Transactional
    public PriceListDetail activate(
            Jwt jwt, UUID id, UUID nppId, UUID outletId,
            ActivateRequest request, HttpServletRequest servletRequest) {
        if (!request.effectiveFrom().isBefore(request.effectiveTo())) {
            throw new ApiProblem("INVALID_EFFECTIVE_PERIOD", "Ngày kết thúc phải sau ngày bắt đầu.",
                    HttpStatus.BAD_REQUEST);
        }
        return transition(jwt, id, nppId, outletId,
                new WorkflowRequest(request.version(), request.reason()), servletRequest,
                "APPROVED", "EFFECTIVE", "PRICE_LIST_ACTIVATED", "price_list:approve", false,
                new EffectivePeriod(request.effectiveFrom(), request.effectiveTo()));
    }

    private PriceListDetail transition(
            Jwt jwt,
            UUID id,
            UUID nppId,
            UUID outletId,
            WorkflowRequest request,
            HttpServletRequest servletRequest,
            String expectedStatus,
            String nextStatus,
            String action,
            String permission,
            boolean requireDifferentApprover,
            EffectivePeriod effectivePeriod) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        PriceListSummary before = find(actor.tenantId(), id);
        workflowPolicy.requireRecordAccess(
                jwt, permission, "price_list_scope", "price_list_id",
                actor.tenantId(), id, nppId, outletId);
        if (!before.status().equals(expectedStatus)) {
            throw invalidState(before.status(), expectedStatus);
        }
        if (before.version() != request.version()) {
            throw versionConflict("price_list", id);
        }
        if (requireDifferentApprover && actor.userId().equals(before.createdBy())) {
            throw new ApiProblem("APPROVER_MUST_DIFFER", "Người duyệt phải khác người tạo.",
                    HttpStatus.CONFLICT);
        }
        int updated;
        if ("APPROVED".equals(nextStatus) || "REJECTED".equals(nextStatus)) {
            updated = jdbc.update("""
                    UPDATE price_list
                    SET status = ?, approved_by = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = ?
                    """, nextStatus, "APPROVED".equals(nextStatus) ? actor.userId() : null,
                    actor.tenantId(), id, request.version(), expectedStatus);
        } else if (effectivePeriod != null) {
            updated = jdbc.update("""
                    UPDATE price_list
                    SET status = ?, effective_from = ?, effective_to = ?,
                        updated_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = ?
                      AND approved_by IS NOT NULL AND approved_by <> created_by
                    """, nextStatus, effectivePeriod.from(), effectivePeriod.to(),
                    actor.tenantId(), id, request.version(), expectedStatus);
        } else {
            updated = jdbc.update("""
                    UPDATE price_list
                    SET status = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = ?
                    """, nextStatus, actor.tenantId(), id, request.version(), expectedStatus);
        }
        if (updated == 0) {
            throw versionConflict("price_list", id);
        }
        PriceListSummary after = find(actor.tenantId(), id);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), permission, action,
                "price_list", id, before, after, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return detail(actor.tenantId(), id);
    }

    private void validateLines(UUID tenantId, List<PriceLine> lines) {
        if (lines.stream().map(line -> line.productId() + ":" + line.unitCode().toUpperCase())
                .distinct().count() != lines.size()) {
            throw new ApiProblem("DUPLICATE_PRICE_LINE", "SKU và đơn vị trong bảng giá bị trùng.",
                    HttpStatus.BAD_REQUEST);
        }
        for (PriceLine line : lines) {
            if (line.sellinPriceVnd() == null && line.selloutPriceVnd() == null) {
                throw new ApiProblem("PRICE_REQUIRED", "Mỗi dòng cần có ít nhất một loại giá.",
                        HttpStatus.BAD_REQUEST);
            }
            Boolean valid = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM product p
                        JOIN unit_conversion uc ON uc.product_id = p.id AND uc.tenant_id = p.tenant_id
                        WHERE p.tenant_id = ? AND p.id = ? AND uc.unit_code = ?
                    )
                    """, Boolean.class, tenantId, line.productId(), line.unitCode().strip().toUpperCase());
            if (!Boolean.TRUE.equals(valid)) {
                throw new ApiProblem("INVALID_PRODUCT_UNIT", "SKU hoặc đơn vị không thuộc danh mục NSX.",
                        HttpStatus.BAD_REQUEST,
                        Map.of("productId", line.productId().toString(), "unitCode", line.unitCode()));
            }
        }
    }

    private void saveScopes(UUID tenantId, UUID id, List<PriceScope> scopes) {
        for (PriceScope scope : scopes) {
            jdbc.update("""
                    INSERT INTO price_list_scope (id, tenant_id, price_list_id, scope_type, scope_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), tenantId, id, scope.scopeType(), scope.scopeId());
        }
    }

    private PriceListSummary find(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT id, code, name, status, created_by, approved_by, effective_from, effective_to, version
                FROM price_list WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> summary(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("status"), rs.getObject("created_by", UUID.class),
                rs.getObject("approved_by", UUID.class), rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class), rs.getLong("version")),
                tenantId, id).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("PRICE_LIST_NOT_FOUND", "Không tìm thấy bảng giá.",
                        HttpStatus.NOT_FOUND));
    }

    private ApiProblem invalidState(String current, String expected) {
        return new ApiProblem("INVALID_WORKFLOW_STATE", "Không thể chuyển trạng thái bảng giá.",
                HttpStatus.CONFLICT, Map.of("currentStatus", current, "requiredStatus", expected));
    }

    private ApiProblem versionConflict(String resource, UUID id) {
        return new ApiProblem("VERSION_CONFLICT", "Dữ liệu đã được người khác cập nhật.",
                HttpStatus.CONFLICT, Map.of("resource", resource, "id", id.toString()));
    }

    private String readPermission(Jwt jwt) {
        Map<String, Object> permissions = jwt.getClaimAsMap("permission_scopes");
        if (permissions == null || !permissions.containsKey("price_list:approve")) {
            return "price_list:manage";
        }
        if (!permissions.containsKey("price_list:manage")) {
            return "price_list:approve";
        }
        return scopeRank((String) permissions.get("price_list:manage"))
                >= scopeRank((String) permissions.get("price_list:approve"))
                ? "price_list:manage"
                : "price_list:approve";
    }

    private String permissionScope(Jwt jwt, String permission) {
        Map<String, Object> permissions = jwt.getClaimAsMap("permission_scopes");
        return permissions == null ? null : (String) permissions.get(permission);
    }

    private int scopeRank(String scope) {
        return switch (scope) {
            case "A" -> 4;
            case "R" -> 3;
            case "T" -> 2;
            case "O" -> 1;
            default -> 0;
        };
    }

    private static PriceListSummary summary(
            UUID id, String code, String name, String status, UUID createdBy, UUID approvedBy,
            OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo, long version) {
        return new PriceListSummary(id, code, name, status, createdBy, approvedBy,
                effectiveFrom, effectiveTo, version);
    }

    public record PriceListRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80) String code,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String name,
            @jakarta.validation.constraints.NotEmpty
            List<@jakarta.validation.constraints.NotNull @jakarta.validation.Valid PriceLine> lines,
            List<@jakarta.validation.constraints.NotNull @jakarta.validation.Valid PriceScope> scopes,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record PriceLine(
            @jakarta.validation.constraints.NotNull UUID productId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 32) String unitCode,
            @jakarta.validation.constraints.PositiveOrZero Long sellinPriceVnd,
            @jakarta.validation.constraints.PositiveOrZero Long selloutPriceVnd) {
    }

    public record PriceScope(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "ORG_UNIT|NPP|OUTLET") String scopeType,
            @jakarta.validation.constraints.NotNull UUID scopeId) {
    }

    public record WorkflowRequest(
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record ActivateRequest(
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotNull OffsetDateTime effectiveFrom,
            @jakarta.validation.constraints.NotNull OffsetDateTime effectiveTo,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record PriceListSummary(
            UUID id, String code, String name, String status, UUID createdBy, UUID approvedBy,
            OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo, long version) {
    }

    public record PriceListDetail(PriceListSummary priceList, List<PriceLine> lines, List<PriceScope> scopes) {
    }

    private record EffectivePeriod(OffsetDateTime from, OffsetDateTime to) {
    }
}
