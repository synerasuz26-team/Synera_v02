package com.synera.api.order;

import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.ApiPage;
import com.synera.api.organization.BusinessAccessService;
import com.synera.api.partner.PartnerAccessService;
import com.synera.api.security.AuditService;
import com.synera.api.security.ResolvedScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DemandTargetService {
    private static final List<String> TARGET_TYPES = List.of(
            "NPP_SELLOUT_QTY", "OUTLET_SELLOUT_QTY", "USER_SELLOUT_QTY",
            "ORG_UNIT_SELLOUT_QTY", "ROUTE_SELLOUT_QTY");

    private final JdbcTemplate jdbc;
    private final BusinessAccessService access;
    private final PartnerAccessService partnerAccess;
    private final AuditService audit;

    public DemandTargetService(
            JdbcTemplate jdbc, BusinessAccessService access,
            PartnerAccessService partnerAccess, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.partnerAccess = partnerAccess;
        this.audit = audit;
    }

    public ApiPage<DemandPlanView> listDemandPlans(
            Jwt jwt, UUID requestedNppId, LocalDate from, LocalDate to, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiProblem("DATE_RANGE_INVALID",
                    "Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.", HttpStatus.BAD_REQUEST);
        }
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.resolve(jwt, "demand_plan:view");
        NppFilter nppFilter = nppFilter(jwt, actor, "demand_plan:view", requestedNppId, "dp");
        StringBuilder where = new StringBuilder("dp.tenant_id = dp.npp_tenant_id AND ")
                .append(nppFilter.sql());
        List<Object> args = new ArrayList<>(nppFilter.arguments());
        if (from != null) {
            where.append(" AND dp.planned_for >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND dp.planned_for <= ?");
            args.add(to);
        }
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM demand_plan dp
                JOIN product p ON p.id = dp.product_id
                WHERE
                """ + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<DemandPlanView> rows = jdbc.query("""
                SELECT dp.id, dp.npp_tenant_id, n.name AS npp_name, dp.product_id,
                    p.sku, p.name AS product_name, dp.unit_code, dp.quantity,
                    dp.planned_for, dp.status, dp.created_by, dp.created_at, dp.version
                FROM demand_plan dp
                JOIN tenant n ON n.id = dp.npp_tenant_id
                JOIN product p ON p.id = dp.product_id
                WHERE
                """ + where + """
                ORDER BY dp.planned_for DESC, dp.created_at DESC, dp.id
                LIMIT ? OFFSET ?
                """, (rs, row) -> new DemandPlanView(
                rs.getObject("id", UUID.class), rs.getObject("npp_tenant_id", UUID.class),
                rs.getString("npp_name"), rs.getObject("product_id", UUID.class),
                rs.getString("sku"), rs.getString("product_name"), rs.getString("unit_code"),
                rs.getLong("quantity"), rs.getObject("planned_for", LocalDate.class),
                rs.getString("status"), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getLong("version")),
                args.toArray());
        return ApiPage.of(rows, page, size, total == null ? 0 : total);
    }

    @Transactional
    public DemandPlanView createDemandPlan(
            Jwt jwt, DemandPlanRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        UUID nppId = requireNpp(jwt, actor, "demand_plan:submit", request.nppTenantId());
        ProductUnit product = requireProductUnit(actor, nppId, request.sku(), request.unitCode());
        long quantity;
        try {
            quantity = Math.multiplyExact(request.quantity(), product.baseUnits());
        } catch (ArithmeticException exception) {
            throw new ApiProblem("QUANTITY_OVERFLOW",
                    "Số lượng kế hoạch sau quy đổi vượt giới hạn cho phép.", HttpStatus.BAD_REQUEST);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO demand_plan (
                    id, tenant_id, npp_tenant_id, product_id, unit_code, quantity,
                    planned_for, status, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?)
                """, id, nppId, nppId, product.id(), product.baseUnitCode(), quantity,
                request.plannedFor(), actor.userId());
        DemandPlanView result = loadDemandPlan(id, nppId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "demand_plan:submit",
                "DEMAND_PLAN_SUBMITTED", "demand_plan", id, null, result,
                request.reason(), servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    public ApiPage<TargetView> listTargets(
            Jwt jwt, UUID periodId, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ResolvedScope scope = access.resolve(jwt, "target:view");
        TargetFilter filter = targetFilter(scope, "t");
        List<Object> args = new ArrayList<>(filter.arguments());
        String where = filter.sql();
        if (periodId != null) {
            where += " AND t.period_id = ?";
            args.add(periodId);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM target t WHERE " + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<TargetView> rows = jdbc.query("""
                SELECT t.id, t.target_code, t.target_type, t.subject_id, t.period_id,
                    t.target_qty, t.target_amount_vnd, t.created_by, t.approved_by,
                    t.created_at, t.updated_at,
                    COALESCE(actual.quantity, 0) AS actual_qty,
                    CASE WHEN t.target_qty > 0
                        THEN ROUND(COALESCE(actual.quantity, 0)::numeric * 100 / t.target_qty, 2)
                        ELSE NULL END AS completion_percent
                FROM target t
                LEFT JOIN period p ON p.id = t.period_id AND p.tenant_id = t.tenant_id
                LEFT JOIN LATERAL (
                    SELECT SUM(s.quantity) AS quantity
                    FROM sellout_record s
                    WHERE (
                        (t.target_type = 'NPP_SELLOUT_QTY' AND s.npp_tenant_id = t.subject_id)
                        OR (t.target_type = 'OUTLET_SELLOUT_QTY' AND s.outlet_id = t.subject_id)
                        OR (t.target_type = 'USER_SELLOUT_QTY' AND s.created_by = t.subject_id)
                        OR (t.target_type = 'ROUTE_SELLOUT_QTY' AND EXISTS (
                            SELECT 1 FROM route_outlet ro
                            WHERE ro.tenant_id = t.tenant_id AND ro.route_id = t.subject_id
                              AND ro.outlet_id = s.outlet_id
                              AND ro.effective_from <= (s.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                              AND (ro.effective_to IS NULL OR ro.effective_to >=
                                  (s.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
                        ))
                        OR (t.target_type = 'ORG_UNIT_SELLOUT_QTY' AND EXISTS (
                            SELECT 1 FROM route_outlet ro
                            JOIN route r ON r.tenant_id = ro.tenant_id AND r.id = ro.route_id
                            JOIN org_unit ou ON ou.tenant_id = r.tenant_id AND ou.id = r.org_unit_id
                            WHERE ro.tenant_id = t.tenant_id AND ou.id = t.subject_id
                              AND ro.outlet_id = s.outlet_id
                              AND ro.effective_from <= (s.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                              AND (ro.effective_to IS NULL OR ro.effective_to >=
                                  (s.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
                        ))
                    )
                      AND (t.period_id IS NULL OR (p.id IS NOT NULL AND (
                          s.occurred_at >= (p.starts_on::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')
                          AND s.occurred_at < ((p.ends_on + 1)::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')
                      )))
                      AND (
                          s.npp_tenant_id = t.tenant_id
                          OR EXISTS (
                              SELECT 1 FROM tenant_relation tr
                              WHERE tr.nsx_tenant_id = t.tenant_id
                                AND tr.npp_tenant_id = s.npp_tenant_id
                                AND tr.status = 'ACTIVE'
                                AND tr.effective_from <=
                                    (s.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                                AND (tr.effective_to IS NULL OR tr.effective_to >=
                                    (s.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
                          )
                      )
                ) actual ON TRUE
                WHERE
                """ + where + """
                ORDER BY t.created_at DESC, t.id
                LIMIT ? OFFSET ?
                """, (rs, row) -> new TargetView(
                rs.getObject("id", UUID.class), rs.getString("target_code"),
                rs.getString("target_type"), rs.getObject("subject_id", UUID.class),
                rs.getObject("period_id", UUID.class), (Long) rs.getObject("target_qty"),
                (Long) rs.getObject("target_amount_vnd"), rs.getLong("actual_qty"),
                rs.getBigDecimal("completion_percent"), rs.getObject("created_by", UUID.class),
                rs.getObject("approved_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)), args.toArray());
        return ApiPage.of(rows, page, size, total == null ? 0 : total);
    }

    @Transactional
    public TargetView createTarget(
            Jwt jwt, TargetRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ResolvedScope scope = access.resolve(jwt, "target:manage");
        String targetType = request.targetType().strip().toUpperCase(Locale.ROOT);
        if (!TARGET_TYPES.contains(targetType)) {
            throw new ApiProblem("TARGET_TYPE_INVALID",
                    "Loại chỉ tiêu không được hỗ trợ.", HttpStatus.BAD_REQUEST,
                    Map.of("supportedTypes", TARGET_TYPES));
        }
        if (request.targetQty() == null && request.targetAmountVnd() == null) {
            throw new ApiProblem("TARGET_VALUE_REQUIRED",
                    "Cần nhập chỉ tiêu số lượng hoặc doanh số.", HttpStatus.BAD_REQUEST);
        }
        validateTargetSubject(jwt, actor, scope, targetType, request.subjectId());
        if (request.periodId() != null) {
            Boolean periodExists = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM period WHERE id = ? AND tenant_id = ?)
                    """, Boolean.class, request.periodId(), actor.tenantId());
            if (!Boolean.TRUE.equals(periodExists)) {
                throw new ApiProblem("PERIOD_NOT_FOUND",
                        "Kỳ chốt không tồn tại trong đơn vị.", HttpStatus.BAD_REQUEST);
            }
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO target (
                    id, tenant_id, target_code, target_type, subject_id, period_id,
                    target_qty, target_amount_vnd, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, actor.tenantId(), request.targetCode().strip(), targetType,
                request.subjectId(), request.periodId(), request.targetQty(),
                request.targetAmountVnd(), actor.userId());
        TargetView result = loadTarget(id, actor.tenantId());
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "target:manage",
                "TARGET_CREATED", "target", id, null, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    private NppFilter nppFilter(
            Jwt jwt, BusinessAccessService.Actor actor, String permission,
            UUID requestedNppId, String alias) {
        if ("NPP".equals(actor.tenantType())) {
            if (requestedNppId != null && !requestedNppId.equals(actor.tenantId())) {
                throw new AccessDeniedException("NPP chỉ được truy cập kế hoạch của chính mình.");
            }
            return new NppFilter(alias + ".npp_tenant_id = ?", List.of(actor.tenantId()));
        }
        if (!"NSX".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chức năng này chỉ dành cho NSX hoặc NPP.");
        }
        PartnerAccessService.Filter npps = partnerAccess.npps(jwt, actor, permission, "n");
        List<Object> args = new ArrayList<>(npps.arguments());
        String sql = alias + ".npp_tenant_id IN (SELECT n.id FROM tenant n WHERE " + npps.sql() + ")";
        if (requestedNppId != null) {
            partnerAccess.requireNpp(jwt, actor, permission, requestedNppId);
            sql += " AND " + alias + ".npp_tenant_id = ?";
            args.add(requestedNppId);
        }
        return new NppFilter(sql, args);
    }

    private UUID requireNpp(
            Jwt jwt, BusinessAccessService.Actor actor, String permission, UUID requestedNppId) {
        if ("NPP".equals(actor.tenantType())) {
            if (requestedNppId != null && !requestedNppId.equals(actor.tenantId())) {
                throw new AccessDeniedException("NPP chỉ được ghi kế hoạch của chính mình.");
            }
            return actor.tenantId();
        }
        if ("NSX".equals(actor.tenantType()) && requestedNppId != null) {
            partnerAccess.requireNpp(jwt, actor, permission, requestedNppId);
            return requestedNppId;
        }
        throw new AccessDeniedException("Cần chọn NPP thuộc phạm vi được phân quyền.");
    }

    private ProductUnit requireProductUnit(
            BusinessAccessService.Actor actor, UUID nppId, String sku, String unitCode) {
        LocalDate businessDate = access.businessToday();
        String nsxRestriction = "NSX".equals(actor.tenantType()) ? " AND p.tenant_id = ?" : "";
        List<Object> args = new ArrayList<>(List.of(
                sku.strip().toLowerCase(Locale.ROOT), unitCode.strip().toLowerCase(Locale.ROOT),
                nppId, businessDate, businessDate));
        if ("NSX".equals(actor.tenantType())) {
            args.add(actor.tenantId());
        }
        List<ProductUnit> matches = jdbc.query("""
                SELECT p.id, p.base_unit_code, uc.base_units
                FROM product p
                JOIN unit_conversion uc ON uc.tenant_id = p.tenant_id AND uc.product_id = p.id
                WHERE LOWER(p.sku) = ? AND LOWER(uc.unit_code) = ? AND p.status = 'ACTIVE'
                  AND EXISTS (
                      SELECT 1 FROM tenant_relation tr
                      WHERE tr.nsx_tenant_id = p.tenant_id AND tr.npp_tenant_id = ?
                        AND tr.status = 'ACTIVE' AND tr.effective_from <= ?
                        AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                  )
                """ + nsxRestriction + " ORDER BY p.id",
                (rs, row) -> new ProductUnit(rs.getObject("id", UUID.class),
                        rs.getString("base_unit_code"), rs.getLong("base_units")), args.toArray());
        if (matches.isEmpty()) {
            throw new ApiProblem("PRODUCT_OR_UNIT_NOT_FOUND",
                    "Không tìm thấy SKU/đơn vị trong danh mục đang liên kết.", HttpStatus.BAD_REQUEST);
        }
        if (matches.size() > 1) {
            throw new ApiProblem("PRODUCT_SKU_AMBIGUOUS",
                    "SKU trùng ở nhiều NSX liên kết; không thể xác định sản phẩm.",
                    HttpStatus.BAD_REQUEST, Map.of("sku", sku));
        }
        return matches.get(0);
    }

    private void validateTargetSubject(
            Jwt jwt, BusinessAccessService.Actor actor, ResolvedScope scope,
            String targetType, UUID subjectId) {
        if (subjectId == null) {
            throw new ApiProblem("TARGET_SUBJECT_REQUIRED",
                    "Cần chọn đối tượng áp dụng chỉ tiêu.", HttpStatus.BAD_REQUEST);
        }
        switch (targetType) {
            case "NPP_SELLOUT_QTY" -> {
                if ("NPP".equals(actor.tenantType())) {
                    if (!subjectId.equals(actor.tenantId())) {
                        throw new AccessDeniedException("NPP chỉ được tạo chỉ tiêu cho chính mình.");
                    }
                } else {
                    partnerAccess.requireNpp(jwt, actor, "target:manage", subjectId);
                }
            }
            case "OUTLET_SELLOUT_QTY" -> partnerAccess.requireOutlet(
                    jwt, actor, "target:manage", subjectId);
            case "ORG_UNIT_SELLOUT_QTY" -> access.requireOrgUnitAccess(
                    jwt, "target:manage", subjectId);
            case "ROUTE_SELLOUT_QTY" -> access.requireRouteAccess(
                    jwt, "target:manage", subjectId);
            case "USER_SELLOUT_QTY" -> {
                Boolean assigned = jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM user_role
                            WHERE tenant_id = ? AND user_id = ?
                        )
                        """, Boolean.class, actor.tenantId(), subjectId);
                boolean inScope = "A".equals(scope.scope())
                        || ("O".equals(scope.scope()) && subjectId.equals(actor.userId()));
                if ("R".equals(scope.scope())) {
                    var orgFilter = access.orgUnitFilter(scope, "ou");
                    List<Object> args = new ArrayList<>();
                    args.add(actor.tenantId());
                    args.add(subjectId);
                    args.addAll(orgFilter.arguments());
                    inScope = Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS (
                                SELECT 1 FROM user_org_unit uou
                                JOIN org_unit ou ON ou.tenant_id = uou.tenant_id
                                    AND ou.id = uou.org_unit_id
                                WHERE uou.tenant_id = ? AND uou.user_id = ? AND
                            """ + orgFilter.sql() + ")", Boolean.class, args.toArray()));
                } else if ("T".equals(scope.scope()) && !scope.routeIds().isEmpty()) {
                    List<UUID> routeIds = scope.routeIds().stream().sorted().toList();
                    List<Object> args = new ArrayList<>();
                    args.add(actor.tenantId());
                    args.add(subjectId);
                    args.addAll(routeIds);
                    inScope = Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS (
                                SELECT 1 FROM user_route
                                WHERE tenant_id = ? AND user_id = ? AND route_id IN (
                            """ + placeholders(routeIds.size()) + "))", Boolean.class, args.toArray()));
                }
                if (!Boolean.TRUE.equals(assigned) || !inScope) {
                    throw new AccessDeniedException("Người dùng nằm ngoài phạm vi chỉ tiêu.");
                }
            }
            default -> throw new ApiProblem("TARGET_TYPE_INVALID",
                    "Loại chỉ tiêu không được hỗ trợ.", HttpStatus.BAD_REQUEST);
        }
    }

    private TargetFilter targetFilter(ResolvedScope scope, String alias) {
        List<Object> args = new ArrayList<>();
        args.add(scope.tenantId());
        if ("A".equals(scope.scope())) {
            return new TargetFilter(alias + ".tenant_id = ?", args);
        }
        if ("O".equals(scope.scope())) {
            args.add("USER_SELLOUT_QTY");
            args.add(scope.ownerId());
            return new TargetFilter(alias + ".tenant_id = ? AND " + alias
                    + ".target_type = ? AND " + alias + ".subject_id = ?", args);
        }
        if ("T".equals(scope.scope())) {
            List<UUID> routeIds = scope.routeIds().stream().sorted().toList();
            if (routeIds.isEmpty()) {
                return new TargetFilter(alias + ".tenant_id = ? AND FALSE", args);
            }
            args.add("ROUTE_SELLOUT_QTY");
            args.addAll(routeIds);
            return new TargetFilter(alias + ".tenant_id = ? AND " + alias + ".target_type = ? AND "
                    + alias + ".subject_id IN (" + placeholders(routeIds.size()) + ")", args);
        }
        var orgFilter = access.orgUnitFilter(scope, "ou");
        args.add("ORG_UNIT_SELLOUT_QTY");
        args.addAll(orgFilter.arguments());
        return new TargetFilter(alias + ".tenant_id = ? AND " + alias + ".target_type = ? AND EXISTS ("
                + "SELECT 1 FROM org_unit ou WHERE ou.id = " + alias + ".subject_id AND "
                + orgFilter.sql() + ")", args);
    }

    private DemandPlanView loadDemandPlan(UUID id, UUID tenantId) {
        return jdbc.query("""
                SELECT dp.id, dp.npp_tenant_id, n.name AS npp_name, dp.product_id,
                    p.sku, p.name AS product_name, dp.unit_code, dp.quantity,
                    dp.planned_for, dp.status, dp.created_by, dp.created_at, dp.version
                FROM demand_plan dp
                JOIN tenant n ON n.id = dp.npp_tenant_id
                JOIN product p ON p.id = dp.product_id
                WHERE dp.id = ? AND dp.tenant_id = ?
                """, rs -> rs.next() ? new DemandPlanView(
                rs.getObject("id", UUID.class), rs.getObject("npp_tenant_id", UUID.class),
                rs.getString("npp_name"), rs.getObject("product_id", UUID.class),
                rs.getString("sku"), rs.getString("product_name"), rs.getString("unit_code"),
                rs.getLong("quantity"), rs.getObject("planned_for", LocalDate.class),
                rs.getString("status"), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getLong("version")) : null,
                id, tenantId);
    }

    private TargetView loadTarget(UUID id, UUID tenantId) {
        TargetRow row = jdbc.query("""
                SELECT id, target_code, target_type, subject_id, period_id, target_qty,
                    target_amount_vnd, created_by, approved_by, created_at, updated_at
                FROM target WHERE id = ? AND tenant_id = ?
                """, rs -> rs.next() ? new TargetRow(
                rs.getObject("id", UUID.class), rs.getString("target_code"),
                rs.getString("target_type"), rs.getObject("subject_id", UUID.class),
                rs.getObject("period_id", UUID.class), (Long) rs.getObject("target_qty"),
                (Long) rs.getObject("target_amount_vnd"), rs.getObject("created_by", UUID.class),
                rs.getObject("approved_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)) : null, id, tenantId);
        if (row == null) {
            return null;
        }
        long actual = actualQuantity(row.targetType(), row.subjectId(), row.periodId(), tenantId);
        java.math.BigDecimal completion = row.targetQty() == null || row.targetQty() == 0
                ? null : java.math.BigDecimal.valueOf(actual)
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(java.math.BigDecimal.valueOf(row.targetQty()), 2, java.math.RoundingMode.HALF_UP);
        return new TargetView(row.id(), row.targetCode(), row.targetType(), row.subjectId(),
                row.periodId(), row.targetQty(), row.targetAmountVnd(), actual, completion,
                row.createdBy(), row.approvedBy(), row.createdAt(), row.updatedAt());
    }

    private long actualQuantity(
            String targetType, UUID subjectId, UUID periodId, UUID tenantId) {
        String scope = switch (targetType) {
            case "NPP_SELLOUT_QTY" -> "s.npp_tenant_id = ?";
            case "OUTLET_SELLOUT_QTY" -> "s.outlet_id = ?";
            case "USER_SELLOUT_QTY" -> "s.created_by = ?";
            case "ROUTE_SELLOUT_QTY" -> """
                    EXISTS (
                        SELECT 1 FROM route_outlet ro
                        WHERE ro.tenant_id = ? AND ro.route_id = ? AND ro.outlet_id = s.outlet_id
                          AND ro.effective_from <= (s.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                          AND (ro.effective_to IS NULL OR ro.effective_to >=
                              (s.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
                    )
                    """;
            case "ORG_UNIT_SELLOUT_QTY" -> """
                    EXISTS (
                        SELECT 1 FROM route_outlet ro
                        JOIN route r ON r.tenant_id = ro.tenant_id AND r.id = ro.route_id
                        WHERE ro.tenant_id = ? AND r.org_unit_id = ? AND ro.outlet_id = s.outlet_id
                          AND ro.effective_from <= (s.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                          AND (ro.effective_to IS NULL OR ro.effective_to >=
                              (s.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
                    )
                    """;
            default -> "FALSE";
        };
        List<Object> args = new ArrayList<>();
        String periodCondition = periodId == null ? "" : """
                AND s.occurred_at >= (p.starts_on::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')
                AND s.occurred_at < ((p.ends_on + 1)::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')
                """;
        String periodJoin = periodId == null ? "" : " JOIN period p ON p.id = ? AND p.tenant_id = ?";
        if (periodId != null) {
            args.add(periodId);
            args.add(tenantId);
        }
        args.add(tenantId);
        args.add(tenantId);
        if ("ROUTE_SELLOUT_QTY".equals(targetType) || "ORG_UNIT_SELLOUT_QTY".equals(targetType)) {
            args.add(tenantId);
        }
        if (!"FALSE".equals(scope)) {
            args.add(subjectId);
        }
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(s.quantity), 0)
                FROM sellout_record s
                """ + periodJoin + """
                WHERE (
                    s.npp_tenant_id = ?
                    OR EXISTS (
                        SELECT 1 FROM tenant_relation tr
                        WHERE tr.nsx_tenant_id = ? AND tr.npp_tenant_id = s.npp_tenant_id
                          AND tr.status = 'ACTIVE'
                    )
                )
                AND
                """ + scope + periodCondition,
                Long.class, args.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private record NppFilter(String sql, List<Object> arguments) {
    }

    private record ProductUnit(UUID id, String baseUnitCode, long baseUnits) {
    }

    private record TargetFilter(String sql, List<Object> arguments) {
    }

    private record TargetRow(
            UUID id, String targetCode, String targetType, UUID subjectId, UUID periodId,
            Long targetQty, Long targetAmountVnd, UUID createdBy, UUID approvedBy,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record DemandPlanRequest(
            UUID nppTenantId, @NotBlank @Size(max = 80) String sku,
            @NotBlank @Size(max = 32) String unitCode, @Positive long quantity,
            @NotNull LocalDate plannedFor, @Size(max = 500) String reason) {
    }

    public record DemandPlanView(
            UUID id, UUID nppTenantId, String nppName, UUID productId, String sku,
            String productName, String unitCode, long quantity, LocalDate plannedFor,
            String status, UUID createdBy, OffsetDateTime createdAt, long version) {
    }

    public record TargetRequest(
            @NotBlank @Size(max = 80) String targetCode,
            @NotBlank @Size(max = 32) String targetType,
            @NotNull UUID subjectId, UUID periodId,
            @PositiveOrZero Long targetQty, @PositiveOrZero Long targetAmountVnd,
            @Size(max = 500) String reason) {
    }

    public record TargetView(
            UUID id, String targetCode, String targetType, UUID subjectId, UUID periodId,
            Long targetQty, Long targetAmountVnd, long actualQty,
            java.math.BigDecimal completionPercent, UUID createdBy, UUID approvedBy,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }
}
