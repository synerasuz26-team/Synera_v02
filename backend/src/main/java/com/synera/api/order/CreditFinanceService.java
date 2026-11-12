package com.synera.api.order;

import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.ApiPage;
import com.synera.api.catalog.PricingMath;
import com.synera.api.organization.BusinessAccessService;
import com.synera.api.partner.PartnerAccessService;
import com.synera.api.security.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
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
public class CreditFinanceService {
    private final JdbcTemplate jdbc;
    private final BusinessAccessService access;
    private final PartnerAccessService partnerAccess;
    private final AuditService audit;

    public CreditFinanceService(
            JdbcTemplate jdbc, BusinessAccessService access,
            PartnerAccessService partnerAccess, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.partnerAccess = partnerAccess;
        this.audit = audit;
    }

    public ApiPage<CreditLimitView> listCreditLimits(
            Jwt jwt, UUID requestedNppId, int page, int size, HttpServletRequest request) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ScopedNpp scope = scopedNpp(jwt, actor, "credit_limit:view", "cl.npp_tenant_id");
        StringBuilder where = new StringBuilder("cl.tenant_id = nsx.id AND ").append(scope.sql());
        List<Object> args = new ArrayList<>(scope.arguments());
        if (requestedNppId != null) {
            requireNppScope(jwt, actor, "credit_limit:view", requestedNppId);
            where.append(" AND cl.npp_tenant_id = ?");
            args.add(requestedNppId);
        }
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM credit_limit cl
                JOIN tenant nsx ON nsx.id = cl.tenant_id
                WHERE
                """ + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<CreditLimitView> rows = jdbc.query("""
                SELECT cl.id, cl.tenant_id, cl.npp_tenant_id, n.name AS npp_name,
                    cl.limit_amount_vnd, cl.limit_qty, cl.unit_code, cl.status,
                    cl.effective_from, cl.effective_to, cl.created_by, cl.approved_by, cl.version,
                    COALESCE((
                        SELECT SUM(GREATEST(i.amount_vnd
                            - COALESCE((SELECT SUM(p.amount_vnd) FROM payment p
                                WHERE p.invoice_id = i.id AND p.status = 'RECORDED'
                                  AND p.deleted_at IS NULL), 0)
                            - COALESCE((SELECT SUM(d.amount_vnd) FROM deduction d
                                WHERE d.invoice_id = i.id AND d.status = 'APPROVED'), 0), 0))
                        FROM invoice i WHERE i.tenant_id = cl.tenant_id
                            AND i.npp_tenant_id = cl.npp_tenant_id
                            AND i.deleted_at IS NULL AND i.status IN ('OPEN', 'PARTIAL')
                    ), 0) AS outstanding_vnd,
                    COALESCE((
                        SELECT SUM(os.total_vnd) FROM order_si os
                        WHERE os.tenant_id = cl.tenant_id AND os.npp_tenant_id = cl.npp_tenant_id
                          AND os.deleted_at IS NULL
                          AND os.status IN ('SUBMITTED', 'PENDING_APPROVAL', 'HELD_CREDIT', 'APPROVED')
                    ), 0) AS reserved_vnd,
                    COALESCE((
                        SELECT SUM(ol.ordered_qty) FROM order_si os
                        JOIN order_line ol ON ol.tenant_id = os.tenant_id AND ol.order_si_id = os.id
                        WHERE os.tenant_id = cl.tenant_id AND os.npp_tenant_id = cl.npp_tenant_id
                          AND os.deleted_at IS NULL
                          AND os.status IN ('SUBMITTED', 'PENDING_APPROVAL', 'HELD_CREDIT', 'APPROVED', 'SHIPPED')
                          AND ol.deleted_at IS NULL
                    ), 0) AS reserved_qty
                FROM credit_limit cl
                JOIN tenant nsx ON nsx.id = cl.tenant_id
                JOIN tenant n ON n.id = cl.npp_tenant_id
                WHERE
                """ + where + " ORDER BY cl.created_at DESC, cl.id LIMIT ? OFFSET ?",
                (rs, row) -> toLimitView(rs, isNppStaff(jwt)),
                args.toArray());
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "credit_limit:view",
                "CREDIT_LIMITS_VIEWED", "credit_limit", null, null,
                Map.of("nppId", requestedNppId == null ? "" : requestedNppId.toString()),
                "Tra cứu hạn mức", request.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                request.getHeader("User-Agent"));
        return ApiPage.of(rows, page, size, total == null ? 0 : total);
    }

    @Transactional
    public CreditLimitView proposeCreditLimit(
            Jwt jwt, CreateCreditLimitRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        requireNsx(actor);
        if (request.limitAmountVnd() == null && request.limitQty() == null) {
            throw new ApiProblem("CREDIT_LIMIT_REQUIRED",
                    "Cần nhập hạn mức tiền hoặc hạn mức số lượng.", HttpStatus.BAD_REQUEST);
        }
        if (request.limitQty() != null && (request.unitCode() == null || request.unitCode().isBlank())) {
            throw new ApiProblem("CREDIT_LIMIT_UNIT_REQUIRED",
                    "Cần chọn đơn vị cho hạn mức số lượng.", HttpStatus.BAD_REQUEST);
        }
        if (request.effectiveFrom() != null && request.effectiveTo() != null
                && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new ApiProblem("CREDIT_LIMIT_DATE_RANGE_INVALID",
                    "Ngày kết thúc hạn mức không được trước ngày bắt đầu.", HttpStatus.BAD_REQUEST);
        }
        requireNppScope(jwt, actor, "credit_limit:manage", request.nppTenantId());
        UUID limitId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO credit_limit (
                    id, tenant_id, npp_tenant_id, limit_amount_vnd, limit_qty, unit_code,
                    status, created_by, effective_from, effective_to
                ) VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                """, limitId, actor.tenantId(), request.nppTenantId(), request.limitAmountVnd(),
                request.limitQty(), cleanNullable(request.unitCode()), actor.userId(),
                request.effectiveFrom(), request.effectiveTo());
        CreditLimitView result = loadCreditLimit(limitId, actor.tenantId(), false);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "credit_limit:manage",
                "CREDIT_LIMIT_PROPOSED", "credit_limit", limitId, null, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public CreditLimitView submitCreditLimit(
            Jwt jwt, UUID limitId, CreditLimitActionRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        requireNsx(actor);
        CreditLimitRow limit = requireCreditLimit(jwt, actor, "credit_limit:manage", limitId, true);
        if (!"DRAFT".equals(limit.status()) || !actor.userId().equals(limit.createdBy())) {
            throw new ApiProblem("CREDIT_LIMIT_NOT_SUBMITTABLE",
                    "Chỉ người tạo mới được gửi hạn mức đang nháp.", HttpStatus.CONFLICT);
        }
        int changed = jdbc.update("""
                UPDATE credit_limit SET status = 'PENDING_APPROVAL', updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = ? AND tenant_id = ? AND version = ? AND status = 'DRAFT'
                """, limitId, actor.tenantId(), request.version());
        if (changed == 0) {
            throw versionConflict("CREDIT_LIMIT_VERSION_CONFLICT", limitId);
        }
        CreditLimitView result = loadCreditLimit(limitId, actor.tenantId(), false);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "credit_limit:manage",
                "CREDIT_LIMIT_SUBMITTED", "credit_limit", limitId, limit, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public CreditLimitView decideCreditLimit(
            Jwt jwt, UUID limitId, CreditLimitDecisionRequest request,
            HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        requireNsx(actor);
        CreditLimitRow limit = requireCreditLimit(jwt, actor, "credit_limit:approve", limitId, true);
        if (!"PENDING_APPROVAL".equals(limit.status())) {
            throw new ApiProblem("CREDIT_LIMIT_NOT_PENDING",
                    "Hạn mức không ở trạng thái chờ duyệt.", HttpStatus.CONFLICT);
        }
        if (actor.userId().equals(limit.createdBy())) {
            throw new ApiProblem("APPROVER_MUST_DIFFER",
                    "Người duyệt không được là người đề xuất hạn mức.", HttpStatus.FORBIDDEN);
        }
        String status = request.approved() ? "EFFECTIVE" : "REJECTED";
        if (request.approved()) {
            OffsetDateTime startsAt = limit.effectiveFrom() == null
                    ? OffsetDateTime.now(ZoneOffset.UTC) : limit.effectiveFrom();
            jdbc.queryForObject("SELECT id FROM tenant WHERE id = ? FOR UPDATE",
                    UUID.class, limit.nppTenantId());
            jdbc.update("""
                    UPDATE credit_limit SET effective_to = LEAST(COALESCE(effective_to, ?), ?)
                            - INTERVAL '1 microsecond',
                        updated_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND npp_tenant_id = ? AND status = 'EFFECTIVE'
                      AND effective_from < ?
                    """, startsAt, startsAt, actor.tenantId(), limit.nppTenantId(), startsAt);
        }
        int changed = jdbc.update("""
                UPDATE credit_limit SET status = ?, approved_by = ?, effective_from = COALESCE(
                    effective_from, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = ? AND tenant_id = ? AND version = ? AND status = 'PENDING_APPROVAL'
                """, status, actor.userId(), limitId, actor.tenantId(), request.version());
        if (changed == 0) {
            throw versionConflict("CREDIT_LIMIT_VERSION_CONFLICT", limitId);
        }
        CreditLimitView result = loadCreditLimit(limitId, actor.tenantId(), false);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "credit_limit:approve",
                request.approved() ? "CREDIT_LIMIT_APPROVED" : "CREDIT_LIMIT_REJECTED",
                "credit_limit", limitId, limit, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    public ApiPage<InvoiceView> listInvoices(
            Jwt jwt, UUID requestedNppId, int page, int size, HttpServletRequest request) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ScopedNpp scope = scopedNpp(jwt, actor, "debt:view", "i.npp_tenant_id");
        StringBuilder where = new StringBuilder("i.deleted_at IS NULL AND ").append(scope.sql());
        List<Object> args = new ArrayList<>(scope.arguments());
        if (requestedNppId != null) {
            requireNppScope(jwt, actor, "debt:view", requestedNppId);
            where.append(" AND i.npp_tenant_id = ?");
            args.add(requestedNppId);
        }
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM invoice i WHERE
                """ + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<InvoiceView> rows = jdbc.query("""
                SELECT i.id, i.npp_tenant_id, i.order_si_id, i.invoice_code, i.amount_vnd,
                    i.issued_at, i.due_at, i.status, i.version,
                    GREATEST(i.amount_vnd - COALESCE((
                        SELECT SUM(p.amount_vnd) FROM payment p
                        WHERE p.invoice_id = i.id AND p.status = 'RECORDED' AND p.deleted_at IS NULL
                    ), 0) - COALESCE((
                        SELECT SUM(d.amount_vnd) FROM deduction d
                        WHERE d.invoice_id = i.id AND d.status = 'APPROVED'
                    ), 0), 0) AS outstanding_vnd
                FROM invoice i WHERE
                """ + where + " ORDER BY i.issued_at DESC, i.id LIMIT ? OFFSET ?",
                (rs, row) -> new InvoiceView(rs.getObject("id", UUID.class),
                        rs.getObject("npp_tenant_id", UUID.class), rs.getObject("order_si_id", UUID.class),
                        rs.getString("invoice_code"), rs.getLong("amount_vnd"),
                        rs.getLong("outstanding_vnd"), rs.getObject("issued_at", OffsetDateTime.class),
                        rs.getObject("due_at", OffsetDateTime.class), rs.getString("status"),
                        rs.getLong("version")), args.toArray());
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "debt:view",
                "INVOICES_VIEWED", "invoice", null, null,
                Map.of("nppId", requestedNppId == null ? "" : requestedNppId.toString()),
                "Tra cứu hóa đơn", request.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                request.getHeader("User-Agent"));
        return ApiPage.of(rows, page, size, total == null ? 0 : total);
    }

    public ApiPage<PaymentView> listPayments(
            Jwt jwt, UUID requestedNppId, int page, int size, HttpServletRequest request) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ScopedNpp scope = scopedNpp(jwt, actor, "debt:view", "p.npp_tenant_id");
        StringBuilder where = new StringBuilder("p.deleted_at IS NULL AND ").append(scope.sql());
        List<Object> args = new ArrayList<>(scope.arguments());
        if (requestedNppId != null) {
            requireNppScope(jwt, actor, "debt:view", requestedNppId);
            where.append(" AND p.npp_tenant_id = ?");
            args.add(requestedNppId);
        }
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment p WHERE
                """ + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<PaymentView> rows = jdbc.query("""
                SELECT p.id, p.npp_tenant_id, p.invoice_id, p.amount_vnd, p.reference_code,
                    p.status, p.paid_at, p.created_at, p.version
                FROM payment p WHERE
                """ + where + " ORDER BY p.created_at DESC, p.id LIMIT ? OFFSET ?",
                (rs, row) -> new PaymentView(rs.getObject("id", UUID.class),
                        rs.getObject("npp_tenant_id", UUID.class), rs.getObject("invoice_id", UUID.class),
                        rs.getLong("amount_vnd"), rs.getString("reference_code"), rs.getString("status"),
                        rs.getObject("paid_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getLong("version")),
                args.toArray());
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "debt:view",
                "PAYMENTS_VIEWED", "payment", null, null,
                Map.of("nppId", requestedNppId == null ? "" : requestedNppId.toString()),
                "Tra cứu thanh toán", request.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                request.getHeader("User-Agent"));
        return ApiPage.of(rows, page, size, total == null ? 0 : total);
    }

    @Transactional
    public PaymentView declarePayment(
            Jwt jwt, DeclarePaymentRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        if (!"NPP".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chỉ NPP mới được khai báo thanh toán.");
        }
        InvoiceRow invoice = request.invoiceId() == null
                ? null : requireInvoiceForNpp(request.invoiceId(), actor.tenantId());
        UUID nsxId = invoice == null ? request.nsxTenantId() : invoice.tenantId();
        if (nsxId == null) {
            throw new ApiProblem("NSX_REQUIRED", "Cần chọn NSX nhận thanh toán.", HttpStatus.BAD_REQUEST);
        }
        requireRelationship(nsxId, actor.tenantId());
        if (invoice != null && !invoice.tenantId().equals(nsxId)) {
            throw new ApiProblem("INVOICE_NOT_FOUND", "Không tìm thấy hóa đơn của NPP.",
                    HttpStatus.NOT_FOUND);
        }
        if (invoice != null && request.nsxTenantId() != null
                && !invoice.tenantId().equals(request.nsxTenantId())) {
            throw new ApiProblem("INVOICE_NOT_FOUND", "Không tìm thấy hóa đơn của NPP.",
                    HttpStatus.NOT_FOUND);
        }
        Boolean duplicateReference = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM payment WHERE tenant_id = ? AND reference_code = ?)
                """, Boolean.class, nsxId, request.referenceCode().strip());
        if (Boolean.TRUE.equals(duplicateReference)) {
            throw new ApiProblem("PAYMENT_REFERENCE_DUPLICATE",
                    "Mã tham chiếu thanh toán đã được sử dụng.", HttpStatus.CONFLICT);
        }
        if (invoice != null) {
            Long alreadyCommitted = jdbc.queryForObject("""
                    SELECT COALESCE((
                        SELECT SUM(amount_vnd) FROM payment
                        WHERE invoice_id = ? AND deleted_at IS NULL AND status IN ('DECLARED', 'RECORDED')
                    ), 0) + COALESCE((
                        SELECT SUM(amount_vnd) FROM deduction
                        WHERE invoice_id = ? AND status = 'APPROVED'
                    ), 0)
                    """, Long.class, invoice.id(), invoice.id());
            if (PricingMath.add(alreadyCommitted == null ? 0 : alreadyCommitted, request.amountVnd())
                    > invoice.amountVnd()) {
                throw new ApiProblem("PAYMENT_EXCEEDS_INVOICE",
                        "Tổng thanh toán đã khai báo vượt số dư hóa đơn.", HttpStatus.CONFLICT);
            }
        }
        UUID paymentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment (
                    id, tenant_id, npp_tenant_id, invoice_id, amount_vnd,
                    reference_code, status, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'DECLARED', ?)
                """, paymentId, nsxId, actor.tenantId(), request.invoiceId(),
                request.amountVnd(), request.referenceCode().strip(), actor.userId());
        PaymentView result = loadPayment(paymentId, nsxId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "payment:declare",
                "PAYMENT_DECLARED", "payment", paymentId, null, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public PaymentView recordPayment(
            Jwt jwt, UUID paymentId, RecordPaymentRequest request,
            HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        requireNsx(actor);
        PaymentRow payment = requirePayment(jwt, actor, "payment:record", paymentId, true);
        if (!"DECLARED".equals(payment.status())) {
            throw new ApiProblem("PAYMENT_NOT_DECLARED", "Chỉ thanh toán đã khai báo mới được ghi nhận.",
                    HttpStatus.CONFLICT);
        }
        int changed = jdbc.update("""
                UPDATE payment SET status = 'RECORDED', paid_at = ?, updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = ? AND tenant_id = ? AND status = 'DECLARED' AND version = ?
                """, request.paidAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.paidAt(),
                paymentId, actor.tenantId(), request.version());
        if (changed == 0) {
            throw versionConflict("PAYMENT_VERSION_CONFLICT", paymentId);
        }
        if (payment.invoiceId() != null) {
            updateInvoiceStatus(payment.invoiceId(), actor.tenantId());
        }
        PaymentView result = loadPayment(paymentId, actor.tenantId());
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "payment:record",
                "PAYMENT_RECORDED", "payment", paymentId, payment, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public DeductionView recordDeduction(
            Jwt jwt, DeductionRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        requireNsx(actor);
        requireNppScope(jwt, actor, "payment:record", request.nppTenantId());
        InvoiceRow invoice = request.invoiceId() == null
                ? null : requireInvoiceForNpp(request.invoiceId(), request.nppTenantId());
        if (invoice != null && !invoice.tenantId().equals(actor.tenantId())) {
            throw new ApiProblem("INVOICE_NOT_FOUND", "Không tìm thấy hóa đơn của NPP.",
                    HttpStatus.NOT_FOUND);
        }
        if (invoice != null) {
            Long alreadyCommitted = jdbc.queryForObject("""
                    SELECT COALESCE((
                        SELECT SUM(amount_vnd) FROM payment
                        WHERE invoice_id = ? AND deleted_at IS NULL AND status IN ('DECLARED', 'RECORDED')
                    ), 0) + COALESCE((
                        SELECT SUM(amount_vnd) FROM deduction
                        WHERE invoice_id = ? AND status = 'APPROVED'
                    ), 0)
                    """, Long.class, invoice.id(), invoice.id());
            if (PricingMath.add(alreadyCommitted == null ? 0 : alreadyCommitted, request.amountVnd())
                    > invoice.amountVnd()) {
                throw new ApiProblem("DEDUCTION_EXCEEDS_INVOICE",
                        "Tổng thanh toán và khấu trừ vượt số tiền hóa đơn.", HttpStatus.CONFLICT);
            }
        }
        UUID deductionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deduction (
                    id, tenant_id, npp_tenant_id, invoice_id, amount_vnd,
                    reason, status, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'APPROVED', ?)
                """, deductionId, actor.tenantId(), request.nppTenantId(), request.invoiceId(),
                request.amountVnd(), request.reason().strip(), actor.userId());
        if (invoice != null) {
            updateInvoiceStatus(invoice.id(), actor.tenantId());
        }
        DeductionView result = loadDeduction(deductionId, actor.tenantId());
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "payment:record",
                "DEDUCTION_RECORDED", "deduction", deductionId, null, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    public ApiPage<DeductionView> listDeductions(
            Jwt jwt, UUID requestedNppId, int page, int size, HttpServletRequest request) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ScopedNpp scope = scopedNpp(jwt, actor, "debt:view", "d.npp_tenant_id");
        StringBuilder where = new StringBuilder(scope.sql());
        List<Object> args = new ArrayList<>(scope.arguments());
        if (requestedNppId != null) {
            requireNppScope(jwt, actor, "debt:view", requestedNppId);
            where.append(" AND d.npp_tenant_id = ?");
            args.add(requestedNppId);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM deduction d WHERE " + where,
                Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<DeductionView> rows = jdbc.query("""
                SELECT d.id, d.npp_tenant_id, d.invoice_id, d.amount_vnd, d.reason,
                    d.status, d.created_at, d.version
                FROM deduction d WHERE
                """ + where + " ORDER BY d.created_at DESC, d.id LIMIT ? OFFSET ?",
                (rs, row) -> new DeductionView(rs.getObject("id", UUID.class),
                        rs.getObject("npp_tenant_id", UUID.class), rs.getObject("invoice_id", UUID.class),
                        rs.getLong("amount_vnd"), rs.getString("reason"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getLong("version")),
                args.toArray());
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "debt:view",
                "DEDUCTIONS_VIEWED", "deduction", null, null,
                Map.of("nppId", requestedNppId == null ? "" : requestedNppId.toString()),
                "Tra cứu khấu trừ", request.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                request.getHeader("User-Agent"));
        return ApiPage.of(rows, page, size, total == null ? 0 : total);
    }

    private ScopedNpp scopedNpp(
            Jwt jwt, BusinessAccessService.Actor actor, String permission, String nppColumn) {
        if ("NPP".equals(actor.tenantType())) {
            return new ScopedNpp(nppColumn + " = ?", List.of(actor.tenantId()));
        }
        if ("NSX".equals(actor.tenantType())) {
            PartnerAccessService.Filter npps = partnerAccess.npps(jwt, actor, permission, "n");
            String tableAlias = nppColumn.substring(0, nppColumn.indexOf('.'));
            List<Object> arguments = new ArrayList<>();
            arguments.add(actor.tenantId());
            arguments.addAll(npps.arguments());
            return new ScopedNpp(tableAlias + ".tenant_id = ? AND " + nppColumn
                    + " IN (SELECT n.id FROM tenant n WHERE " + npps.sql() + ")", arguments);
        }
        return new ScopedNpp("FALSE", List.of());
    }

    private void requireNppScope(
            Jwt jwt, BusinessAccessService.Actor actor, String permission, UUID nppId) {
        if ("NPP".equals(actor.tenantType())) {
            if (!actor.tenantId().equals(nppId)) {
                throw new AccessDeniedException("Không được truy cập công nợ của NPP khác.");
            }
        } else if ("NSX".equals(actor.tenantType())) {
            partnerAccess.requireNpp(jwt, actor, permission, nppId);
        } else {
            throw new AccessDeniedException("Không được truy cập dữ liệu công nợ.");
        }
    }

    private void requireNsx(BusinessAccessService.Actor actor) {
        if (!"NSX".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chức năng này chỉ dành cho tài khoản NSX.");
        }
    }

    private CreditLimitRow requireCreditLimit(
            Jwt jwt, BusinessAccessService.Actor actor, String permission, UUID limitId, boolean lock) {
        ScopedNpp scope = scopedNpp(jwt, actor, permission, "cl.npp_tenant_id");
        String sql = """
                SELECT cl.id, cl.tenant_id, cl.npp_tenant_id, cl.created_by, cl.status, cl.version,
                    cl.limit_amount_vnd, cl.limit_qty, cl.unit_code, cl.effective_from
                FROM credit_limit cl
                WHERE cl.id = ? AND cl.tenant_id = ? AND
                """ + scope.sql() + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> new CreditLimitRow(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("npp_tenant_id", UUID.class), rs.getObject("created_by", UUID.class),
                rs.getString("status"), rs.getLong("version"),
                (Long) rs.getObject("limit_amount_vnd"), (Long) rs.getObject("limit_qty"),
                rs.getString("unit_code"), rs.getObject("effective_from", OffsetDateTime.class)),
                prepend(limitId, actor.tenantId(), scope.arguments()).toArray())
                .stream().findFirst()
                .orElseThrow(() -> new ApiProblem("CREDIT_LIMIT_NOT_FOUND",
                        "Không tìm thấy đề xuất hạn mức.", HttpStatus.NOT_FOUND));
    }

    private CreditLimitView loadCreditLimit(UUID limitId, UUID tenantId, boolean staff) {
        return jdbc.query("""
                SELECT cl.id, cl.tenant_id, cl.npp_tenant_id, n.name AS npp_name,
                    cl.limit_amount_vnd, cl.limit_qty, cl.unit_code, cl.status,
                    cl.effective_from, cl.effective_to, cl.created_by, cl.approved_by, cl.version,
                    COALESCE((
                        SELECT SUM(GREATEST(i.amount_vnd
                            - COALESCE((SELECT SUM(p.amount_vnd) FROM payment p
                                WHERE p.invoice_id = i.id AND p.status = 'RECORDED'
                                  AND p.deleted_at IS NULL), 0)
                            - COALESCE((SELECT SUM(d.amount_vnd) FROM deduction d
                                WHERE d.invoice_id = i.id AND d.status = 'APPROVED'), 0), 0))
                        FROM invoice i WHERE i.tenant_id = cl.tenant_id
                            AND i.npp_tenant_id = cl.npp_tenant_id
                            AND i.deleted_at IS NULL AND i.status IN ('OPEN', 'PARTIAL')
                    ), 0) AS outstanding_vnd,
                    COALESCE((
                        SELECT SUM(os.total_vnd) FROM order_si os
                        WHERE os.tenant_id = cl.tenant_id AND os.npp_tenant_id = cl.npp_tenant_id
                          AND os.deleted_at IS NULL
                          AND os.status IN ('SUBMITTED', 'PENDING_APPROVAL', 'HELD_CREDIT', 'APPROVED')
                    ), 0) AS reserved_vnd,
                    COALESCE((
                        SELECT SUM(ol.ordered_qty) FROM order_si os
                        JOIN order_line ol ON ol.tenant_id = os.tenant_id AND ol.order_si_id = os.id
                        WHERE os.tenant_id = cl.tenant_id AND os.npp_tenant_id = cl.npp_tenant_id
                          AND os.deleted_at IS NULL
                          AND os.status IN ('SUBMITTED', 'PENDING_APPROVAL', 'HELD_CREDIT', 'APPROVED', 'SHIPPED')
                          AND ol.deleted_at IS NULL
                    ), 0) AS reserved_qty
                FROM credit_limit cl JOIN tenant n ON n.id = cl.npp_tenant_id
                WHERE cl.id = ? AND cl.tenant_id = ?
                """, (rs, row) -> toLimitView(rs, staff), limitId, tenantId)
                .stream().findFirst()
                .orElseThrow(() -> new ApiProblem("CREDIT_LIMIT_NOT_FOUND",
                        "Không tìm thấy hạn mức.", HttpStatus.NOT_FOUND));
    }

    private CreditLimitView toLimitView(java.sql.ResultSet rs, boolean staff)
            throws java.sql.SQLException {
        Long limitAmount = (Long) rs.getObject("limit_amount_vnd");
        Long limitQty = (Long) rs.getObject("limit_qty");
        Long outstanding = rs.getLong("outstanding_vnd");
        Long reservedVnd = rs.getLong("reserved_vnd");
        long reservedQty = rs.getLong("reserved_qty");
        Long remaining = limitAmount == null ? null : Math.max(0,
                limitAmount - PricingMath.add(outstanding, reservedVnd));
        Long remainingQty = limitQty == null ? null : Math.max(0, limitQty - reservedQty);
        return new CreditLimitView(rs.getObject("id", UUID.class),
                rs.getObject("npp_tenant_id", UUID.class), rs.getString("npp_name"),
                staff ? null : limitAmount, staff ? null : limitQty,
                staff ? null : rs.getString("unit_code"),
                staff ? null : outstanding, remaining, remainingQty, rs.getString("status"),
                rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class), rs.getObject("approved_by", UUID.class),
                rs.getLong("version"));
    }

    private boolean isNppStaff(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("NPP_STAFF")
                && !roles.contains("NPP_OWNER") && !roles.contains("NPP_ACCT");
    }

    private InvoiceRow requireInvoiceForNpp(UUID invoiceId, UUID nppId) {
        return jdbc.query("""
                SELECT id, tenant_id, npp_tenant_id, amount_vnd
                FROM invoice WHERE id = ? AND npp_tenant_id = ? AND deleted_at IS NULL
                FOR UPDATE
                """, (rs, row) -> new InvoiceRow(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("npp_tenant_id", UUID.class),
                rs.getLong("amount_vnd")), invoiceId, nppId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("INVOICE_NOT_FOUND",
                        "Không tìm thấy hóa đơn của NPP.", HttpStatus.NOT_FOUND));
    }

    private PaymentRow requirePayment(
            Jwt jwt, BusinessAccessService.Actor actor, String permission, UUID paymentId, boolean lock) {
        ScopedNpp scope = scopedNpp(jwt, actor, permission, "p.npp_tenant_id");
        String sql = """
                SELECT p.id, p.tenant_id, p.npp_tenant_id, p.invoice_id, p.amount_vnd,
                    p.reference_code, p.status, p.created_by, p.version
                FROM payment p
                WHERE p.id = ? AND p.tenant_id = ? AND p.deleted_at IS NULL AND
                """ + scope.sql() + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> new PaymentRow(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("npp_tenant_id", UUID.class), rs.getObject("invoice_id", UUID.class),
                rs.getLong("amount_vnd"), rs.getString("reference_code"), rs.getString("status"),
                rs.getObject("created_by", UUID.class), rs.getLong("version")),
                prepend(paymentId, actor.tenantId(), scope.arguments()).toArray())
                .stream().findFirst()
                .orElseThrow(() -> new ApiProblem("PAYMENT_NOT_FOUND", "Không tìm thấy thanh toán.",
                        HttpStatus.NOT_FOUND));
    }

    private PaymentView loadPayment(UUID paymentId, UUID tenantId) {
        return jdbc.query("""
                SELECT id, npp_tenant_id, invoice_id, amount_vnd, reference_code,
                    status, paid_at, created_at, version
                FROM payment WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                """, (rs, row) -> new PaymentView(rs.getObject("id", UUID.class),
                rs.getObject("npp_tenant_id", UUID.class), rs.getObject("invoice_id", UUID.class),
                rs.getLong("amount_vnd"), rs.getString("reference_code"), rs.getString("status"),
                rs.getObject("paid_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("version")), paymentId, tenantId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("PAYMENT_NOT_FOUND", "Không tìm thấy thanh toán.",
                        HttpStatus.NOT_FOUND));
    }

    private DeductionView loadDeduction(UUID deductionId, UUID tenantId) {
        return jdbc.query("""
                SELECT id, npp_tenant_id, invoice_id, amount_vnd, reason, status, created_at, version
                FROM deduction WHERE id = ? AND tenant_id = ?
                """, (rs, row) -> new DeductionView(rs.getObject("id", UUID.class),
                rs.getObject("npp_tenant_id", UUID.class), rs.getObject("invoice_id", UUID.class),
                rs.getLong("amount_vnd"), rs.getString("reason"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getLong("version")),
                deductionId, tenantId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("DEDUCTION_NOT_FOUND", "Không tìm thấy khoản khấu trừ.",
                        HttpStatus.NOT_FOUND));
    }

    private void updateInvoiceStatus(UUID invoiceId, UUID tenantId) {
        jdbc.update("""
                UPDATE invoice i SET status = CASE
                    WHEN GREATEST(i.amount_vnd - COALESCE((
                        SELECT SUM(p.amount_vnd) FROM payment p WHERE p.invoice_id = i.id
                            AND p.status = 'RECORDED' AND p.deleted_at IS NULL
                    ), 0) - COALESCE((
                        SELECT SUM(d.amount_vnd) FROM deduction d WHERE d.invoice_id = i.id
                            AND d.status = 'APPROVED'
                    ), 0), 0) = 0 THEN 'PAID'
                    WHEN EXISTS (SELECT 1 FROM payment p WHERE p.invoice_id = i.id
                        AND p.status = 'RECORDED' AND p.deleted_at IS NULL) THEN 'PARTIAL'
                    WHEN EXISTS (SELECT 1 FROM deduction d WHERE d.invoice_id = i.id
                        AND d.status = 'APPROVED') THEN 'PARTIAL'
                    ELSE 'OPEN' END,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE i.id = ? AND i.tenant_id = ? AND i.deleted_at IS NULL
                """, invoiceId, tenantId);
    }

    private void requireRelationship(UUID nsxId, UUID nppId) {
        Boolean related = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM tenant_relation tr
                    JOIN tenant nsx ON nsx.id = tr.nsx_tenant_id
                        AND nsx.tenant_type = 'NSX' AND nsx.status = 'ACTIVE'
                    JOIN tenant npp ON npp.id = tr.npp_tenant_id
                        AND npp.tenant_type = 'NPP' AND npp.status = 'ACTIVE'
                    WHERE tr.nsx_tenant_id = ? AND tr.npp_tenant_id = ? AND tr.status = 'ACTIVE'
                      AND tr.effective_from <= CURRENT_DATE
                      AND (tr.effective_to IS NULL OR tr.effective_to >= CURRENT_DATE)
                )
                """, Boolean.class, nsxId, nppId);
        if (!Boolean.TRUE.equals(related)) {
            throw new ApiProblem("NPP_RELATION_REQUIRED",
                    "Không có quan hệ hiệu lực giữa nhà sản xuất và nhà phân phối.",
                    HttpStatus.FORBIDDEN);
        }
    }

    private ApiProblem versionConflict(String code, UUID id) {
        return new ApiProblem(code, "Bản ghi đã được cập nhật bởi người khác.",
                HttpStatus.CONFLICT, Map.of("id", id.toString()));
    }

    private static List<Object> prepend(UUID id, UUID tenantId, List<Object> tail) {
        List<Object> result = new ArrayList<>();
        result.add(id);
        result.add(tenantId);
        result.addAll(tail);
        return result;
    }

    private static String cleanNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record ScopedNpp(String sql, List<Object> arguments) {
    }

    private record CreditLimitRow(
            UUID id, UUID tenantId, UUID nppTenantId, UUID createdBy, String status, long version,
            Long limitAmountVnd, Long limitQty, String unitCode, OffsetDateTime effectiveFrom) {
    }

    private record InvoiceRow(UUID id, UUID tenantId, UUID nppTenantId, long amountVnd) {
    }

    private record PaymentRow(
            UUID id, UUID tenantId, UUID nppTenantId, UUID invoiceId, long amountVnd,
            String referenceCode, String status, UUID createdBy, long version) {
    }

    public record CreditLimitView(
            UUID id, UUID nppTenantId, String nppName, Long limitAmountVnd, Long limitQty,
            String unitCode, Long outstandingAmountVnd, Long remainingAmountVnd, Long remainingQty,
            String status,
            OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
            UUID createdBy, UUID approvedBy, long version) {
    }

    public record InvoiceView(
            UUID id, UUID nppTenantId, UUID orderSiId, String invoiceCode, long amountVnd,
            long outstandingVnd, OffsetDateTime issuedAt, OffsetDateTime dueAt,
            String status, long version) {
    }

    public record PaymentView(
            UUID id, UUID nppTenantId, UUID invoiceId, long amountVnd, String referenceCode,
            String status, OffsetDateTime paidAt, OffsetDateTime createdAt, long version) {
    }

    public record DeductionView(
            UUID id, UUID nppTenantId, UUID invoiceId, long amountVnd, String reason,
            String status, OffsetDateTime createdAt, long version) {
    }

    public record CreateCreditLimitRequest(
            @jakarta.validation.constraints.NotNull UUID nppTenantId,
            @jakarta.validation.constraints.PositiveOrZero Long limitAmountVnd,
            @jakarta.validation.constraints.PositiveOrZero Long limitQty,
            @jakarta.validation.constraints.Size(max = 32) String unitCode,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record CreditLimitActionRequest(
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record CreditLimitDecisionRequest(
            boolean approved,
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record DeclarePaymentRequest(
            UUID nsxTenantId,
            UUID invoiceId,
            @jakarta.validation.constraints.Positive long amountVnd,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 120)
            String referenceCode,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record RecordPaymentRequest(
            OffsetDateTime paidAt,
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record DeductionRequest(
            @jakarta.validation.constraints.NotNull UUID nppTenantId,
            UUID invoiceId,
            @jakarta.validation.constraints.Positive long amountVnd,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000)
            String reason) {
    }
}
