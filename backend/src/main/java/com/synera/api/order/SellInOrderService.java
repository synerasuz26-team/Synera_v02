package com.synera.api.order;

import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.ApiPage;
import com.synera.api.catalog.EffectivePricingService;
import com.synera.api.catalog.PricingMath;
import com.synera.api.organization.BusinessAccessService;
import com.synera.api.partner.PartnerAccessService;
import com.synera.api.security.AuditService;
import com.synera.api.security.ResolvedScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SellInOrderService {
    private final JdbcTemplate jdbc;
    private final BusinessAccessService access;
    private final PartnerAccessService partnerAccess;
    private final EffectivePricingService pricing;
    private final OrderFlowService orderFlow;
    private final AuditService audit;

    public SellInOrderService(
            JdbcTemplate jdbc, BusinessAccessService access, PartnerAccessService partnerAccess,
            EffectivePricingService pricing, OrderFlowService orderFlow, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.partnerAccess = partnerAccess;
        this.pricing = pricing;
        this.orderFlow = orderFlow;
        this.audit = audit;
    }

    public ApiPage<OrderSummary> list(
            Jwt jwt, String status, UUID nppId, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ScopedOrders scope = scopedOrders(jwt, actor, "order_si:view");
        StringBuilder where = new StringBuilder("os.deleted_at IS NULL AND ").append(scope.sql());
        List<Object> arguments = new ArrayList<>(scope.arguments());
        if (status != null && !status.isBlank()) {
            where.append(" AND os.status = ?");
            arguments.add(status.strip().toUpperCase());
        }
        if (nppId != null) {
            where.append(" AND os.npp_tenant_id = ?");
            arguments.add(nppId);
        }
        long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_si os JOIN tenant n ON n.id = os.npp_tenant_id
                WHERE
                """ + where, Long.class, arguments.toArray());
        arguments.add(size);
        arguments.add((long) page * size);
        List<OrderSummary> items = jdbc.query("""
                SELECT os.id, os.order_code, os.status, os.npp_tenant_id,
                    n.name AS npp_name, os.total_vnd, os.created_at, os.updated_at, os.version
                FROM order_si os JOIN tenant n ON n.id = os.npp_tenant_id
                WHERE
                """ + where + " ORDER BY os.created_at DESC, os.id LIMIT ? OFFSET ?",
                (rs, row) -> new OrderSummary(rs.getObject("id", UUID.class),
                        rs.getString("order_code"), rs.getString("status"),
                        rs.getObject("npp_tenant_id", UUID.class), rs.getString("npp_name"),
                        rs.getLong("total_vnd"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("version")),
                arguments.toArray());
        return ApiPage.of(items, page, size, total);
    }

    public OrderDetail get(Jwt jwt, UUID orderId) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        return detail(requireOrder(jwt, actor, "order_si:view", orderId, false));
    }

    @Transactional
    public OrderDetail updateDraft(
            Jwt jwt, UUID orderId, DraftUpdateRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        OrderHeader order = requireOrder(jwt, actor, "order_si:cancel_draft", orderId, true);
        if (!"NPP".equals(actor.tenantType()) || !order.nppTenantId().equals(actor.tenantId())
                || !actor.userId().equals(order.createdBy())) {
            throw new AccessDeniedException("Chỉ người tạo đơn thuộc NPP mới được sửa đơn.");
        }
        if (!Set.of("DRAFT", "NEED_INFO").contains(order.status())) {
            throw conflict("ORDER_NOT_EDITABLE", "Chỉ được sửa đơn nháp hoặc được yêu cầu bổ sung.", order);
        }
        List<PricedLine> lines = calculateLines(jwt, order.nppTenantId(), request.lines(), servletRequest);
        long total = sum(lines.stream().map(PricedLine::amountVnd).toList());
        long subtotal = sum(lines.stream().map(PricedLine::grossVnd).toList());
        int changed = jdbc.update("""
                UPDATE order_si SET note = ?, subtotal_vnd = ?, total_vnd = ?,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND tenant_id = ? AND status = ? AND version = ?
                """, clean(request.note()), subtotal, total, order.id(), order.tenantId(),
                order.status(), request.version());
        if (changed == 0) {
            throw versionConflict(order.id());
        }
        jdbc.update("""
                UPDATE order_line SET deleted_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND order_si_id = ? AND deleted_at IS NULL
                """, order.tenantId(), order.id());
        replaceLines(order.tenantId(), order.id(), lines);
        OrderDetail result = detail(loadById(order.id()));
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_si:cancel_draft",
                "ORDER_SI_DRAFT_UPDATED", "order_si", order.id(), order, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public OrderDetail create(
            Jwt jwt, String idempotencyKey, CreateRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        requireNpp(actor);
        UUID nppId = actor.tenantId();
        UUID nsxId = request.nsxTenantId();
        requireRelationship(nsxId, nppId);
        String requestHash = requestHash(nsxId, nppId, request);
        OrderDetail duplicate = findIdempotent(actor, nppId, idempotencyKey, requestHash);
        if (duplicate != null) {
            return duplicate;
        }

        List<PricedLine> lines = calculateLines(jwt, nppId, request.lines(), servletRequest);
        long total = sum(lines.stream().map(PricedLine::amountVnd).toList());
        long subtotal = sum(lines.stream().map(PricedLine::grossVnd).toList());
        UUID orderId = UUID.randomUUID();
        String orderCode = "SI-" + orderId.toString().replace("-", "").substring(0, 16).toUpperCase();
        int inserted = jdbc.update("""
                INSERT INTO order_si (
                    id, tenant_id, npp_tenant_id, order_code, status, idempotency_key,
                    request_hash, created_by, note, subtotal_vnd, total_vnd
                ) VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """, orderId, nsxId, nppId, orderCode, idempotencyKey, requestHash,
                actor.userId(), clean(request.note()), subtotal, total);
        if (inserted == 0) {
            OrderDetail raced = findIdempotent(actor, nppId, idempotencyKey, requestHash);
            if (raced == null) {
                throw idempotencyConflict();
            }
            return raced;
        }
        replaceLines(nsxId, orderId, lines);
        jdbc.update("""
                INSERT INTO order_status_history (
                    id, tenant_id, order_si_id, from_status, to_status, actor_id, reason
                ) VALUES (?, ?, ?, NULL, 'DRAFT', ?, ?)
                """, UUID.randomUUID(), nsxId, orderId, actor.userId(), "Tạo đơn bán vào.");

        OrderHeader order = loadById(orderId);
        if (request.submit() == null || request.submit()) {
            submit(order, actor, request.reason());
        }
        OrderDetail created = detail(loadById(orderId));
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_si:create",
                "ORDER_SI_CREATED", "order_si", orderId, null, created, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return created;
    }

    @Transactional
    public OrderDetail repeat(
            Jwt jwt, UUID orderId, String idempotencyKey, RepeatRequest request,
            HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        OrderHeader source = requireOrder(jwt, actor, "order_si:view", orderId, false);
        if (!"NPP".equals(actor.tenantType()) || !source.nppTenantId().equals(actor.tenantId())) {
            throw new AccessDeniedException("Chỉ NPP của đơn mới được đặt lại đơn này.");
        }
        List<EffectivePricingService.CalculationLine> lines = jdbc.query("""
                SELECT product_id, entered_unit_code, entered_qty
                FROM order_line
                WHERE order_si_id = ? AND tenant_id = ? AND deleted_at IS NULL AND is_gift = FALSE
                ORDER BY product_id
                """, (rs, row) -> new EffectivePricingService.CalculationLine(
                rs.getObject("product_id", UUID.class), rs.getString("entered_unit_code"),
                rs.getLong("entered_qty")), source.id(), source.tenantId());
        CreateRequest requestBody = new CreateRequest(source.tenantId(), lines, source.note(),
                request.reason(), true);
        return create(jwt, idempotencyKey, requestBody, servletRequest);
    }

    @Transactional
    public OrderDetail submit(
            Jwt jwt, UUID orderId, SubmitRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        OrderHeader order = requireOrder(jwt, actor, "order_si:create", orderId, true);
        if (!"NPP".equals(actor.tenantType()) || !order.nppTenantId().equals(actor.tenantId())) {
            throw new AccessDeniedException("Chỉ NPP của đơn mới được gửi hoặc gửi lại đơn.");
        }
        if (!Set.of("DRAFT", "NEED_INFO").contains(order.status())) {
            throw conflict("ORDER_NOT_SUBMITTABLE", "Đơn không ở trạng thái có thể gửi.", order);
        }
        List<EffectivePricingService.CalculationLine> input = jdbc.query("""
                SELECT product_id, entered_unit_code, entered_qty
                FROM order_line
                WHERE order_si_id = ? AND tenant_id = ? AND deleted_at IS NULL AND is_gift = FALSE
                ORDER BY product_id
                """, (rs, row) -> new EffectivePricingService.CalculationLine(
                rs.getObject("product_id", UUID.class), rs.getString("entered_unit_code"),
                rs.getLong("entered_qty")), order.id(), order.tenantId());
        if (input.isEmpty()) {
            throw new ApiProblem("ORDER_LINES_REQUIRED", "Đơn hàng cần có ít nhất một mặt hàng.",
                    HttpStatus.BAD_REQUEST);
        }
        List<PricedLine> lines = calculateLines(jwt, order.nppTenantId(), input, servletRequest);
        persistRecalculation(order, lines);
        long version = order.version() + 1;
        if ("DRAFT".equals(order.status())) {
            version = orderFlow.transition("SELL_IN", order.tenantId(), order.id(), "DRAFT", "SUBMITTED",
                    "order_si:create", actor.userId(), request.reason(), order.version() + 1);
        } else {
            version = orderFlow.transition("SELL_IN", order.tenantId(), order.id(), "NEED_INFO", "SUBMITTED",
                    "order_si:create", actor.userId(), request.reason(), order.version() + 1);
        }
        jdbc.update("""
                UPDATE order_si SET submitted_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND version = ?
                """, order.id(), order.tenantId(), version);
        OrderHeader submitted = loadById(order.id());
        applyCreditHold(submitted);
        OrderDetail result = detail(loadById(order.id()));
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_si:create",
                "ORDER_SI_SUBMITTED", "order_si", order.id(), order, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public OrderDetail review(
            Jwt jwt, UUID orderId, ReviewRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        requireNsx(actor);
        OrderHeader order = requireOrder(jwt, actor, "order_si:review", orderId, true);
        if (!"SUBMITTED".equals(order.status())) {
            throw conflict("ORDER_NOT_REVIEWABLE", "Chỉ đơn mới gửi mới được tiếp nhận.", order);
        }
        long version = orderFlow.transition("SELL_IN", order.tenantId(), order.id(), "SUBMITTED",
                "PENDING_APPROVAL", "order_si:review", actor.userId(), request.reason(), request.version());
        OrderDetail result = detail(loadById(order.id()));
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_si:review",
                "ORDER_SI_REVIEWED", "order_si", order.id(), order, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public OrderDetail approve(
            Jwt jwt, UUID orderId, DecisionRequest request, boolean overLimit,
            HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        requireNsx(actor);
        String permission = overLimit ? "order_si:approve_over_limit" : "order_si:approve";
        OrderHeader order = requireOrder(jwt, actor, permission, orderId, true);
        if (actor.userId().equals(order.createdBy())) {
            throw new ApiProblem("APPROVER_MUST_DIFFER", "Người duyệt không được là người tạo đơn.",
                    HttpStatus.FORBIDDEN);
        }
        String expectedStatus = overLimit ? "HELD_CREDIT" : "PENDING_APPROVAL";
        if (!expectedStatus.equals(order.status())) {
            throw conflict("ORDER_NOT_APPROVABLE", "Trạng thái đơn không phù hợp với cấp duyệt.", order);
        }
        int changed = jdbc.update("""
                UPDATE order_si SET approved_by = ? WHERE id = ? AND tenant_id = ?
                    AND status = ? AND version = ?
                """, actor.userId(), order.id(), order.tenantId(), order.status(), request.version());
        if (changed == 0) {
            throw versionConflict(order.id());
        }
        jdbc.update("""
                UPDATE order_line SET confirmed_qty = ordered_qty
                WHERE tenant_id = ? AND order_si_id = ? AND deleted_at IS NULL
                """, order.tenantId(), order.id());
        orderFlow.transition("SELL_IN", order.tenantId(), order.id(), order.status(), "APPROVED",
                permission, actor.userId(), request.reason(), request.version());
        OrderDetail result = detail(loadById(order.id()));
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), permission,
                overLimit ? "ORDER_SI_APPROVED_OVER_LIMIT" : "ORDER_SI_APPROVED",
                "order_si", order.id(), order, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public OrderDetail reject(
            Jwt jwt, UUID orderId, RejectRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        requireNsx(actor);
        OrderHeader order = requireOrder(jwt, actor, "order_si:reject", orderId, true);
        String toStatus;
        if (request.needInfo()) {
            if (!"PENDING_APPROVAL".equals(order.status())) {
                throw conflict("ORDER_CANNOT_REQUEST_INFO",
                        "Chỉ đơn đang chờ duyệt mới được yêu cầu bổ sung.", order);
            }
            toStatus = "NEED_INFO";
        } else if (Set.of("PENDING_APPROVAL", "HELD_CREDIT").contains(order.status())) {
            toStatus = "REJECTED";
        } else {
            throw conflict("ORDER_NOT_REJECTABLE", "Trạng thái đơn không cho phép từ chối.", order);
        }
        orderFlow.transition("SELL_IN", order.tenantId(), order.id(), order.status(), toStatus,
                "order_si:reject", actor.userId(), request.reason(), request.version());
        OrderDetail result = detail(loadById(order.id()));
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_si:reject",
                request.needInfo() ? "ORDER_SI_NEED_INFO" : "ORDER_SI_REJECTED",
                "order_si", order.id(), order, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public OrderDetail cancel(
            Jwt jwt, UUID orderId, CancelRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        OrderHeader order = requireOrder(jwt, actor, "order_si:cancel_draft", orderId, true);
        if (!"NPP".equals(actor.tenantType()) || !order.nppTenantId().equals(actor.tenantId())
                || !actor.userId().equals(order.createdBy())) {
            throw new AccessDeniedException("Chỉ người tạo đơn thuộc NPP mới được hủy đơn này.");
        }
        if (!Set.of("DRAFT", "SUBMITTED").contains(order.status())) {
            throw conflict("ORDER_NOT_CANCELLABLE", "Chỉ được hủy đơn trước khi NSX tiếp nhận.", order);
        }
        orderFlow.transition("SELL_IN", order.tenantId(), order.id(), order.status(), "CANCELLED",
                "order_si:cancel_draft", actor.userId(), request.reason(), request.version());
        OrderDetail result = detail(loadById(order.id()));
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_si:cancel_draft",
                "ORDER_SI_CANCELLED", "order_si", order.id(), order, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public OrderDetail ship(
            Jwt jwt, UUID orderId, ShipmentRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        requireNsx(actor);
        OrderHeader order = requireOrder(jwt, actor, "order_si:ship", orderId, true);
        if (!"APPROVED".equals(order.status())) {
            throw conflict("ORDER_NOT_READY_TO_SHIP", "Chỉ đơn đã duyệt mới được xuất kho.", order);
        }
        List<OrderLineState> lines = orderLines(order.id(), order.tenantId());
        Map<UUID, Long> shipped = validateQuantities(lines, request.lines(), true);
        for (OrderLineState line : lines) {
            if (shipped.get(line.id()) != line.confirmedQty()) {
                throw new ApiProblem("SHIPMENT_MUST_MATCH_CONFIRMED",
                        "Số xuất kho cần khớp số lượng đã được duyệt.", HttpStatus.BAD_REQUEST,
                        Map.of("orderLineId", line.id().toString()));
            }
            jdbc.update("""
                    UPDATE order_line SET shipped_qty = ?
                    WHERE id = ? AND tenant_id = ? AND order_si_id = ?
                    """, shipped.get(line.id()), line.id(), order.tenantId(), order.id());
        }
        orderFlow.transition("SELL_IN", order.tenantId(), order.id(), "APPROVED", "SHIPPED",
                "order_si:ship", actor.userId(), request.reason(), request.version());
        long invoiceAmount = jdbc.queryForObject("""
                SELECT COALESCE(SUM(line_amount_vnd), 0) FROM order_line
                WHERE tenant_id = ? AND order_si_id = ? AND is_gift = FALSE
                """, Long.class, order.tenantId(), order.id());
        UUID invoiceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO invoice (
                    id, tenant_id, npp_tenant_id, order_si_id, invoice_code, amount_vnd,
                    issued_at, status
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 'OPEN')
                ON CONFLICT (tenant_id, order_si_id) WHERE order_si_id IS NOT NULL AND deleted_at IS NULL
                DO NOTHING
                """, invoiceId, order.tenantId(), order.nppTenantId(), order.id(),
                "INV-" + order.orderCode(), invoiceAmount);
        OrderDetail result = detail(loadById(order.id()));
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_si:ship",
                "ORDER_SI_SHIPPED", "order_si", order.id(), order, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public OrderDetail receive(
            Jwt jwt, UUID orderId, ReceiptRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        OrderHeader order = requireOrder(jwt, actor, "order_si:receive_confirm", orderId, true);
        if (!"NPP".equals(actor.tenantType()) || !order.nppTenantId().equals(actor.tenantId())) {
            throw new AccessDeniedException("Chỉ NPP nhận hàng mới được xác nhận thực nhận.");
        }
        if (!"SHIPPED".equals(order.status())) {
            throw conflict("ORDER_NOT_SHIPPED", "Chỉ đơn đã xuất kho mới xác nhận thực nhận.", order);
        }
        List<OrderLineState> lines = orderLines(order.id(), order.tenantId());
        Map<UUID, ReceiptLineInput> received = validateReceipt(lines, request.lines());
        for (OrderLineState line : lines) {
            ReceiptLineInput receipt = received.get(line.id());
            jdbc.update("""
                    UPDATE order_line SET received_qty = ?
                    WHERE id = ? AND tenant_id = ? AND order_si_id = ?
                    """, receipt.receivedQty(), line.id(), order.tenantId(), order.id());
            jdbc.update("""
                    INSERT INTO order_si_receipt_line (
                        id, tenant_id, order_si_id, order_line_id, received_qty,
                        damaged_qty, note, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), order.tenantId(), order.id(), line.id(),
                    receipt.receivedQty(), receipt.damagedQty(), clean(receipt.note()), actor.userId());
        }
        jdbc.update("""
                UPDATE order_si SET received_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND version = ?
                """, order.id(), order.tenantId(), order.version());
        orderFlow.transition("SELL_IN", order.tenantId(), order.id(), "SHIPPED", "RECEIVED",
                "order_si:receive_confirm", actor.userId(), request.reason(), request.version());
        OrderDetail result = detail(loadById(order.id()));
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_si:receive_confirm",
                "ORDER_SI_RECEIVED", "order_si", order.id(), order, result, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    private void submit(
            OrderHeader order, BusinessAccessService.Actor actor, String reason) {
        long version = orderFlow.transition("SELL_IN", order.tenantId(), order.id(),
                "DRAFT", "SUBMITTED", "order_si:create", actor.userId(), reason, order.version());
        jdbc.update("""
                UPDATE order_si SET submitted_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND version = ?
                """, order.id(), order.tenantId(), version);
        applyCreditHold(loadById(order.id()));
    }

    private void applyCreditHold(OrderHeader order) {
        CreditPosition position = creditPosition(order.tenantId(), order.nppTenantId(), order.id());
        boolean overLimit = position.limitAmountVnd() == null && position.limitQty() == null
                || position.limitAmountVnd() != null
                    && safeAdd(position.outstandingAmountVnd(), order.totalVnd()) > position.limitAmountVnd()
                || position.limitQty() != null
                    && safeAdd(position.outstandingQty(), order.orderedQty()) > position.limitQty();
        if (overLimit) {
            orderFlow.transition("SELL_IN", order.tenantId(), order.id(), "SUBMITTED", "HELD_CREDIT",
                    "SYSTEM", null, "Giữ đơn do vượt hạn mức hoặc chưa có hạn mức hiệu lực.", order.version());
        }
    }

    private CreditPosition creditPosition(UUID nsxId, UUID nppId, UUID currentOrderId) {
        List<CreditLimit> limits = jdbc.query("""
                SELECT limit_amount_vnd, limit_qty
                FROM credit_limit
                WHERE tenant_id = ? AND npp_tenant_id = ? AND status = 'EFFECTIVE'
                  AND effective_from <= CURRENT_TIMESTAMP
                  AND (effective_to IS NULL OR effective_to >= CURRENT_TIMESTAMP)
                ORDER BY effective_from DESC, created_at DESC
                LIMIT 1 FOR UPDATE
                """, (rs, row) -> new CreditLimit(
                (Long) rs.getObject("limit_amount_vnd"), (Long) rs.getObject("limit_qty")),
                nsxId, nppId);
        if (limits.isEmpty()) {
            return new CreditPosition(null, null, 0, 0);
        }
        CreditLimit limit = limits.get(0);
        Long debt = jdbc.queryForObject("""
                SELECT COALESCE(SUM(GREATEST(i.amount_vnd
                    - COALESCE((SELECT SUM(p.amount_vnd) FROM payment p
                        WHERE p.invoice_id = i.id AND p.status = 'RECORDED' AND p.deleted_at IS NULL), 0)
                    - COALESCE((SELECT SUM(d.amount_vnd) FROM deduction d
                        WHERE d.invoice_id = i.id AND d.status = 'APPROVED'), 0), 0)), 0)
                FROM invoice i
                WHERE i.tenant_id = ? AND i.npp_tenant_id = ? AND i.deleted_at IS NULL
                  AND i.status IN ('OPEN', 'PARTIAL')
                """, Long.class, nsxId, nppId);
        Long committedAmount = jdbc.queryForObject("""
                SELECT COALESCE(SUM(total_vnd), 0) FROM order_si
                WHERE tenant_id = ? AND npp_tenant_id = ? AND id <> ? AND deleted_at IS NULL
                  AND status IN ('SUBMITTED', 'PENDING_APPROVAL', 'HELD_CREDIT', 'APPROVED')
                """, Long.class, nsxId, nppId, currentOrderId);
        Long committedQty = jdbc.queryForObject("""
                SELECT COALESCE(SUM(ol.ordered_qty), 0)
                FROM order_si os JOIN order_line ol ON ol.order_si_id = os.id
                WHERE os.tenant_id = ? AND os.npp_tenant_id = ? AND os.id <> ?
                  AND os.deleted_at IS NULL AND os.status IN
                    ('SUBMITTED', 'PENDING_APPROVAL', 'HELD_CREDIT', 'APPROVED', 'SHIPPED')
                  AND ol.deleted_at IS NULL
                """, Long.class, nsxId, nppId, currentOrderId);
        return new CreditPosition(limit.amountVnd(), limit.quantity(),
                safeAdd(debt, committedAmount), committedQty == null ? 0 : committedQty);
    }

    private List<PricedLine> calculateLines(
            Jwt jwt, UUID nppId, List<EffectivePricingService.CalculationLine> inputs,
            HttpServletRequest servletRequest) {
        if (inputs == null || inputs.isEmpty()) {
            throw new ApiProblem("ORDER_LINES_REQUIRED", "Đơn hàng cần có ít nhất một mặt hàng.",
                    HttpStatus.BAD_REQUEST);
        }
        Set<UUID> products = new HashSet<>();
        List<PricedLine> result = new ArrayList<>();
        for (EffectivePricingService.CalculationLine input : inputs) {
            if (!products.add(input.productId())) {
                throw new ApiProblem("DUPLICATE_ORDER_LINE", "Không thể lặp lại cùng SKU trong một đơn.",
                        HttpStatus.BAD_REQUEST);
            }
            EffectivePricingService.EffectivePrice effective = pricing.sellIn(
                    jwt, nppId, null, input.productId(), input.unitCode(),
                    OffsetDateTime.now(ZoneOffset.UTC), servletRequest);
            ProductUnit unit = jdbc.query("""
                    SELECT p.base_unit_code,
                        (SELECT uc.base_units FROM unit_conversion uc
                         WHERE uc.tenant_id = p.tenant_id AND uc.product_id = p.id AND uc.unit_code = ?) AS factor
                    FROM product p WHERE p.id = ? AND p.status = 'ACTIVE'
                    """, (rs, row) -> new ProductUnit(
                    rs.getString("base_unit_code"), (Long) rs.getObject("factor")),
                    input.unitCode().strip().toUpperCase(), input.productId()).stream().findFirst()
                    .orElseThrow(() -> new ApiProblem("PRODUCT_NOT_FOUND", "Không tìm thấy SKU đang kinh doanh.",
                            HttpStatus.NOT_FOUND));
            if (unit.factor() == null || unit.factor() <= 0) {
                throw new ApiProblem("INVALID_PRODUCT_UNIT", "Đơn vị không có trong quy cách SKU.",
                        HttpStatus.BAD_REQUEST);
            }
            long orderedQty = PricingMath.multiply(input.quantity(), unit.factor());
            long gross = PricingMath.multiply(effective.priceVnd(), input.quantity());
            result.add(new PricedLine(input.productId(), effective.sku(), effective.productName(),
                    unit.baseUnitCode(), input.unitCode().strip().toUpperCase(), input.quantity(),
                    orderedQty, effective.priceVnd(), gross, 0, gross, false));
        }
        return result;
    }

    private void replaceLines(UUID tenantId, UUID orderId, List<PricedLine> lines) {
        for (PricedLine line : lines) {
            jdbc.update("""
                    INSERT INTO order_line (
                        id, tenant_id, order_si_id, product_id, unit_code, entered_unit_code,
                        entered_qty, ordered_qty, confirmed_qty, shipped_qty, received_qty,
                        unit_price_vnd, is_gift, gross_amount_vnd, discount_vnd, line_amount_vnd
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), tenantId, orderId, line.productId(), line.baseUnitCode(),
                    line.enteredUnitCode(), line.enteredQty(), line.orderedQty(), line.unitPriceVnd(),
                    line.gift(), line.grossVnd(), line.discountVnd(), line.amountVnd());
        }
    }

    private void persistRecalculation(OrderHeader order, List<PricedLine> lines) {
        long total = sum(lines.stream().map(PricedLine::amountVnd).toList());
        long subtotal = sum(lines.stream().map(PricedLine::grossVnd).toList());
        int changed = jdbc.update("""
                UPDATE order_si SET subtotal_vnd = ?, total_vnd = ?, updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = ? AND tenant_id = ? AND status = ? AND version = ?
                """, subtotal, total, order.id(), order.tenantId(), order.status(), order.version());
        if (changed == 0) {
            throw versionConflict(order.id());
        }
        jdbc.update("""
                UPDATE order_line SET deleted_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND order_si_id = ? AND deleted_at IS NULL
                """, order.tenantId(), order.id());
        replaceLines(order.tenantId(), order.id(), lines);
    }

    private void requireRelationship(UUID nsxId, UUID nppId) {
        Boolean related = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM tenant_relation tr
                    JOIN tenant nsx ON nsx.id = tr.nsx_tenant_id AND nsx.tenant_type = 'NSX'
                        AND nsx.status = 'ACTIVE'
                    JOIN tenant npp ON npp.id = tr.npp_tenant_id AND npp.tenant_type = 'NPP'
                        AND npp.status = 'ACTIVE'
                    WHERE tr.nsx_tenant_id = ? AND tr.npp_tenant_id = ? AND tr.status = 'ACTIVE'
                      AND tr.effective_from <= CURRENT_DATE
                      AND (tr.effective_to IS NULL OR tr.effective_to >= CURRENT_DATE)
                )
                """, Boolean.class, nsxId, nppId);
        if (!Boolean.TRUE.equals(related)) {
            throw new ApiProblem("NPP_RELATION_REQUIRED",
                    "Nhà phân phối không có quan hệ hiệu lực với nhà sản xuất đã chọn.",
                    HttpStatus.FORBIDDEN);
        }
    }

    private OrderHeader requireOrder(
            Jwt jwt, BusinessAccessService.Actor actor, String permission, UUID orderId, boolean lock) {
        ScopedOrders scope = scopedOrders(jwt, actor, permission);
        String sql = """
                SELECT os.id, os.tenant_id, os.npp_tenant_id, os.order_code, os.status,
                    os.created_by, os.approved_by, os.version, os.total_vnd, os.subtotal_vnd,
                    os.discount_vnd, os.note, os.created_at, os.updated_at,
                    (SELECT COALESCE(SUM(ol.ordered_qty), 0) FROM order_line ol
                     WHERE ol.tenant_id = os.tenant_id AND ol.order_si_id = os.id
                       AND ol.deleted_at IS NULL) AS ordered_qty
                FROM order_si os JOIN tenant n ON n.id = os.npp_tenant_id
                WHERE os.id = ? AND os.deleted_at IS NULL AND
                """ + scope.sql() + (lock ? " FOR UPDATE OF os" : "");
        return jdbc.query(sql, (rs, row) -> orderHeader(rs), prepend(orderId, scope.arguments()).toArray())
                .stream().findFirst()
                .orElseThrow(() -> new ApiProblem("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng.",
                        HttpStatus.NOT_FOUND));
    }

    private ScopedOrders scopedOrders(
            Jwt jwt, BusinessAccessService.Actor actor, String permission) {
        if ("NPP".equals(actor.tenantType())) {
            return new ScopedOrders("os.npp_tenant_id = ?",
                    List.of(actor.tenantId()));
        }
        if ("NSX".equals(actor.tenantType())) {
            PartnerAccessService.Filter nppScope = partnerAccess.npps(jwt, actor, permission, "n");
            List<Object> args = new ArrayList<>();
            args.add(actor.tenantId());
            args.addAll(nppScope.arguments());
            return new ScopedOrders("os.tenant_id = ? AND " + nppScope.sql(), args);
        }
        return new ScopedOrders("FALSE", List.of());
    }

    private OrderDetail detail(OrderHeader order) {
        List<OrderLineView> lines = jdbc.query("""
                SELECT ol.id, ol.product_id, p.sku, p.name AS product_name, ol.unit_code,
                    ol.entered_unit_code, ol.entered_qty, ol.ordered_qty, ol.confirmed_qty,
                    ol.shipped_qty, ol.received_qty, ol.unit_price_vnd, ol.is_gift,
                    ol.gross_amount_vnd, ol.discount_vnd, ol.line_amount_vnd
                FROM order_line ol JOIN product p ON p.id = ol.product_id
                WHERE ol.tenant_id = ? AND ol.order_si_id = ? AND ol.deleted_at IS NULL
                ORDER BY p.sku, ol.id
                """, (rs, row) -> new OrderLineView(rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getString("sku"),
                rs.getString("product_name"), rs.getString("unit_code"),
                rs.getString("entered_unit_code"), rs.getLong("entered_qty"),
                rs.getLong("ordered_qty"), rs.getLong("confirmed_qty"),
                rs.getLong("shipped_qty"), rs.getLong("received_qty"),
                rs.getLong("unit_price_vnd"), rs.getBoolean("is_gift"),
                rs.getLong("gross_amount_vnd"), rs.getLong("discount_vnd"),
                rs.getLong("line_amount_vnd")), order.tenantId(), order.id());
        List<StatusEvent> history = jdbc.query("""
                SELECT from_status, to_status, actor_id, occurred_at, reason
                FROM order_status_history
                WHERE tenant_id = ? AND order_si_id = ? ORDER BY occurred_at, id
                """, (rs, row) -> new StatusEvent(rs.getString("from_status"), rs.getString("to_status"),
                rs.getObject("actor_id", UUID.class), rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("reason")), order.tenantId(), order.id());
        List<ReceiptLineView> receipts = jdbc.query("""
                SELECT id, order_line_id, received_qty, damaged_qty, note, created_by, created_at
                FROM order_si_receipt_line WHERE tenant_id = ? AND order_si_id = ?
                ORDER BY created_at, id
                """, (rs, row) -> new ReceiptLineView(rs.getObject("id", UUID.class),
                rs.getObject("order_line_id", UUID.class), rs.getLong("received_qty"),
                rs.getLong("damaged_qty"), rs.getString("note"),
                rs.getObject("created_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class)),
                order.tenantId(), order.id());
        return new OrderDetail(order.id(), order.tenantId(), order.nppTenantId(), order.orderCode(),
                order.status(), order.createdBy(), order.approvedBy(), order.version(), order.subtotalVnd(),
                order.discountVnd(), order.totalVnd(), order.note(), order.createdAt(), order.updatedAt(),
                lines, history, receipts);
    }

    private OrderHeader loadById(UUID orderId) {
        return jdbc.query("""
                SELECT id, tenant_id, npp_tenant_id, order_code, status, created_by, approved_by,
                    version, total_vnd, subtotal_vnd, discount_vnd, note, created_at, updated_at,
                    (SELECT COALESCE(SUM(ol.ordered_qty), 0) FROM order_line ol
                     WHERE ol.tenant_id = order_si.tenant_id AND ol.order_si_id = order_si.id
                       AND ol.deleted_at IS NULL) AS ordered_qty
                FROM order_si WHERE id = ? AND deleted_at IS NULL
                """, (rs, row) -> orderHeader(rs), orderId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng.",
                        HttpStatus.NOT_FOUND));
    }

    private OrderHeader orderHeader(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderHeader(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("npp_tenant_id", UUID.class), rs.getString("order_code"),
                rs.getString("status"), rs.getObject("created_by", UUID.class),
                rs.getObject("approved_by", UUID.class), rs.getLong("version"),
                rs.getLong("subtotal_vnd"), rs.getLong("discount_vnd"), rs.getLong("total_vnd"),
                rs.getString("note"), rs.getLong("ordered_qty"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private List<OrderLineState> orderLines(UUID orderId, UUID tenantId) {
        return jdbc.query("""
                SELECT id, ordered_qty, confirmed_qty, shipped_qty
                FROM order_line
                WHERE tenant_id = ? AND order_si_id = ? AND deleted_at IS NULL
                ORDER BY id
                """, (rs, row) -> new OrderLineState(rs.getObject("id", UUID.class),
                rs.getLong("ordered_qty"), rs.getLong("confirmed_qty"),
                rs.getLong("shipped_qty")), tenantId, orderId);
    }

    private Map<UUID, Long> validateQuantities(
            List<OrderLineState> lines, List<LineQuantity> requested, boolean againstOrdered) {
        Map<UUID, OrderLineState> byId = new HashMap<>();
        lines.forEach(line -> byId.put(line.id(), line));
        Map<UUID, Long> result = new HashMap<>();
        for (LineQuantity item : requested) {
            OrderLineState line = byId.get(item.orderLineId());
            if (line == null || result.containsKey(item.orderLineId())) {
                throw new ApiProblem("INVALID_ORDER_LINE",
                        "Một hoặc nhiều dòng hàng không thuộc đơn này.", HttpStatus.BAD_REQUEST);
            }
            long max = againstOrdered ? line.orderedQty() : line.confirmedQty();
            if (item.quantity() < 0 || item.quantity() > max) {
                throw new ApiProblem("INVALID_ORDER_QUANTITY", "Số lượng vượt số lượng được đặt.",
                        HttpStatus.BAD_REQUEST, Map.of("orderLineId", item.orderLineId().toString()));
            }
            result.put(item.orderLineId(), item.quantity());
        }
        if (result.size() != byId.size()) {
            throw new ApiProblem("ORDER_LINE_QUANTITIES_REQUIRED",
                    "Cần gửi số lượng cho tất cả dòng hàng trong đơn.", HttpStatus.BAD_REQUEST);
        }
        return result;
    }

    private Map<UUID, ReceiptLineInput> validateReceipt(
            List<OrderLineState> lines, List<ReceiptLineInput> requested) {
        Map<UUID, OrderLineState> byId = new HashMap<>();
        lines.forEach(line -> byId.put(line.id(), line));
        Map<UUID, ReceiptLineInput> result = new HashMap<>();
        for (ReceiptLineInput item : requested) {
            OrderLineState line = byId.get(item.orderLineId());
            if (line == null || item.receivedQty() < 0 || item.damagedQty() < 0
                    || result.putIfAbsent(item.orderLineId(), item) != null
                    || item.receivedQty() > line.shippedQty() || item.damagedQty() > item.receivedQty()) {
                throw new ApiProblem("INVALID_RECEIPT_QUANTITY",
                        "Số lượng thực nhận hoặc hỏng không hợp lệ.", HttpStatus.BAD_REQUEST);
            }
        }
        if (result.size() != byId.size()) {
            throw new ApiProblem("ORDER_LINE_RECEIPTS_REQUIRED",
                    "Cần gửi số thực nhận cho tất cả dòng hàng.", HttpStatus.BAD_REQUEST);
        }
        return result;
    }

    private OrderDetail findIdempotent(
            BusinessAccessService.Actor actor, UUID nppId, String key, String requestHash) {
        List<IdempotentOrder> found = jdbc.query("""
                SELECT id, request_hash FROM order_si
                WHERE npp_tenant_id = ? AND idempotency_key = ? AND deleted_at IS NULL
                """, (rs, row) -> new IdempotentOrder(
                rs.getObject("id", UUID.class), rs.getString("request_hash")), nppId, key);
        if (found.isEmpty()) {
            return null;
        }
        IdempotentOrder existing = found.get(0);
        if (existing.requestHash() == null || !existing.requestHash().equals(requestHash)) {
            throw new ApiProblem("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key đã được dùng cho nội dung đơn hàng khác.",
                    HttpStatus.CONFLICT, Map.of("orderId", existing.id().toString()));
        }
        UUID creator = jdbc.queryForObject("SELECT created_by FROM order_si WHERE id = ?",
                UUID.class, existing.id());
        if (!actor.userId().equals(creator)) {
            throw new AccessDeniedException("Idempotency-Key đã được dùng bởi tài khoản khác.");
        }
        return detail(loadById(existing.id()));
    }

    private ApiProblem conflict(String code, String message, OrderHeader order) {
        return new ApiProblem(code, message, HttpStatus.CONFLICT,
                Map.of("orderId", order.id().toString(), "status", order.status()));
    }

    private ApiProblem versionConflict(UUID orderId) {
        return new ApiProblem("ORDER_VERSION_CONFLICT", "Đơn hàng đã được cập nhật bởi người khác.",
                HttpStatus.CONFLICT, Map.of("orderId", orderId.toString()));
    }

    private ApiProblem idempotencyConflict() {
        return new ApiProblem("IDEMPOTENCY_CONFLICT",
                "Không thể hoàn tất yêu cầu trùng lặp; hãy thử lại cùng khóa.", HttpStatus.CONFLICT);
    }

    private void requireNpp(BusinessAccessService.Actor actor) {
        if (!"NPP".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chỉ nhà phân phối mới được tạo đơn sell-in.");
        }
    }

    private void requireNsx(BusinessAccessService.Actor actor) {
        if (!"NSX".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chức năng này chỉ dành cho tài khoản NSX.");
        }
    }

    private static long safeAdd(Long left, Long right) {
        return PricingMath.add(left == null ? 0 : left, right == null ? 0 : right);
    }

    private static long sum(List<Long> values) {
        long result = 0;
        for (long value : values) {
            result = PricingMath.add(result, value);
        }
        return result;
    }

    private static String requestHash(UUID nsxId, UUID nppId, CreateRequest request) {
        String lines = request.lines().stream()
                .sorted(Comparator.comparing((EffectivePricingService.CalculationLine line) ->
                                line.productId().toString())
                        .thenComparing(line -> line.unitCode().strip().toUpperCase()))
                .map(line -> line.productId() + ":" + line.unitCode().strip().toUpperCase()
                        + ":" + line.quantity())
                .reduce("", (left, right) -> left + "|" + right);
        String canonical = nsxId + "|" + nppId + "|" + lines + "|" + clean(request.note())
                + "|" + clean(request.reason()) + "|" + (request.submit() == null || request.submit());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Không thể tạo dấu vân tay yêu cầu.", exception);
        }
    }

    private static List<Object> prepend(Object first, List<Object> rest) {
        List<Object> result = new ArrayList<>();
        result.add(first);
        result.addAll(rest);
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private record ScopedOrders(String sql, List<Object> arguments) {
    }

    private record ProductUnit(String baseUnitCode, Long factor) {
    }

    private record PricedLine(
            UUID productId, String sku, String productName, String baseUnitCode,
            String enteredUnitCode, long enteredQty, long orderedQty, long unitPriceVnd,
            long grossVnd, long discountVnd, long amountVnd, boolean gift) {
    }

    private record CreditLimit(Long amountVnd, Long quantity) {
    }

    private record CreditPosition(
            Long limitAmountVnd, Long limitQty, long outstandingAmountVnd, long outstandingQty) {
    }

    private record IdempotentOrder(UUID id, String requestHash) {
    }

    private record OrderHeader(
            UUID id, UUID tenantId, UUID nppTenantId, String orderCode, String status,
            UUID createdBy, UUID approvedBy, long version, long subtotalVnd, long discountVnd,
            long totalVnd, String note, long orderedQty,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private record OrderLineState(UUID id, long orderedQty, long confirmedQty, long shippedQty) {
    }

    public record OrderSummary(
            UUID id, String orderCode, String status, UUID nppTenantId, String nppName,
            long totalVnd, OffsetDateTime createdAt, OffsetDateTime updatedAt, long version) {
    }

    public record OrderDetail(
            UUID id, UUID tenantId, UUID nppTenantId, String orderCode, String status,
            UUID createdBy, UUID approvedBy, long version, long subtotalVnd, long discountVnd,
            long totalVnd, String note, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            List<OrderLineView> lines, List<StatusEvent> history, List<ReceiptLineView> receiptLines) {
    }

    public record OrderLineView(
            UUID id, UUID productId, String sku, String productName, String unitCode,
            String enteredUnitCode, long enteredQty, long orderedQty, long confirmedQty,
            long shippedQty, long receivedQty, long unitPriceVnd, boolean gift,
            long grossAmountVnd, long discountVnd, long lineAmountVnd) {
    }

    public record StatusEvent(
            String fromStatus, String toStatus, UUID actorId, OffsetDateTime occurredAt, String reason) {
    }

    public record ReceiptLineView(
            UUID id, UUID orderLineId, long receivedQty, long damagedQty,
            String note, UUID createdBy, OffsetDateTime createdAt) {
    }

    public record CreateRequest(
            @jakarta.validation.constraints.NotNull UUID nsxTenantId,
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.Valid
            List<EffectivePricingService.CalculationLine> lines,
            @jakarta.validation.constraints.Size(max = 2000) String note,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            Boolean submit) {
    }

    public record RepeatRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record DraftUpdateRequest(
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.Valid
            List<EffectivePricingService.CalculationLine> lines,
            @jakarta.validation.constraints.Size(max = 2000) String note,
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record SubmitRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record DecisionRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.PositiveOrZero long version) {
    }

    public record ReviewRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.PositiveOrZero long version) {
    }

    public record RejectRequest(
            boolean needInfo,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.PositiveOrZero long version) {
    }

    public record CancelRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.PositiveOrZero long version) {
    }

    public record LineQuantity(
            @jakarta.validation.constraints.NotNull UUID orderLineId,
            @jakarta.validation.constraints.PositiveOrZero long quantity) {
    }

    public record ShipmentRequest(
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.Valid List<LineQuantity> lines,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.PositiveOrZero long version) {
    }

    public record ReceiptLineInput(
            @jakarta.validation.constraints.NotNull UUID orderLineId,
            @jakarta.validation.constraints.PositiveOrZero long receivedQty,
            @jakarta.validation.constraints.PositiveOrZero long damagedQty,
            @jakarta.validation.constraints.Size(max = 500) String note) {
    }

    public record ReceiptRequest(
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.Valid List<ReceiptLineInput> lines,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.PositiveOrZero long version) {
    }
}
