package com.synera.api.order;

import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.ApiPage;
import com.synera.api.organization.BusinessAccessService;
import com.synera.api.partner.PartnerAccessService;
import com.synera.api.security.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReconciliationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbc;
    private final BusinessAccessService access;
    private final PartnerAccessService partnerAccess;
    private final AuditService audit;
    private final OrderFlowService orderFlow;

    public ReconciliationService(
            JdbcTemplate jdbc, BusinessAccessService access,
            PartnerAccessService partnerAccess, AuditService audit, OrderFlowService orderFlow) {
        this.jdbc = jdbc;
        this.access = access;
        this.partnerAccess = partnerAccess;
        this.audit = audit;
        this.orderFlow = orderFlow;
    }

    public ApiPage<ReconciliationView> listReconciliations(
            Jwt jwt, UUID periodId, UUID nppId, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ReconciliationScope scope = reconciliationScope(jwt, actor, "reconciliation:view", "r");
        StringBuilder where = new StringBuilder(scope.sql());
        List<Object> args = new ArrayList<>(scope.arguments());
        if (periodId != null) {
            where.append(" AND r.period_id = ?");
            args.add(periodId);
        }
        if (nppId != null) {
            requireNppAccess(jwt, actor, "reconciliation:view", nppId);
            where.append(" AND r.npp_tenant_id = ?");
            args.add(nppId);
        }
        long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation r WHERE
                """ + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<ReconciliationView> rows = jdbc.query("""
                SELECT r.id, r.tenant_id, r.npp_tenant_id, n.name AS npp_name,
                    r.period_id, r.status, r.ordered_amount_vnd, r.shipped_amount_vnd,
                    r.received_amount_vnd, r.nsx_confirmed_by, r.npp_confirmed_by,
                    r.snapshot_at, r.confirmed_at, r.version
                FROM reconciliation r
                JOIN tenant n ON n.id = r.npp_tenant_id
                WHERE
                """ + where + " ORDER BY r.updated_at DESC, r.id LIMIT ? OFFSET ?",
                (rs, row) -> new ReconciliationView(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("npp_tenant_id", UUID.class), rs.getString("npp_name"),
                        rs.getObject("period_id", UUID.class), rs.getString("status"),
                        rs.getLong("ordered_amount_vnd"), rs.getLong("shipped_amount_vnd"),
                        rs.getLong("received_amount_vnd"), rs.getObject("nsx_confirmed_by", UUID.class),
                        rs.getObject("npp_confirmed_by", UUID.class),
                        rs.getObject("snapshot_at", OffsetDateTime.class),
                        rs.getObject("confirmed_at", OffsetDateTime.class), rs.getLong("version"), null),
                args.toArray());
        return ApiPage.of(rows, page, size, total);
    }

    public ApiPage<ExceptionView> listExceptions(
            Jwt jwt, UUID reconciliationId, String status, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ReconciliationScope scope = reconciliationScope(jwt, actor, "reconciliation:view", "r");
        StringBuilder where = new StringBuilder("r.id = ? AND ").append(scope.sql());
        List<Object> args = new ArrayList<>();
        args.add(reconciliationId);
        args.addAll(scope.arguments());
        if (status != null && !status.isBlank()) {
            where.append(" AND e.status = ?");
            args.add(status.strip().toUpperCase());
        }
        long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation_exception e
                JOIN reconciliation r ON r.id = e.reconciliation_id
                WHERE
                """ + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<ExceptionView> rows = jdbc.query("""
                SELECT e.id, e.reconciliation_id, e.order_si_id, e.order_line_id,
                    e.exception_code, e.description, e.status, e.expected_qty, e.actual_qty,
                    e.resolution,
                    e.assigned_to, e.resolved_by, e.resolved_at, e.version, e.created_at
                FROM reconciliation_exception e
                JOIN reconciliation r ON r.id = e.reconciliation_id
                WHERE
                """ + where + " ORDER BY e.created_at DESC, e.id LIMIT ? OFFSET ?",
                (rs, row) -> new ExceptionView(
                        rs.getObject("id", UUID.class), rs.getObject("reconciliation_id", UUID.class),
                        rs.getObject("order_si_id", UUID.class), rs.getObject("order_line_id", UUID.class),
                        rs.getString("exception_code"), rs.getString("description"), rs.getString("status"),
                        (Long) rs.getObject("expected_qty"), (Long) rs.getObject("actual_qty"),
                        rs.getString("resolution"),
                        rs.getObject("assigned_to", UUID.class), rs.getObject("resolved_by", UUID.class),
                        rs.getObject("resolved_at", OffsetDateTime.class), rs.getLong("version"),
                        rs.getObject("created_at", OffsetDateTime.class)), args.toArray());
        return ApiPage.of(rows, page, size, total);
    }

    public ApiPage<PeriodView> listPeriods(Jwt jwt, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        String scope;
        Object tenantId = actor.tenantId();
        if ("NSX".equals(actor.tenantType())) {
            scope = "tenant_id = ?";
        } else if ("NPP".equals(actor.tenantType())) {
            scope = """
                    EXISTS (SELECT 1 FROM reconciliation r
                        WHERE r.period_id = period.id AND r.npp_tenant_id = ?)
                    """;
        } else {
            throw new AccessDeniedException("Chỉ NSX hoặc NPP được truy cập danh sách kỳ.");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM period WHERE " + scope,
                Long.class, tenantId);
        List<PeriodView> items = jdbc.query("""
                SELECT id, tenant_id, period_type, starts_on, ends_on, status, closed_by, closed_at, version
                FROM period WHERE
                """ + scope + " ORDER BY starts_on DESC, period_type, id LIMIT ? OFFSET ?",
                (rs, row) -> periodView(rs), tenantId, size, (long) page * size);
        return ApiPage.of(items, page, size, total == null ? 0 : total);
    }

    public ApiPage<AdjustmentView> listAdjustments(
            Jwt jwt, UUID periodId, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        List<Object> args = new ArrayList<>();
        String where = "tenant_id = ?";
        args.add(actor.tenantId());
        if (periodId != null) {
            where += " AND period_id = ?";
            args.add(periodId);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM period_adjustment WHERE " + where,
                Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<AdjustmentView> items = jdbc.query("""
                SELECT id, tenant_id, period_id, npp_tenant_id, metric_key, adjustment_code,
                    amount_vnd, reason, status, created_by, approved_by, version, created_at, updated_at
                FROM period_adjustment WHERE
                """ + where + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                (rs, row) -> new AdjustmentView(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("period_id", UUID.class), rs.getObject("npp_tenant_id", UUID.class),
                        rs.getString("metric_key"), rs.getString("adjustment_code"), rs.getLong("amount_vnd"),
                        rs.getString("reason"), rs.getString("status"), rs.getObject("created_by", UUID.class),
                        rs.getObject("approved_by", UUID.class), rs.getLong("version"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)), args.toArray());
        return ApiPage.of(items, page, size, total == null ? 0 : total);
    }

    @Transactional
    public ReconciliationView confirm(
            Jwt jwt, UUID reconciliationId, ActionRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ReconciliationScope scope = reconciliationScope(jwt, actor, "reconciliation:confirm", "r");
        ReconciliationRow row = requireReconciliation(reconciliationId, scope, true);
        if (!"OPEN".equals(row.status()) && !"PARTIALLY_CONFIRMED".equals(row.status())) {
            throw conflict("RECONCILIATION_ALREADY_CONFIRMED", "Đối soát đã được hai bên xác nhận.");
        }
        Long openExceptions = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation_exception
                WHERE tenant_id = ? AND reconciliation_id = ? AND status = 'OPEN'
                """, Long.class, row.tenantId(), row.id());
        if (openExceptions != null && openExceptions > 0) {
            throw conflict("RECONCILIATION_HAS_OPEN_EXCEPTIONS",
                    "Cần xử lý hết ngoại lệ trước khi xác nhận đối soát.");
        }
        String confirmationColumn;
        String tenantColumn;
        if ("NSX".equals(actor.tenantType()) && actor.tenantId().equals(row.tenantId())) {
            confirmationColumn = "nsx_confirmed_by";
            tenantColumn = "tenant_id";
        } else if ("NPP".equals(actor.tenantType()) && actor.tenantId().equals(row.nppTenantId())) {
            confirmationColumn = "npp_confirmed_by";
            tenantColumn = "npp_tenant_id";
        } else {
            throw new AccessDeniedException("Chỉ NSX và NPP liên quan mới được xác nhận đối soát.");
        }
        String update = """
                UPDATE reconciliation
                SET %s = ?, status = CASE
                    WHEN (nsx_confirmed_by IS NOT NULL OR ? = 'NSX')
                     AND (npp_confirmed_by IS NOT NULL OR ? = 'NPP')
                    THEN 'CONFIRMED' ELSE 'PARTIALLY_CONFIRMED' END,
                    confirmed_by = CASE WHEN (nsx_confirmed_by IS NOT NULL OR ? = 'NSX')
                     AND (npp_confirmed_by IS NOT NULL OR ? = 'NPP') THEN ? ELSE confirmed_by END,
                    confirmed_at = CASE WHEN (nsx_confirmed_by IS NOT NULL OR ? = 'NSX')
                     AND (npp_confirmed_by IS NOT NULL OR ? = 'NPP')
                    THEN CURRENT_TIMESTAMP ELSE confirmed_at END,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND %s = ? AND version = ? AND %s IS NULL
                """.formatted(confirmationColumn, tenantColumn, confirmationColumn);
        int changed = jdbc.update(update, actor.userId(), actor.tenantType(), actor.tenantType(),
                actor.tenantType(), actor.tenantType(), actor.userId(), actor.tenantType(),
                actor.tenantType(), row.id(), actor.tenantId(), request.version());
        if (changed == 0) {
            throw conflict("RECONCILIATION_VERSION_CONFLICT", "Đối soát đã được cập nhật, vui lòng tải lại.");
        }
        ReconciliationView result = loadReconciliation(row.id());
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "reconciliation:confirm",
                "RECONCILIATION_CONFIRMED", "reconciliation", row.id(), row, result,
                request.reason(), servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public ExceptionView resolveException(
            Jwt jwt, UUID exceptionId, ActionRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ReconciliationScope scope = reconciliationScope(
                jwt, actor, "reconciliation:resolve_exception", "r");
        ExceptionRow old = jdbc.query("""
                SELECT e.id, e.tenant_id, e.reconciliation_id, e.status, e.version,
                    r.tenant_id AS rec_tenant_id, r.npp_tenant_id
                FROM reconciliation_exception e
                JOIN reconciliation r ON r.id = e.reconciliation_id
                WHERE e.id = ? AND
                """ + scope.sql() + " FOR UPDATE OF e",
                rs -> rs.next() ? new ExceptionRow(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("reconciliation_id", UUID.class), rs.getString("status"),
                        rs.getLong("version"), rs.getObject("rec_tenant_id", UUID.class),
                        rs.getObject("npp_tenant_id", UUID.class)) : null,
                prepend(exceptionId, scope.arguments()).toArray());
        if (old == null) {
            throw new AccessDeniedException("Ngoại lệ không tồn tại hoặc nằm ngoài phạm vi được phân quyền.");
        }
        if (!"OPEN".equals(old.status())) {
            throw conflict("RECONCILIATION_EXCEPTION_NOT_OPEN", "Ngoại lệ không còn ở trạng thái mở.");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApiProblem("RESOLUTION_REASON_REQUIRED",
                    "Cần ghi rõ lý do xử lý ngoại lệ.", HttpStatus.BAD_REQUEST);
        }
        int changed = jdbc.update("""
                UPDATE reconciliation_exception SET status = 'RESOLVED', resolved_by = ?,
                    resolution = ?,
                    resolved_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = ? AND version = ? AND status = 'OPEN'
                """, actor.userId(), request.reason().strip(), exceptionId, request.version());
        if (changed == 0) {
            throw conflict("RECONCILIATION_EXCEPTION_VERSION_CONFLICT",
                    "Ngoại lệ đã được cập nhật, vui lòng tải lại.");
        }
        ExceptionView result = loadException(exceptionId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "reconciliation:resolve_exception",
                "RECONCILIATION_EXCEPTION_RESOLVED", "reconciliation_exception", exceptionId,
                old, result, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public PeriodView createPeriod(Jwt jwt, CreatePeriodRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        validatePeriod(request.periodType(), request.startsOn(), request.endsOn());
        UUID periodId = jdbc.queryForObject("""
                INSERT INTO period (id, tenant_id, period_type, starts_on, ends_on, status)
                VALUES (?, ?, ?, ?, ?, 'OPEN')
                ON CONFLICT (tenant_id, period_type, starts_on, ends_on)
                DO UPDATE SET updated_at = period.updated_at
                RETURNING id
                """, UUID.class, UUID.randomUUID(), actor.tenantId(), request.periodType(),
                request.startsOn(), request.endsOn());
        PeriodView period = loadPeriod(periodId);
        if (!"CLOSED".equals(period.status())) {
            refreshPeriod(period);
        }
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "period:close",
                "PERIOD_CREATED", "period", periodId, null, period, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return loadPeriod(periodId);
    }

    @Transactional
    public PeriodView closePeriod(
            Jwt jwt, UUID periodId, ActionRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        PeriodView period = requirePeriodForTenant(periodId, actor.tenantId(), true);
        if (!"OPEN".equals(period.status())) {
            throw conflict("PERIOD_NOT_OPEN", "Chỉ có thể chốt kỳ đang mở.");
        }
        OffsetDateTime from = start(period.startsOn());
        OffsetDateTime to = start(period.endsOn().plusDays(1));
        Long unfinishedOrders = jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_si
                WHERE tenant_id = ? AND deleted_at IS NULL AND created_at >= ? AND created_at < ?
                  AND status NOT IN ('RECEIVED', 'RECONCILED', 'PERIOD_CLOSED', 'CANCELLED', 'REJECTED')
                """, Long.class, actor.tenantId(), from, to);
        if (unfinishedOrders != null && unfinishedOrders > 0) {
            throw conflict("PERIOD_HAS_UNFINISHED_ORDERS",
                    "Không thể chốt kỳ khi còn đơn nhập chưa nhận đủ hoặc chưa kết thúc.");
        }
        Long pendingReconciliations = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation
                WHERE tenant_id = ? AND period_id = ? AND status <> 'CONFIRMED'
                """, Long.class, actor.tenantId(), periodId);
        Long expectedReconciliations = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT npp_tenant_id) FROM tenant_relation
                WHERE nsx_tenant_id = ? AND status = 'ACTIVE'
                  AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                """, Long.class, actor.tenantId(), period.endsOn(), period.startsOn());
        Long createdReconciliations = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation
                WHERE tenant_id = ? AND period_id = ?
                """, Long.class, actor.tenantId(), periodId);
        Long openExceptions = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation_exception e
                JOIN reconciliation r ON r.id = e.reconciliation_id
                WHERE r.tenant_id = ? AND r.period_id = ? AND e.status = 'OPEN'
                """, Long.class, actor.tenantId(), periodId);
        if ((pendingReconciliations != null && pendingReconciliations > 0)
                || !java.util.Objects.equals(expectedReconciliations, createdReconciliations)
                || (openExceptions != null && openExceptions > 0)) {
            throw conflict("PERIOD_RECONCILIATION_INCOMPLETE",
                    "Cần tạo/xác nhận đủ đối soát hai bên và xử lý ngoại lệ trước khi chốt kỳ.");
        }
        closeReconciledOrders(period);
        int changed = jdbc.update("""
                UPDATE period SET status = 'CLOSED', closed_by = ?, closed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND tenant_id = ? AND status = 'OPEN' AND version = ?
                """, actor.userId(), periodId, actor.tenantId(), request.version());
        if (changed == 0) {
            throw conflict("PERIOD_VERSION_CONFLICT", "Kỳ đã được cập nhật, vui lòng tải lại.");
        }
        PeriodView result = loadPeriod(periodId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "period:close", "PERIOD_CLOSED",
                "period", periodId, period, result, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return result;
    }

    public ApiPage<ReportRow> operationsReport(
            Jwt jwt, UUID periodId, UUID nppId, int page, int size) {
        return report(jwt, periodId, nppId, page, size, "report:ops", false);
    }

    public ApiPage<ReportRow> officialReport(
            Jwt jwt, UUID periodId, UUID nppId, int page, int size, HttpServletRequest request) {
        ApiPage<ReportRow> result = report(jwt, periodId, nppId, page, size, "report:official", true);
        BusinessAccessService.Actor actor = access.actor(jwt);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "report:official",
                "OFFICIAL_REPORT_VIEWED", "period", periodId, null,
                Map.of("nppId", nppId == null ? "" : nppId.toString()),
                "Xem báo cáo kỳ đã chốt", request.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), request.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public AdjustmentView createAdjustment(
            Jwt jwt, AdjustmentRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        PeriodView period = requirePeriodForTenant(request.periodId(), actor.tenantId(), false);
        if (!"CLOSED".equals(period.status())) {
            throw conflict("ADJUSTMENT_REQUIRES_CLOSED_PERIOD",
                    "Phiếu điều chỉnh chỉ áp dụng cho kỳ đã chốt.");
        }
        if (!List.of("ORDERED", "SHIPPED", "RECEIVED").contains(request.metricKey())) {
            throw new ApiProblem("ADJUSTMENT_METRIC_INVALID",
                    "Chỉ hỗ trợ điều chỉnh số đặt, xuất hoặc nhận.", HttpStatus.BAD_REQUEST);
        }
        partnerAccess.requireNpp(jwt, actor, "period:adjust_create", request.nppTenantId());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO period_adjustment (
                    id, tenant_id, period_id, npp_tenant_id, metric_key, adjustment_code,
                    amount_vnd, reason, status, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?)
                """, id, actor.tenantId(), request.periodId(), request.nppTenantId(),
                request.metricKey(), adjustmentCode(id), request.amountVnd(), request.reason(),
                actor.userId());
        AdjustmentView result = loadAdjustment(id);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "period:adjust_create",
                "PERIOD_ADJUSTMENT_CREATED", "period_adjustment", id, null, result,
                request.reason(), servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public AdjustmentView approveAdjustment(
            Jwt jwt, UUID adjustmentId, ActionRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        AdjustmentRow old = jdbc.query("""
                SELECT id, tenant_id, period_id, npp_tenant_id, status, created_by, version
                FROM period_adjustment WHERE id = ? AND tenant_id = ? FOR UPDATE
                """, rs -> rs.next() ? new AdjustmentRow(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("period_id", UUID.class), rs.getObject("npp_tenant_id", UUID.class),
                        rs.getString("status"), rs.getObject("created_by", UUID.class),
                        rs.getLong("version")) : null, adjustmentId, actor.tenantId());
        if (old == null) {
            throw new AccessDeniedException("Phiếu điều chỉnh không tồn tại trong tenant hiện tại.");
        }
        if (!"PENDING_APPROVAL".equals(old.status())) {
            throw conflict("ADJUSTMENT_NOT_PENDING", "Phiếu điều chỉnh không chờ duyệt.");
        }
        if (actor.userId().equals(old.createdBy())) {
            throw new ApiProblem("APPROVER_MUST_DIFFER",
                    "Người duyệt không được là người lập phiếu.", HttpStatus.FORBIDDEN);
        }
        PeriodView period = requirePeriodForTenant(old.periodId(), actor.tenantId(), false);
        if (!"CLOSED".equals(period.status())) {
            throw conflict("ADJUSTMENT_PERIOD_NOT_CLOSED", "Kỳ điều chỉnh không còn ở trạng thái đã chốt.");
        }
        int changed = jdbc.update("""
                UPDATE period_adjustment SET status = 'APPROVED', approved_by = ?,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND tenant_id = ? AND status = 'PENDING_APPROVAL' AND version = ?
                """, actor.userId(), adjustmentId, actor.tenantId(), request.version());
        if (changed == 0) {
            throw conflict("ADJUSTMENT_VERSION_CONFLICT", "Phiếu đã được cập nhật, vui lòng tải lại.");
        }
        AdjustmentView result = loadAdjustment(adjustmentId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "period:adjust_approve",
                "PERIOD_ADJUSTMENT_APPROVED", "period_adjustment", adjustmentId,
                old, result, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public void snapshotPreviousBusinessDay() {
        LocalDate day = LocalDate.now(BUSINESS_ZONE).minusDays(1);
        List<UUID> nsxTenants = jdbc.query("""
                SELECT id FROM tenant WHERE tenant_type = 'NSX' AND status = 'ACTIVE'
                """, (rs, row) -> rs.getObject("id", UUID.class));
        for (UUID tenantId : nsxTenants) {
            snapshotDay(tenantId,day);
        }
    }

    @Transactional
    public long snapshotDay(UUID tenantId,LocalDate day) {
            UUID periodId = jdbc.queryForObject("""
                    INSERT INTO period (id, tenant_id, period_type, starts_on, ends_on, status)
                    VALUES (?, ?, 'DAY', ?, ?, 'OPEN')
                    ON CONFLICT (tenant_id, period_type, starts_on, ends_on)
                    DO UPDATE SET updated_at = period.updated_at
                    RETURNING id
                    """, UUID.class, UUID.randomUUID(), tenantId, day, day);
            refreshPeriod(loadPeriod(periodId));
            return jdbc.queryForObject("SELECT count(*) FROM reconciliation WHERE period_id=?",Long.class,periodId);
    }

    /** T+1 closes only days whose human confirmations and three-way checks are already complete. */
    @Transactional
    public long closeReadyDays(UUID tenantId,LocalDate through) {
        // Prevent concurrent inserts/line edits between readiness checks and the CLOSED write.
        // Held only by the closing transaction; a deadlock aborts and is retried by the job runner.
        jdbc.execute("LOCK TABLE order_si,order_line IN SHARE ROW EXCLUSIVE MODE");
        var days=jdbc.query("SELECT id FROM period WHERE tenant_id=? AND period_type='DAY' AND status='OPEN' AND ends_on<=? ORDER BY starts_on FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),tenantId,through);
        long closed=0;
        for(UUID id:days){
            PeriodView period=loadPeriod(id);
            long unfinished=jdbc.queryForObject("SELECT count(*) FROM order_si WHERE tenant_id=? AND deleted_at IS NULL AND created_at>=? AND created_at<? AND status NOT IN('RECONCILED','PERIOD_CLOSED','CANCELLED','REJECTED')",Long.class,tenantId,start(period.startsOn()),start(period.endsOn().plusDays(1)));
            long expected=jdbc.queryForObject("SELECT count(DISTINCT npp_tenant_id) FROM tenant_relation WHERE nsx_tenant_id=? AND status='ACTIVE' AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?)",Long.class,tenantId,period.endsOn(),period.startsOn());
            long confirmed=jdbc.queryForObject("SELECT count(*) FROM reconciliation WHERE tenant_id=? AND period_id=? AND status='CONFIRMED'",Long.class,tenantId,id);
            long exceptions=jdbc.queryForObject("SELECT count(*) FROM reconciliation_exception e JOIN reconciliation r ON r.id=e.reconciliation_id WHERE r.period_id=? AND e.status='OPEN'",Long.class,id);
            if(unfinished!=0||expected!=confirmed||exceptions!=0)continue;
            closeReconciledOrders(period);
            closed+=jdbc.update("UPDATE period SET status='CLOSED',closed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=? AND status='OPEN'",id);
        }
        return closed;
    }

    private ApiPage<ReportRow> report(
            Jwt jwt, UUID periodId, UUID requestedNppId, int page, int size,
            String permission, boolean official) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        PeriodView period = requireAccessiblePeriod(jwt, actor, permission, periodId);
        BigDecimal warningThreshold=BigDecimal.valueOf(com.synera.api.operations.RuntimeSettings.number(jdbc,period.tenantId(),"variance_warning_percent"));
        if (official && !"CLOSED".equals(period.status())) {
            throw conflict("OFFICIAL_REPORT_PERIOD_NOT_CLOSED",
                    "Báo cáo chính thức chỉ có sau khi kỳ được chốt.");
        }
        NppVisibility visibility = nppVisibility(jwt, actor, permission, requestedNppId, "n");
        String baseWhere = "r.tenant_id = ? AND " + visibility.sql();
        List<Object> args = new ArrayList<>();
        args.add(period.tenantId());
        args.addAll(visibility.arguments());
        if (official) {
            List<Object> officialArgs = prepend(periodId, args);
            Long total = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM reconciliation r
                    JOIN tenant n ON n.id = r.npp_tenant_id
                    WHERE r.period_id = ? AND
                    """ + baseWhere, Long.class, officialArgs.toArray());
            officialArgs.add(size);
            officialArgs.add((long) page * size);
            List<ReportRow> rows = jdbc.query("""
                    SELECT r.npp_tenant_id, n.name AS npp_name, p.closed_at AS as_of,
                        r.ordered_amount_vnd,
                        r.shipped_amount_vnd, r.received_amount_vnd,
                        COALESCE(SUM(CASE WHEN a.metric_key = 'ORDERED' THEN a.amount_vnd ELSE 0 END), 0)
                            AS ordered_adjustment,
                        COALESCE(SUM(CASE WHEN a.metric_key = 'SHIPPED' THEN a.amount_vnd ELSE 0 END), 0)
                            AS shipped_adjustment,
                        COALESCE(SUM(CASE WHEN a.metric_key = 'RECEIVED' THEN a.amount_vnd ELSE 0 END), 0)
                            AS received_adjustment
                    FROM reconciliation r
                    JOIN tenant n ON n.id = r.npp_tenant_id
                    JOIN period p ON p.id = r.period_id
                    LEFT JOIN period_adjustment a ON a.tenant_id = r.tenant_id
                        AND a.period_id = r.period_id AND a.npp_tenant_id = r.npp_tenant_id
                        AND a.status = 'APPROVED'
                    WHERE r.period_id = ? AND
                    """ + baseWhere + "\n" + """
                    GROUP BY r.npp_tenant_id, n.name, r.ordered_amount_vnd,
                        r.shipped_amount_vnd, r.received_amount_vnd, p.closed_at
                    ORDER BY n.name, r.npp_tenant_id LIMIT ? OFFSET ?
                    """, (rs, row) -> reportRow(
                            rs.getObject("npp_tenant_id", UUID.class), rs.getString("npp_name"),
                            rs.getLong("ordered_amount_vnd"), rs.getLong("shipped_amount_vnd"),
                            rs.getLong("received_amount_vnd"), rs.getLong("ordered_adjustment"),
                            rs.getLong("shipped_adjustment"), rs.getLong("received_adjustment"),
                            rs.getObject("as_of", OffsetDateTime.class), null,warningThreshold),
                    officialArgs.toArray());
            return ApiPage.of(rows, page, size, total == null ? 0 : total);
        }

        OffsetDateTime from = start(period.startsOn());
        OffsetDateTime to = start(period.endsOn().plusDays(1));
        String liveWhere = "r.period_id = ? AND r.tenant_id = ? AND " + visibility.sql();
        List<Object> liveArgs = prepend(periodId, List.of());
        liveArgs.add(period.tenantId());
        liveArgs.addAll(visibility.arguments());
        long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation r
                JOIN tenant n ON n.id = r.npp_tenant_id
                WHERE
                """ + liveWhere, Long.class, liveArgs.toArray());
        List<Object> queryArgs = new ArrayList<>();
        queryArgs.add(from);
        queryArgs.add(to);
        queryArgs.addAll(liveArgs);
        queryArgs.add(size);
        queryArgs.add((long) page * size);
        List<ReportRow> rows = jdbc.query("""
                SELECT r.npp_tenant_id, n.name AS npp_name, CURRENT_TIMESTAMP AS as_of,
                    COALESCE(SUM(ol.line_amount_vnd), 0) AS ordered_amount,
                    COALESCE(SUM(CASE WHEN ol.ordered_qty > 0 THEN
                        (ol.line_amount_vnd::numeric * ol.shipped_qty / ol.ordered_qty)::bigint
                        ELSE 0 END), 0) AS shipped_amount,
                    COALESCE(SUM(CASE WHEN ol.ordered_qty > 0 THEN
                        (ol.line_amount_vnd::numeric * ol.received_qty / ol.ordered_qty)::bigint
                        ELSE 0 END), 0) AS received_amount
                FROM reconciliation r
                JOIN tenant n ON n.id = r.npp_tenant_id
                LEFT JOIN order_si os ON os.tenant_id = r.tenant_id
                    AND os.npp_tenant_id = r.npp_tenant_id
                    AND os.created_at >= ? AND os.created_at < ?
                    AND os.deleted_at IS NULL AND os.status NOT IN ('DRAFT', 'CANCELLED', 'REJECTED')
                LEFT JOIN order_line ol ON ol.tenant_id = os.tenant_id AND ol.order_si_id = os.id
                    AND ol.deleted_at IS NULL
                WHERE
                """ + liveWhere + "\n" + """
                GROUP BY r.npp_tenant_id, n.name
                ORDER BY n.name, r.npp_tenant_id LIMIT ? OFFSET ?
                """, (rs, row) -> {
            long ordered = rs.getLong("ordered_amount");
            long shipped = rs.getLong("shipped_amount");
            long received = rs.getLong("received_amount");
            return reportRow(rs.getObject("npp_tenant_id", UUID.class), rs.getString("npp_name"),
                    ordered, shipped, received, 0, 0, 0,
                    rs.getObject("as_of", OffsetDateTime.class), null,warningThreshold);
        }, queryArgs.toArray());

        if (!official) {
            List<ReportRow> rowsWithVariance = compareWithOfficial(rows,periodId,warningThreshold);
            return ApiPage.of(rowsWithVariance, page, size, total);
        }
        return ApiPage.of(rows, page, size, total);
    }

    private void refreshPeriod(PeriodView period) {
        if ("CLOSED".equals(period.status())) {
            return;
        }
        LocalDate relationDate = period.endsOn();
        List<UUID> nppIds = jdbc.query("""
                SELECT DISTINCT npp_tenant_id FROM tenant_relation
                WHERE nsx_tenant_id = ? AND status = 'ACTIVE'
                  AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                """, (rs, row) -> rs.getObject("npp_tenant_id", UUID.class),
                period.tenantId(), relationDate, period.startsOn());
        for (UUID nppId : nppIds) {
            List<UUID> refreshed = jdbc.query("""
                    INSERT INTO reconciliation (
                        id, tenant_id, npp_tenant_id, period_id, status, snapshot_at
                    ) VALUES (?, ?, ?, ?, 'OPEN', CURRENT_TIMESTAMP)
                    ON CONFLICT (tenant_id, npp_tenant_id, period_id)
                        WHERE period_id IS NOT NULL
                    DO UPDATE SET snapshot_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                        ordered_amount_vnd = EXCLUDED.ordered_amount_vnd,
                        shipped_amount_vnd = EXCLUDED.shipped_amount_vnd,
                        received_amount_vnd = EXCLUDED.received_amount_vnd
                    WHERE reconciliation.status <> 'CONFIRMED'
                    RETURNING id
                    """, (rs, row) -> rs.getObject("id", UUID.class),
                    UUID.randomUUID(), period.tenantId(), nppId, period.id());
            if (!refreshed.isEmpty()) {
                UUID reconciliationId = refreshed.get(0);
                updateTotals(reconciliationId, period, nppId);
                generateExceptions(reconciliationId, period, nppId);
                reconcileMatchedOrders(period, nppId);
            }
        }
    }

    private void reconcileMatchedOrders(PeriodView period, UUID nppId) {
        List<OrderVersion> orders = jdbc.query("""
                SELECT os.id, os.version FROM order_si os
                WHERE os.tenant_id = ? AND os.npp_tenant_id = ? AND os.status = 'RECEIVED'
                  AND os.deleted_at IS NULL AND os.created_at >= ? AND os.created_at < ?
                  AND EXISTS (SELECT 1 FROM order_line ol
                      WHERE ol.tenant_id = os.tenant_id AND ol.order_si_id = os.id
                        AND ol.deleted_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM order_line ol
                      WHERE ol.tenant_id = os.tenant_id AND ol.order_si_id = os.id
                        AND ol.deleted_at IS NULL
                        AND (ol.ordered_qty <> ol.shipped_qty OR ol.shipped_qty <> ol.received_qty))
                ORDER BY os.id
                """, (rs, row) -> new OrderVersion(
                rs.getObject("id", UUID.class), rs.getLong("version")),
                period.tenantId(), nppId, start(period.startsOn()), start(period.endsOn().plusDays(1)));
        for (OrderVersion order : orders) {
            orderFlow.transition("SELL_IN", period.tenantId(), order.id(), "RECEIVED", "RECONCILED",
                    "SYSTEM", null, "Đối soát ba bên khớp.", order.version());
        }
    }

    private void closeReconciledOrders(PeriodView period) {
        List<OrderVersion> orders = jdbc.query("""
                SELECT id, version FROM order_si
                WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
                  AND status = 'RECONCILED' AND deleted_at IS NULL
                ORDER BY id
                """, (rs, row) -> new OrderVersion(
                rs.getObject("id", UUID.class), rs.getLong("version")),
                period.tenantId(), start(period.startsOn()), start(period.endsOn().plusDays(1)));
        for (OrderVersion order : orders) {
            orderFlow.transition("SELL_IN", period.tenantId(), order.id(), "RECONCILED", "PERIOD_CLOSED",
                    "period:close", null, "Đóng đơn theo kỳ đã chốt.", order.version());
        }
    }

    private void updateTotals(UUID reconciliationId, PeriodView period, UUID nppId) {
        jdbc.update("""
                UPDATE reconciliation r SET
                    ordered_amount_vnd = totals.ordered_amount,
                    shipped_amount_vnd = totals.shipped_amount,
                    received_amount_vnd = totals.received_amount,
                    snapshot_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                FROM (
                    SELECT COALESCE(SUM(ol.line_amount_vnd), 0)::bigint AS ordered_amount,
                        COALESCE(SUM(CASE WHEN ol.ordered_qty > 0 THEN
                            (ol.line_amount_vnd::numeric * ol.shipped_qty / ol.ordered_qty)::bigint
                            ELSE 0 END), 0)::bigint AS shipped_amount,
                        COALESCE(SUM(CASE WHEN ol.ordered_qty > 0 THEN
                            (ol.line_amount_vnd::numeric * ol.received_qty / ol.ordered_qty)::bigint
                            ELSE 0 END), 0)::bigint AS received_amount
                    FROM order_si os
                    JOIN order_line ol ON ol.tenant_id = os.tenant_id AND ol.order_si_id = os.id
                        AND ol.deleted_at IS NULL
                    WHERE os.tenant_id = ? AND os.npp_tenant_id = ?
                      AND os.created_at >= ? AND os.created_at < ?
                      AND os.deleted_at IS NULL
                      AND os.status NOT IN ('DRAFT', 'CANCELLED', 'REJECTED')
                ) totals
                WHERE r.id = ?
                """, period.tenantId(), nppId, start(period.startsOn()),
                start(period.endsOn().plusDays(1)), reconciliationId);
    }

    private void generateExceptions(UUID reconciliationId, PeriodView period, UUID nppId) {
        jdbc.update("""
                INSERT INTO reconciliation_exception (
                    id, tenant_id, reconciliation_id, order_si_id, order_line_id,
                    exception_code, description, expected_qty, actual_qty
                )
                SELECT gen_random_uuid(), os.tenant_id, ?, os.id, ol.id, 'SHIP_SHORT',
                    'Xuất kho ít hơn lượng đặt: ' || ol.shipped_qty || '/' || ol.ordered_qty,
                    ol.ordered_qty, ol.shipped_qty
                FROM order_si os
                JOIN order_line ol ON ol.tenant_id = os.tenant_id AND ol.order_si_id = os.id
                    AND ol.deleted_at IS NULL
                WHERE os.tenant_id = ? AND os.npp_tenant_id = ?
                  AND os.created_at >= ? AND os.created_at < ?
                  AND os.deleted_at IS NULL AND os.status NOT IN ('DRAFT', 'CANCELLED', 'REJECTED')
                  AND ol.shipped_qty < ol.ordered_qty
                ON CONFLICT (reconciliation_id, exception_code, order_line_id)
                    WHERE order_line_id IS NOT NULL DO NOTHING
                """, reconciliationId, period.tenantId(), nppId, start(period.startsOn()),
                start(period.endsOn().plusDays(1)));
        jdbc.update("""
                INSERT INTO reconciliation_exception (
                    id, tenant_id, reconciliation_id, order_si_id, order_line_id,
                    exception_code, description, expected_qty, actual_qty
                )
                SELECT gen_random_uuid(), os.tenant_id, ?, os.id, ol.id, 'RECEIPT_SHORT',
                    'Thực nhận ít hơn lượng xuất: ' || ol.received_qty || '/' || ol.shipped_qty,
                    ol.shipped_qty, ol.received_qty
                FROM order_si os
                JOIN order_line ol ON ol.tenant_id = os.tenant_id AND ol.order_si_id = os.id
                    AND ol.deleted_at IS NULL
                WHERE os.tenant_id = ? AND os.npp_tenant_id = ?
                  AND os.created_at >= ? AND os.created_at < ?
                  AND os.deleted_at IS NULL AND os.status NOT IN ('DRAFT', 'CANCELLED', 'REJECTED')
                  AND ol.received_qty < ol.shipped_qty
                ON CONFLICT (reconciliation_id, exception_code, order_line_id)
                    WHERE order_line_id IS NOT NULL DO NOTHING
                """, reconciliationId, period.tenantId(), nppId, start(period.startsOn()),
                start(period.endsOn().plusDays(1)));
    }

    private List<ReportRow> compareWithOfficial(List<ReportRow> rows, UUID periodId,BigDecimal warningThreshold) {
        if(rows.isEmpty())return rows;
        String marks=String.join(",",rows.stream().map(row->"?").toList());
        List<Object> arguments=new ArrayList<>();arguments.add(periodId);rows.forEach(row->arguments.add(row.nppTenantId()));
        Map<UUID,OfficialAmounts> amounts=new java.util.HashMap<>();
        jdbc.query("""
                SELECT r.npp_tenant_id,COALESCE(r.received_amount_vnd, 0)
                       + COALESCE(SUM(CASE WHEN a.metric_key = 'RECEIVED'
                                           THEN a.amount_vnd ELSE 0 END), 0) AS amount,
                    COUNT(r.id) AS found
                FROM period p
                JOIN reconciliation r ON r.period_id = p.id AND r.tenant_id=p.tenant_id
                LEFT JOIN period_adjustment a ON a.period_id = p.id
                    AND a.npp_tenant_id = r.npp_tenant_id AND a.status = 'APPROVED'
                WHERE p.id = ? AND p.status = 'CLOSED' AND r.npp_tenant_id IN (
                """+marks+") GROUP BY r.npp_tenant_id,r.received_amount_vnd",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->amounts.put(rs.getObject("npp_tenant_id",UUID.class),new OfficialAmounts(rs.getLong("amount"),rs.getLong("found"))),arguments.toArray());
        return rows.stream().map(live->{
        OfficialAmounts official=amounts.get(live.nppTenantId());
        BigDecimal variance = official == null || official.found() == 0 ? null
                : variance(live.receivedAmountVnd(), official.amount());
        long officialAmount = official == null || official.found() == 0
                ? live.receivedAmountVnd() : official.amount();
        return new ReportRow(live.nppTenantId(), live.nppName(), live.orderedAmountVnd(),
                live.shippedAmountVnd(), live.receivedAmountVnd(), live.orderedAdjustmentVnd(),
                live.shippedAdjustmentVnd(), live.receivedAdjustmentVnd(), officialAmount, live.asOf(),
                variance, variance != null && variance.compareTo(warningThreshold) > 0);
        }).toList();
    }

    ReportRow reportRow(
            UUID nppId, String nppName, long ordered, long shipped, long received,
            long orderedAdjustment, long shippedAdjustment, long receivedAdjustment,
            OffsetDateTime asOf, Long officialReceived,BigDecimal warningThreshold) {
        long official = officialReceived == null ? received + receivedAdjustment : officialReceived;
        BigDecimal variance = variance(received, official);
        return new ReportRow(nppId, nppName, ordered, shipped, received,
                orderedAdjustment, shippedAdjustment, receivedAdjustment, official, asOf,
                variance, variance != null && variance.compareTo(warningThreshold) > 0);
    }

    private BigDecimal variance(long current, long official) {
        BigDecimal denominator = BigDecimal.valueOf(official).abs().max(BigDecimal.ONE);
        return BigDecimal.valueOf(current).subtract(BigDecimal.valueOf(official)).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private ReconciliationScope reconciliationScope(
            Jwt jwt, BusinessAccessService.Actor actor, String permission, String alias) {
        if ("NPP".equals(actor.tenantType())) {
            return new ReconciliationScope(alias + ".npp_tenant_id = ?", List.of(actor.tenantId()));
        }
        if (!"NSX".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chỉ NSX hoặc NPP được truy cập đối soát.");
        }
        PartnerAccessService.Filter npps = partnerAccess.npps(jwt, actor, permission, "n");
        return new ReconciliationScope(alias + ".tenant_id = ? AND " + alias
                + ".npp_tenant_id IN (SELECT n.id FROM tenant n WHERE " + npps.sql() + ")",
                prepend(actor.tenantId(), npps.arguments()));
    }

    private NppVisibility nppVisibility(
            Jwt jwt, BusinessAccessService.Actor actor, String permission,
            UUID requestedNppId, String alias) {
        if ("NPP".equals(actor.tenantType())) {
            if (requestedNppId != null && !actor.tenantId().equals(requestedNppId)) {
                throw new AccessDeniedException("NPP chỉ được truy cập số liệu của chính mình.");
            }
            return new NppVisibility(alias + ".id = ?", List.of(actor.tenantId()));
        }
        if (!"NSX".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chỉ NSX hoặc NPP được truy cập báo cáo.");
        }
        PartnerAccessService.Filter filter = partnerAccess.npps(jwt, actor, permission, alias);
        List<Object> args = new ArrayList<>(filter.arguments());
        String sql = filter.sql();
        if (requestedNppId != null) {
            partnerAccess.requireNpp(jwt, actor, permission, requestedNppId);
            sql += " AND " + alias + ".id = ?";
            args.add(requestedNppId);
        }
        return new NppVisibility(sql, args);
    }

    private PeriodView requireAccessiblePeriod(
            Jwt jwt, BusinessAccessService.Actor actor, String permission, UUID periodId) {
        if ("NSX".equals(actor.tenantType())) {
            return requirePeriodForTenant(periodId, actor.tenantId(), false);
        }
        if ("NPP".equals(actor.tenantType())) {
            PeriodView period = jdbc.query("""
                    SELECT p.id, p.tenant_id, p.period_type, p.starts_on, p.ends_on,
                        p.status, p.closed_by, p.closed_at, p.version
                    FROM period p JOIN reconciliation r ON r.period_id = p.id
                    WHERE p.id = ? AND r.npp_tenant_id = ?
                    """, rs -> rs.next() ? periodView(rs) : null, periodId, actor.tenantId());
            if (period == null) {
                throw new AccessDeniedException("Kỳ không tồn tại hoặc ngoài phạm vi NPP.");
            }
            return period;
        }
        throw new AccessDeniedException("Chỉ NSX hoặc NPP được truy cập báo cáo.");
    }

    private PeriodView requirePeriodForTenant(UUID periodId, UUID tenantId, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        PeriodView period = jdbc.query("""
                SELECT id, tenant_id, period_type, starts_on, ends_on, status, closed_by, closed_at, version
                FROM period WHERE id = ? AND tenant_id = ?
                """ + lock, rs -> rs.next() ? periodView(rs) : null, periodId, tenantId);
        if (period == null) {
            throw new AccessDeniedException("Kỳ không tồn tại trong tenant hiện tại.");
        }
        return period;
    }

    private ReconciliationRow requireReconciliation(UUID id, ReconciliationScope scope, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        ReconciliationRow row = jdbc.query("""
                SELECT id, tenant_id, npp_tenant_id, period_id, status, version
                FROM reconciliation r WHERE r.id = ? AND
                """ + scope.sql() + lock, rs -> rs.next() ? new ReconciliationRow(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("npp_tenant_id", UUID.class), rs.getObject("period_id", UUID.class),
                rs.getString("status"), rs.getLong("version")) : null,
                prepend(id, scope.arguments()).toArray());
        if (row == null) {
            throw new AccessDeniedException("Đối soát không tồn tại hoặc nằm ngoài phạm vi được phân quyền.");
        }
        return row;
    }

    private void requireNppAccess(
            Jwt jwt, BusinessAccessService.Actor actor, String permission, UUID nppId) {
        if ("NPP".equals(actor.tenantType())) {
            if (!actor.tenantId().equals(nppId)) {
                throw new AccessDeniedException("NPP chỉ được truy cập dữ liệu của chính mình.");
            }
            return;
        }
        partnerAccess.requireNpp(jwt, actor, permission, nppId);
    }

    private ReconciliationView loadReconciliation(UUID id) {
        return jdbc.query("""
                SELECT r.id, r.tenant_id, r.npp_tenant_id, n.name AS npp_name,
                    r.period_id, r.status, r.ordered_amount_vnd, r.shipped_amount_vnd,
                    r.received_amount_vnd, r.nsx_confirmed_by, r.npp_confirmed_by,
                    r.snapshot_at, r.confirmed_at, r.version
                FROM reconciliation r JOIN tenant n ON n.id = r.npp_tenant_id
                WHERE r.id = ?
                """, rs -> rs.next() ? new ReconciliationView(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("npp_tenant_id", UUID.class), rs.getString("npp_name"),
                rs.getObject("period_id", UUID.class), rs.getString("status"),
                rs.getLong("ordered_amount_vnd"), rs.getLong("shipped_amount_vnd"),
                rs.getLong("received_amount_vnd"), rs.getObject("nsx_confirmed_by", UUID.class),
                rs.getObject("npp_confirmed_by", UUID.class),
                rs.getObject("snapshot_at", OffsetDateTime.class),
                rs.getObject("confirmed_at", OffsetDateTime.class), rs.getLong("version"), null)
                : null, id);
    }

    private ExceptionView loadException(UUID id) {
        return jdbc.query("""
                SELECT id, reconciliation_id, order_si_id, order_line_id, exception_code,
                    description, status, expected_qty, actual_qty, resolution, assigned_to,
                    resolved_by, resolved_at, version, created_at
                FROM reconciliation_exception WHERE id = ?
                """, rs -> rs.next() ? new ExceptionView(
                rs.getObject("id", UUID.class), rs.getObject("reconciliation_id", UUID.class),
                rs.getObject("order_si_id", UUID.class), rs.getObject("order_line_id", UUID.class),
                rs.getString("exception_code"), rs.getString("description"), rs.getString("status"),
                (Long) rs.getObject("expected_qty"), (Long) rs.getObject("actual_qty"),
                rs.getString("resolution"),
                rs.getObject("assigned_to", UUID.class), rs.getObject("resolved_by", UUID.class),
                rs.getObject("resolved_at", OffsetDateTime.class), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class)) : null, id);
    }

    private PeriodView loadPeriod(UUID id) {
        return jdbc.query("""
                SELECT id, tenant_id, period_type, starts_on, ends_on, status, closed_by, closed_at, version
                FROM period WHERE id = ?
                """, rs -> rs.next() ? periodView(rs) : null, id);
    }

    private PeriodView periodView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PeriodView(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("period_type"), rs.getObject("starts_on", LocalDate.class),
                rs.getObject("ends_on", LocalDate.class), rs.getString("status"),
                rs.getObject("closed_by", UUID.class), rs.getObject("closed_at", OffsetDateTime.class),
                rs.getLong("version"));
    }

    private AdjustmentView loadAdjustment(UUID id) {
        return jdbc.query("""
                SELECT id, tenant_id, period_id, npp_tenant_id, metric_key, adjustment_code,
                    amount_vnd, reason, status, created_by, approved_by, version, created_at, updated_at
                FROM period_adjustment WHERE id = ?
                """, rs -> rs.next() ? new AdjustmentView(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("period_id", UUID.class), rs.getObject("npp_tenant_id", UUID.class),
                rs.getString("metric_key"), rs.getString("adjustment_code"), rs.getLong("amount_vnd"),
                rs.getString("reason"), rs.getString("status"), rs.getObject("created_by", UUID.class),
                rs.getObject("approved_by", UUID.class), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)) : null, id);
    }

    private void validatePeriod(String type, LocalDate starts, LocalDate ends) {
        if (starts == null || ends == null || type == null || ends.isBefore(starts)) {
            throw new ApiProblem("PERIOD_RANGE_INVALID", "Khoảng ngày của kỳ không hợp lệ.",
                    HttpStatus.BAD_REQUEST);
        }
        LocalDate canonicalStart;
        LocalDate canonicalEnd;
        switch (type) {
            case "DAY" -> {
                canonicalStart = starts;
                canonicalEnd = starts;
            }
            case "WEEK" -> {
                canonicalStart = starts.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                canonicalEnd = canonicalStart.plusDays(6);
            }
            case "MONTH" -> {
                canonicalStart = starts.withDayOfMonth(1);
                canonicalEnd = starts.with(TemporalAdjusters.lastDayOfMonth());
            }
            case "QUARTER" -> {
                int firstQuarterMonth = ((starts.getMonthValue() - 1) / 3) * 3 + 1;
                canonicalStart = LocalDate.of(starts.getYear(), firstQuarterMonth, 1);
                canonicalEnd = canonicalStart.plusMonths(3).minusDays(1);
            }
            default -> throw new ApiProblem("PERIOD_TYPE_INVALID",
                    "Loại kỳ phải là DAY, WEEK, MONTH hoặc QUARTER.", HttpStatus.BAD_REQUEST);
        }
        if (!starts.equals(canonicalStart) || !ends.equals(canonicalEnd)) {
            throw new ApiProblem("PERIOD_RANGE_NOT_CANONICAL",
                    "Ngày bắt đầu/kết thúc không khớp ranh giới của loại kỳ.", HttpStatus.BAD_REQUEST);
        }
    }

    private String adjustmentCode(UUID id) {
        return "ADJ-" + id.toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private OffsetDateTime start(LocalDate day) {
        return day.atStartOfDay(BUSINESS_ZONE).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    private ApiProblem conflict(String code, String message) {
        return new ApiProblem(code, message, HttpStatus.CONFLICT);
    }

    private static List<Object> prepend(Object first, List<Object> rest) {
        List<Object> result = new ArrayList<>();
        result.add(first);
        result.addAll(rest);
        return result;
    }

    public record ActionRequest(@Min(0) long version, @NotBlank String reason) {
    }

    public record CreatePeriodRequest(
            @NotBlank String periodType, @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn, String reason) {
    }

    public record AdjustmentRequest(
            @NotNull UUID periodId, @NotNull UUID nppTenantId, @NotBlank String metricKey,
            long amountVnd, @NotBlank String reason) {
    }

    public record PeriodView(
            UUID id, UUID tenantId, String periodType, LocalDate startsOn, LocalDate endsOn,
            String status, UUID closedBy, OffsetDateTime closedAt, long version) {
    }

    public record ReconciliationView(
            UUID id, UUID tenantId, UUID nppTenantId, String nppName, UUID periodId, String status,
            long orderedAmountVnd, long shippedAmountVnd, long receivedAmountVnd,
            UUID nsxConfirmedBy, UUID nppConfirmedBy, OffsetDateTime snapshotAt,
            OffsetDateTime confirmedAt, long version, BigDecimal variancePercent) {
        ReconciliationView withVariance(BigDecimal variance) {
            return new ReconciliationView(id, tenantId, nppTenantId, nppName, periodId, status,
                    orderedAmountVnd, shippedAmountVnd, receivedAmountVnd, nsxConfirmedBy,
                    nppConfirmedBy, snapshotAt, confirmedAt, version, variance);
        }
    }

    public record ExceptionView(
            UUID id, UUID reconciliationId, UUID orderSiId, UUID orderLineId, String exceptionCode,
            String description, String status, Long expectedQty, Long actualQty, String resolution,
            UUID assignedTo, UUID resolvedBy, OffsetDateTime resolvedAt, long version,
            OffsetDateTime createdAt) {
    }

    public record ReportRow(
            UUID nppTenantId, String nppName, long orderedAmountVnd, long shippedAmountVnd,
            long receivedAmountVnd, long orderedAdjustmentVnd, long shippedAdjustmentVnd,
            long receivedAdjustmentVnd, long officialReceivedAmountVnd, OffsetDateTime asOf,
            BigDecimal variancePercent, boolean warningOverFivePercent) {
    }

    public record AdjustmentView(
            UUID id, UUID tenantId, UUID periodId, UUID nppTenantId, String metricKey,
            String adjustmentCode, long amountVnd, String reason, String status,
            UUID createdBy, UUID approvedBy, long version, OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    private record ReconciliationScope(String sql, List<Object> arguments) {
    }

    private record NppVisibility(String sql, List<Object> arguments) {
    }

    private record ReconciliationRow(
            UUID id, UUID tenantId, UUID nppTenantId, UUID periodId, String status, long version) {
    }

    private record ExceptionRow(
            UUID id, UUID tenantId, UUID reconciliationId, String status, long version,
            UUID recTenantId, UUID nppTenantId) {
    }

    private record AdjustmentRow(
            UUID id, UUID tenantId, UUID periodId, UUID nppTenantId, String status,
            UUID createdBy, long version) {
    }

    private record OfficialAmounts(long amount, long found) {
    }

    private record OrderVersion(UUID id, long version) {
    }
}
