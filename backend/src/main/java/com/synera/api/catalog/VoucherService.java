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
public class VoucherService {
    private final JdbcTemplate jdbc;
    private final CatalogAccessService access;
    private final CatalogScopeService catalogScopes;
    private final ScopedCatalogWorkflowPolicy workflowPolicy;
    private final AuditService audit;

    public VoucherService(
            JdbcTemplate jdbc, CatalogAccessService access,
            CatalogScopeService catalogScopes, ScopedCatalogWorkflowPolicy workflowPolicy, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.catalogScopes = catalogScopes;
        this.workflowPolicy = workflowPolicy;
        this.audit = audit;
    }

    public ApiPage<VoucherSummary> list(
            Jwt jwt, UUID requestedNppId, UUID outletId, int page, int size) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID nppId = resolveNpp(jwt, actor, requestedNppId, outletId, now);
        boolean nsx = "NSX".equals(actor.tenantType());
        boolean privileged = hasPermission(jwt, "promotion:manage") || hasPermission(jwt, "promotion:approve");
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (nsx) {
            where.append("v.tenant_id = ?");
            args.add(actor.tenantId());
        } else {
            where.append("""
                    EXISTS (
                        SELECT 1 FROM tenant_relation tr
                        WHERE tr.nsx_tenant_id = v.tenant_id AND tr.npp_tenant_id = ?
                          AND tr.status = 'ACTIVE' AND tr.effective_from <= ?::date
                          AND (tr.effective_to IS NULL OR tr.effective_to >= ?::date)
                    )
                    """);
            args.add(nppId);
            args.add(access.businessDate(now));
            args.add(access.businessDate(now));
            where.append(" AND v.status = 'EFFECTIVE' AND v.effective_from <= ?")
                    .append(" AND (v.effective_to IS NULL OR v.effective_to >= ?)");
            args.add(now);
            args.add(now);
        }
        if (!nsx || outletId != null || requestedNppId != null) {
            where.append(" AND ")
                    .append(catalogScopes.matchesPredicate("voucher_scope", "voucher_id", "v"));
            args.addAll(catalogScopes.matchArguments(nppId, outletId, now));
        }
        if (nsx && !privileged) {
            where.append(" AND v.status = 'EFFECTIVE' AND v.effective_from <= ?")
                    .append(" AND (v.effective_to IS NULL OR v.effective_to >= ?)");
            args.add(now);
            args.add(now);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM voucher v WHERE " + where,
                Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<VoucherSummary> items = jdbc.query("""
                SELECT v.id, v.code, v.name, v.status, v.effective_from, v.effective_to,
                    v.discount_amount_vnd, v.discount_basis_points, v.version
                FROM voucher v WHERE """ + where + """
                ORDER BY v.name, v.code LIMIT ? OFFSET ?
                """, (rs, row) -> summary(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("status"), rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class),
                (Long) rs.getObject("discount_amount_vnd"), (Integer) rs.getObject("discount_basis_points"),
                rs.getLong("version")), args.toArray());
        return ApiPage.of(items, page, size, total);
    }

    public VoucherDetail getForNsx(
            Jwt jwt, UUID id, UUID nppId, UUID outletId, HttpServletRequest servletRequest) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        VoucherDetail detail = detail(actor.tenantId(), id);
        String permission = hasPermission(jwt, "promotion:approve")
                ? "promotion:approve"
                : "promotion:manage";
        workflowPolicy.requireRecordAccess(
                jwt, permission, "voucher_scope", "voucher_id", actor.tenantId(), id, nppId, outletId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), permission, "VOUCHER_VIEWED",
                "voucher", id, null, detail.voucher(), "Tra cứu voucher",
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return detail;
    }

    private VoucherDetail detail(UUID tenantId, UUID id) {
        VoucherSummary summary = find(tenantId, id);
        List<CatalogScopeService.CatalogScope> scopes = jdbc.query("""
                SELECT scope_type, scope_id FROM voucher_scope
                WHERE tenant_id = ? AND voucher_id = ? ORDER BY scope_type, scope_id
                """, (rs, row) -> new CatalogScopeService.CatalogScope(
                rs.getString("scope_type"), rs.getObject("scope_id", UUID.class)), tenantId, id);
        return new VoucherDetail(summary, scopes);
    }

    @Transactional
    public VoucherDetail create(Jwt jwt, VoucherRequest request, HttpServletRequest servletRequest) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        if ((request.discountAmountVnd() == null) == (request.discountBasisPoints() == null)) {
            throw new ApiProblem("INVALID_VOUCHER_DISCOUNT",
                    "Cần nhập đúng một loại giảm giá: số tiền hoặc tỷ lệ.", HttpStatus.BAD_REQUEST);
        }
        List<CatalogScopeService.CatalogScope> scopes = request.scopes() == null ? List.of() : request.scopes();
        catalogScopes.validate(actor, scopes);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO voucher (
                    id, tenant_id, code, name, status, discount_amount_vnd,
                    discount_basis_points, created_by
                ) VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                """, id, actor.tenantId(), request.code().strip(), request.name().strip(),
                request.discountAmountVnd(), request.discountBasisPoints(), actor.userId());
        for (CatalogScopeService.CatalogScope scope : scopes) {
            jdbc.update("""
                    INSERT INTO voucher_scope (id, tenant_id, voucher_id, scope_type, scope_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), actor.tenantId(), id, scope.scopeType(), scope.scopeId());
        }
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "promotion:manage", "VOUCHER_CREATED",
                "voucher", id, null, request, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return detail(actor.tenantId(), id);
    }

    @Transactional
    public VoucherDetail submit(
            Jwt jwt, UUID id, UUID nppId, UUID outletId,
            WorkflowRequest request, HttpServletRequest servletRequest) {
        return transition(jwt, id, request.version(), request.reason(), servletRequest,
                "DRAFT", "PENDING_APPROVAL", "VOUCHER_SUBMITTED", "promotion:manage", false, null,
                nppId, outletId);
    }

    @Transactional
    public VoucherDetail approve(
            Jwt jwt, UUID id, UUID nppId, UUID outletId,
            WorkflowRequest request, HttpServletRequest servletRequest) {
        return transition(jwt, id, request.version(), request.reason(), servletRequest,
                "PENDING_APPROVAL", "APPROVED", "VOUCHER_APPROVED", "promotion:approve", true, null,
                nppId, outletId);
    }

    @Transactional
    public VoucherDetail reject(
            Jwt jwt, UUID id, UUID nppId, UUID outletId,
            WorkflowRequest request, HttpServletRequest servletRequest) {
        return transition(jwt, id, request.version(), request.reason(), servletRequest,
                "PENDING_APPROVAL", "REJECTED", "VOUCHER_REJECTED", "promotion:approve", true, null,
                nppId, outletId);
    }

    @Transactional
    public VoucherDetail activate(
            Jwt jwt, UUID id, UUID nppId, UUID outletId,
            ActivateRequest request, HttpServletRequest servletRequest) {
        if (!request.effectiveFrom().isBefore(request.effectiveTo())) {
            throw new ApiProblem("INVALID_EFFECTIVE_PERIOD", "Ngày kết thúc phải sau ngày bắt đầu.",
                    HttpStatus.BAD_REQUEST);
        }
        return transition(jwt, id, request.version(), request.reason(), servletRequest,
                "APPROVED", "EFFECTIVE", "VOUCHER_ACTIVATED", "promotion:approve", false,
                new EffectivePeriod(request.effectiveFrom(), request.effectiveTo()), nppId, outletId);
    }

    private VoucherDetail transition(
            Jwt jwt, UUID id, long version, String reason, HttpServletRequest servletRequest,
            String expectedStatus, String nextStatus, String action, String permission, boolean approval,
            EffectivePeriod effectivePeriod, UUID nppId, UUID outletId) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        VoucherSummary before = find(actor.tenantId(), id);
        workflowPolicy.requireRecordAccess(
                jwt, permission, "voucher_scope", "voucher_id", actor.tenantId(), id, nppId, outletId);
        if (!expectedStatus.equals(before.status())) {
            throw new ApiProblem("INVALID_WORKFLOW_STATE", "Không thể chuyển trạng thái voucher.",
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
        if ("APPROVED".equals(nextStatus)) {
            changed = jdbc.update("""
                    UPDATE voucher SET status = ?, approved_by = ?, updated_at = CURRENT_TIMESTAMP,
                        version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = ?
                    """, nextStatus, actor.userId(), actor.tenantId(), id, version, expectedStatus);
        } else if (effectivePeriod != null) {
            changed = jdbc.update("""
                    UPDATE voucher SET status = ?, effective_from = ?, effective_to = ?,
                        updated_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = ?
                      AND approved_by IS NOT NULL AND approved_by <> created_by
                    """, nextStatus, effectivePeriod.from(), effectivePeriod.to(),
                    actor.tenantId(), id, version, expectedStatus);
        } else {
            changed = jdbc.update("""
                    UPDATE voucher SET status = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = ?
                    """, nextStatus, actor.tenantId(), id, version, expectedStatus);
        }
        if (changed == 0) {
            throw versionConflict(id);
        }
        VoucherSummary after = find(actor.tenantId(), id);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), permission, action,
                "voucher", id, before, after, reason, servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return detail(actor.tenantId(), id);
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
                    "Tài khoản không có phạm vi voucher.");
        };
    }

    private boolean hasPermission(Jwt jwt, String permission) {
        Map<String, Object> values = jwt.getClaimAsMap("permission_scopes");
        return values != null && values.containsKey(permission);
    }

    private VoucherSummary find(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT id, code, name, status, effective_from, effective_to,
                    discount_amount_vnd, discount_basis_points, version
                FROM voucher WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> summary(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("status"),
                rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class),
                (Long) rs.getObject("discount_amount_vnd"), (Integer) rs.getObject("discount_basis_points"),
                rs.getLong("version")), tenantId, id).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("VOUCHER_NOT_FOUND", "Không tìm thấy voucher.",
                        HttpStatus.NOT_FOUND));
    }

    private UUID createdBy(UUID tenantId, UUID id) {
        return jdbc.queryForObject("SELECT created_by FROM voucher WHERE tenant_id = ? AND id = ?",
                UUID.class, tenantId, id);
    }

    private ApiProblem versionConflict(UUID id) {
        return new ApiProblem("VERSION_CONFLICT", "Dữ liệu đã được người khác cập nhật.",
                HttpStatus.CONFLICT, Map.of("resource", "voucher", "id", id.toString()));
    }

    private static VoucherSummary summary(
            UUID id, String code, String name, String status, OffsetDateTime from, OffsetDateTime to,
            Long amount, Integer basisPoints, long version) {
        return new VoucherSummary(id, code, name, status, from, to, amount, basisPoints, version);
    }

    public record VoucherRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80) String code,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String name,
            @jakarta.validation.constraints.PositiveOrZero Long discountAmountVnd,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(10000)
            Integer discountBasisPoints,
            @jakarta.validation.Valid List<CatalogScopeService.CatalogScope> scopes,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
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

    public record VoucherSummary(
            UUID id, String code, String name, String status, OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo, Long discountAmountVnd, Integer discountBasisPoints, long version) {
    }

    public record VoucherDetail(VoucherSummary voucher, List<CatalogScopeService.CatalogScope> scopes) {
    }

    private record EffectivePeriod(OffsetDateTime from, OffsetDateTime to) {
    }
}
