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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PromotionService {
    private final JdbcTemplate jdbc;
    private final CatalogAccessService access;
    private final CatalogScopeService catalogScopes;
    private final ScopedCatalogWorkflowPolicy workflowPolicy;
    private final AuditService audit;

    public PromotionService(
            JdbcTemplate jdbc, CatalogAccessService access,
            CatalogScopeService catalogScopes, ScopedCatalogWorkflowPolicy workflowPolicy, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.catalogScopes = catalogScopes;
        this.workflowPolicy = workflowPolicy;
        this.audit = audit;
    }

    public ApiPage<PromotionSummary> list(
            Jwt jwt, UUID requestedNppId, UUID outletId, int page, int size) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID nppId = resolveNpp(jwt, actor, requestedNppId, outletId, now);
        boolean nsx = "NSX".equals(actor.tenantType());
        boolean privileged = hasPermission(jwt, "promotion:manage") || hasPermission(jwt, "promotion:approve");
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (nsx) {
            where.append("p.tenant_id = ?");
            args.add(actor.tenantId());
        } else {
            where.append("""
                    EXISTS (
                        SELECT 1 FROM tenant_relation tr
                        WHERE tr.nsx_tenant_id = p.tenant_id AND tr.npp_tenant_id = ?
                          AND tr.status = 'ACTIVE' AND tr.effective_from <= ?::date
                          AND (tr.effective_to IS NULL OR tr.effective_to >= ?::date)
                    )
                    """);
            args.add(nppId);
            args.add(access.businessDate(now));
            args.add(access.businessDate(now));
            where.append(" AND p.status = 'EFFECTIVE' AND p.effective_from <= ?")
                    .append(" AND (p.effective_to IS NULL OR p.effective_to >= ?)");
            args.add(now);
            args.add(now);
        }
        if (!nsx || outletId != null || requestedNppId != null) {
            where.append(" AND ")
                    .append(catalogScopes.matchesPredicate("promotion_scope", "promotion_id", "p"));
            args.addAll(catalogScopes.matchArguments(nppId, outletId, now));
        }
        if (nsx && !privileged) {
            where.append(" AND p.status = 'EFFECTIVE' AND p.effective_from <= ?")
                    .append(" AND (p.effective_to IS NULL OR p.effective_to >= ?)");
            args.add(now);
            args.add(now);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM promotion p WHERE " + where,
                Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<PromotionSummary> rows = jdbc.query("""
                SELECT p.id, p.code, p.name, p.status, p.effective_from, p.effective_to, p.version
                FROM promotion p WHERE """ + where + """
                ORDER BY p.name, p.code LIMIT ? OFFSET ?
                """, (rs, row) -> summary(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("status"), rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class), rs.getLong("version")), args.toArray());
        return ApiPage.of(rows, page, size, total);
    }

    public PromotionDetail getForNsx(
            Jwt jwt, UUID id, UUID nppId, UUID outletId, HttpServletRequest servletRequest) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        PromotionDetail detail = detail(actor.tenantId(), id);
        String permission = hasPermission(jwt, "promotion:approve")
                ? "promotion:approve"
                : "promotion:manage";
        workflowPolicy.requireRecordAccess(
                jwt, permission, "promotion_scope", "promotion_id", actor.tenantId(), id, nppId, outletId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), permission, "PROMOTION_VIEWED",
                "promotion", id, null, detail.promotion(), "Tra cứu CTKM",
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return detail;
    }

    @Transactional
    public PromotionDetail create(Jwt jwt, PromotionRequest request, HttpServletRequest servletRequest) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        validateRules(actor.tenantId(), request.rules());
        List<CatalogScopeService.CatalogScope> scopes = request.scopes() == null ? List.of() : request.scopes();
        catalogScopes.validate(actor, scopes);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO promotion (id, tenant_id, code, name, status, created_by)
                VALUES (?, ?, ?, ?, 'DRAFT', ?)
                """, id, actor.tenantId(), request.code().strip(), request.name().strip(), actor.userId());
        for (PromotionRule rule : request.rules()) {
            jdbc.update("""
                    INSERT INTO promotion_rule (
                        id, tenant_id, promotion_id, product_id, unit_code, min_order_qty,
                        discount_basis_points, gift_product_id, gift_qty
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), actor.tenantId(), id, rule.productId(),
                    rule.unitCode().strip().toUpperCase(), rule.minOrderQty(),
                    rule.discountBasisPoints(), rule.giftProductId(), rule.giftQty());
        }
        for (CatalogScopeService.CatalogScope scope : scopes) {
            jdbc.update("""
                    INSERT INTO promotion_scope (id, tenant_id, promotion_id, scope_type, scope_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), actor.tenantId(), id, scope.scopeType(), scope.scopeId());
        }
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "promotion:manage", "PROMOTION_CREATED",
                "promotion", id, null, request, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return detail(actor.tenantId(), id);
    }

    @Transactional
    public PromotionDetail submit(
            Jwt jwt, UUID id, UUID nppId, UUID outletId,
            WorkflowRequest request, HttpServletRequest servletRequest) {
        return transition(jwt, id, request.version(), request.reason(), servletRequest,
                "DRAFT", "PENDING_APPROVAL", "PROMOTION_SUBMITTED", "promotion:manage", false, null,
                nppId, outletId);
    }

    @Transactional
    public PromotionDetail approve(
            Jwt jwt, UUID id, UUID nppId, UUID outletId,
            WorkflowRequest request, HttpServletRequest servletRequest) {
        return transition(jwt, id, request.version(), request.reason(), servletRequest,
                "PENDING_APPROVAL", "APPROVED", "PROMOTION_APPROVED", "promotion:approve", true, null,
                nppId, outletId);
    }

    @Transactional
    public PromotionDetail reject(
            Jwt jwt, UUID id, UUID nppId, UUID outletId,
            WorkflowRequest request, HttpServletRequest servletRequest) {
        return transition(jwt, id, request.version(), request.reason(), servletRequest,
                "PENDING_APPROVAL", "REJECTED", "PROMOTION_REJECTED", "promotion:approve", true, null,
                nppId, outletId);
    }

    @Transactional
    public PromotionDetail activate(
            Jwt jwt, UUID id, UUID nppId, UUID outletId,
            ActivateRequest request, HttpServletRequest servletRequest) {
        if (!request.effectiveFrom().isBefore(request.effectiveTo())) {
            throw new ApiProblem("INVALID_EFFECTIVE_PERIOD", "Ngày kết thúc phải sau ngày bắt đầu.",
                    HttpStatus.BAD_REQUEST);
        }
        return transition(jwt, id, request.version(), request.reason(), servletRequest,
                "APPROVED", "EFFECTIVE", "PROMOTION_ACTIVATED", "promotion:approve", false,
                new EffectivePeriod(request.effectiveFrom(), request.effectiveTo()), nppId, outletId);
    }

    private PromotionDetail transition(
            Jwt jwt, UUID id, long version, String reason, HttpServletRequest servletRequest,
            String expectedStatus, String nextStatus, String action, String permission, boolean approval,
            EffectivePeriod effectivePeriod, UUID nppId, UUID outletId) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        PromotionSummary before = find(actor.tenantId(), id);
        workflowPolicy.requireRecordAccess(
                jwt, permission, "promotion_scope", "promotion_id", actor.tenantId(), id, nppId, outletId);
        if (!expectedStatus.equals(before.status())) {
            throw new ApiProblem("INVALID_WORKFLOW_STATE", "Không thể chuyển trạng thái CTKM.",
                    HttpStatus.CONFLICT, Map.of("currentStatus", before.status(),
                    "requiredStatus", expectedStatus));
        }
        if (before.version() != version) {
            throw versionConflict(id);
        }
        if (approval && actor.userId().equals(createdBy(actor.tenantId(), id))) {
            throw new ApiProblem("APPROVER_MUST_DIFFER", "Người duyệt phải khác người tạo.",
                    HttpStatus.CONFLICT);
        }
        int changed;
        if ("APPROVED".equals(nextStatus) || "REJECTED".equals(nextStatus)) {
            changed = jdbc.update("""
                    UPDATE promotion SET status = ?, approved_by = ?, updated_at = CURRENT_TIMESTAMP,
                        version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = ?
                    """, nextStatus, "APPROVED".equals(nextStatus) ? actor.userId() : null,
                    actor.tenantId(), id, version, expectedStatus);
        } else if (effectivePeriod != null) {
            changed = jdbc.update("""
                    UPDATE promotion SET status = ?, effective_from = ?, effective_to = ?,
                        updated_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = ?
                      AND approved_by IS NOT NULL AND approved_by <> created_by
                    """, nextStatus, effectivePeriod.from(), effectivePeriod.to(),
                    actor.tenantId(), id, version, expectedStatus);
        } else {
            changed = jdbc.update("""
                    UPDATE promotion SET status = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = ?
                    """, nextStatus, actor.tenantId(), id, version, expectedStatus);
        }
        if (changed == 0) {
            throw versionConflict(id);
        }
        PromotionSummary after = find(actor.tenantId(), id);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), permission, action,
                "promotion", id, before, after, reason, servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return detail(actor.tenantId(), id);
    }

    private PromotionDetail detail(UUID tenantId, UUID id) {
        PromotionSummary summary = find(tenantId, id);
        List<PromotionRule> rules = jdbc.query("""
                SELECT product_id, unit_code, min_order_qty, discount_basis_points, gift_product_id, gift_qty
                FROM promotion_rule WHERE tenant_id = ? AND promotion_id = ?
                ORDER BY product_id, unit_code
                """, (rs, row) -> new PromotionRule(
                rs.getObject("product_id", UUID.class), rs.getString("unit_code"),
                (Long) rs.getObject("min_order_qty"), (Integer) rs.getObject("discount_basis_points"),
                rs.getObject("gift_product_id", UUID.class), (Long) rs.getObject("gift_qty")),
                tenantId, id);
        List<CatalogScopeService.CatalogScope> scopes = jdbc.query("""
                SELECT scope_type, scope_id FROM promotion_scope
                WHERE tenant_id = ? AND promotion_id = ? ORDER BY scope_type, scope_id
                """, (rs, row) -> new CatalogScopeService.CatalogScope(
                rs.getString("scope_type"), rs.getObject("scope_id", UUID.class)), tenantId, id);
        return new PromotionDetail(summary, rules, scopes);
    }

    private void validateRules(UUID tenantId, List<PromotionRule> rules) {
        if (rules.stream().map(rule -> rule.productId() + ":" + rule.unitCode().toUpperCase())
                .distinct().count() != rules.size()) {
            throw new ApiProblem("DUPLICATE_PROMOTION_RULE", "Quy tắc CTKM bị trùng.",
                    HttpStatus.BAD_REQUEST);
        }
        for (PromotionRule rule : rules) {
            boolean discount = rule.discountBasisPoints() != null;
            boolean gift = rule.giftProductId() != null || rule.giftQty() != null;
            if (discount == gift || (gift && (rule.giftProductId() == null || rule.giftQty() == null
                    || rule.giftQty() <= 0))) {
                throw new ApiProblem("INVALID_PROMOTION_RULE",
                        "Mỗi quy tắc cần đúng một ưu đãi: chiết khấu hoặc hàng tặng.", HttpStatus.BAD_REQUEST);
            }
            Boolean valid = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM product p
                        JOIN unit_conversion uc ON uc.tenant_id = p.tenant_id AND uc.product_id = p.id
                        WHERE p.tenant_id = ? AND p.id = ? AND uc.unit_code = ?
                    """, Boolean.class, tenantId, rule.productId(), rule.unitCode().strip().toUpperCase());
            if (!Boolean.TRUE.equals(valid)) {
                throw new ApiProblem("INVALID_PRODUCT_UNIT", "SKU hoặc đơn vị CTKM không thuộc danh mục NSX.",
                        HttpStatus.BAD_REQUEST);
            }
            if (rule.giftProductId() != null) {
                Boolean validGift = jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM product gp
                            JOIN unit_conversion gu ON gu.tenant_id = gp.tenant_id AND gu.product_id = gp.id
                            WHERE gp.tenant_id = ? AND gp.id = ? AND gu.unit_code = ?
                        )
                        """, Boolean.class, tenantId, rule.giftProductId(),
                        rule.unitCode().strip().toUpperCase());
                if (!Boolean.TRUE.equals(validGift)) {
                    throw new ApiProblem("INVALID_PRODUCT_UNIT", "SKU hoặc đơn vị hàng tặng không hợp lệ.",
                            HttpStatus.BAD_REQUEST);
                }
            }
        }
    }

    private UUID resolveNpp(
            Jwt jwt, CatalogAccessService.Actor actor, UUID requestedNppId, UUID outletId, OffsetDateTime at) {
        return switch (actor.tenantType()) {
            case "NSX" -> {
                if (requestedNppId != null) {
                    access.requireRelatedNpp(actor, requestedNppId, access.businessDate(at));
                }
                Map<String, Object> permissions = jwt.getClaimAsMap("permission_scopes");
                String scope = permissions == null ? null : (String) permissions.get("promotion:view");
                if (!"A".equals(scope) && outletId == null) {
                    throw new ApiProblem("OUTLET_CONTEXT_REQUIRED",
                            "Cần truyền điểm bán để áp dụng phạm vi phân quyền.", HttpStatus.BAD_REQUEST);
                }
                if (outletId != null) {
                    if (requestedNppId == null) {
                        throw new ApiProblem("NPP_REQUIRED", "Cần truyền NPP khi lọc theo điểm bán.",
                                HttpStatus.BAD_REQUEST);
                    }
                    access.requireOutletServedBy(requestedNppId, outletId, access.businessDate(at));
                    access.requireScopedOutlet(jwt, "promotion:view", outletId);
                }
                yield requestedNppId;
            }
            case "NPP" -> {
                if (requestedNppId != null && !requestedNppId.equals(actor.tenantId())) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "NPP nằm ngoài phạm vi tài khoản.");
                }
                Map<String, Object> permissions = jwt.getClaimAsMap("permission_scopes");
                String scope = permissions == null ? null : (String) permissions.get("promotion:view");
                if (!"A".equals(scope) && outletId == null) {
                    throw new ApiProblem("OUTLET_CONTEXT_REQUIRED",
                            "Cần truyền điểm bán để áp dụng phạm vi phân quyền.", HttpStatus.BAD_REQUEST);
                }
                if (outletId != null) {
                    access.requireOutletServedBy(actor.tenantId(), outletId, access.businessDate(at));
                    access.requireScopedOutlet(jwt, "promotion:view", outletId);
                }
                yield actor.tenantId();
            }
            case "OUTLET" -> {
                if (requestedNppId == null || outletId == null) {
                    throw new ApiProblem("OUTLET_CONTEXT_REQUIRED", "Cần truyền NPP và điểm bán.",
                            HttpStatus.BAD_REQUEST);
                }
                access.requireOutletOwner(actor, outletId);
                access.requireOutletServedBy(requestedNppId, outletId, access.businessDate(at));
                yield requestedNppId;
            }
            default -> throw new org.springframework.security.access.AccessDeniedException(
                    "Tài khoản không có phạm vi CTKM.");
        };
    }

    private boolean hasPermission(Jwt jwt, String permission) {
        Map<String, Object> values = jwt.getClaimAsMap("permission_scopes");
        return values != null && values.containsKey(permission);
    }

    private PromotionSummary find(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT id, code, name, status, effective_from, effective_to, version
                FROM promotion WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> summary(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("status"),
                rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class), rs.getLong("version")),
                tenantId, id).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("PROMOTION_NOT_FOUND", "Không tìm thấy CTKM.",
                        HttpStatus.NOT_FOUND));
    }

    private UUID createdBy(UUID tenantId, UUID id) {
        return jdbc.queryForObject("SELECT created_by FROM promotion WHERE tenant_id = ? AND id = ?",
                UUID.class, tenantId, id);
    }

    private ApiProblem versionConflict(UUID id) {
        return new ApiProblem("VERSION_CONFLICT", "Dữ liệu đã được người khác cập nhật.",
                HttpStatus.CONFLICT, Map.of("resource", "promotion", "id", id.toString()));
    }

    private static PromotionSummary summary(
            UUID id, String code, String name, String status,
            OffsetDateTime from, OffsetDateTime to, long version) {
        return new PromotionSummary(id, code, name, status, from, to, version);
    }

    public record PromotionRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80) String code,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String name,
            @jakarta.validation.constraints.NotEmpty
            List<@jakarta.validation.constraints.NotNull @jakarta.validation.Valid PromotionRule> rules,
            @jakarta.validation.Valid List<CatalogScopeService.CatalogScope> scopes,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record PromotionRule(
            @jakarta.validation.constraints.NotNull UUID productId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 32) String unitCode,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long minOrderQty,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(10000)
            Integer discountBasisPoints,
            UUID giftProductId,
            @jakarta.validation.constraints.Positive Long giftQty) {
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

    public record PromotionSummary(
            UUID id, String code, String name, String status,
            OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo, long version) {
    }

    public record PromotionDetail(
            PromotionSummary promotion,
            List<PromotionRule> rules,
            List<CatalogScopeService.CatalogScope> scopes) {
    }

    private record EffectivePeriod(OffsetDateTime from, OffsetDateTime to) {
    }
}
