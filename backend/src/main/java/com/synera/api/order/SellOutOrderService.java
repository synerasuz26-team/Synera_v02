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
import org.springframework.beans.factory.annotation.Value;
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
public class SellOutOrderService {
    private final JdbcTemplate jdbc;
    private final BusinessAccessService access;
    private final PartnerAccessService partnerAccess;
    private final EffectivePricingService pricing;
    private final OrderFlowService orderFlow;
    private final AuditService audit;
    private final int confirmationWindowHours;

    public SellOutOrderService(
            JdbcTemplate jdbc,
            BusinessAccessService access,
            PartnerAccessService partnerAccess,
            EffectivePricingService pricing,
            OrderFlowService orderFlow,
            AuditService audit,
            @Value("${synera.orders.confirmation-window-hours:4}") int confirmationWindowHours) {
        this.jdbc = jdbc;
        this.access = access;
        this.partnerAccess = partnerAccess;
        this.pricing = pricing;
        this.orderFlow = orderFlow;
        this.audit = audit;
        this.confirmationWindowHours = confirmationWindowHours;
    }

    public ApiPage<OrderSummary> list(
            Jwt jwt, String status, UUID outletId, UUID nppId, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ScopedOrders scope = scopedOrders(jwt, actor, "order_so:view");
        StringBuilder where = new StringBuilder("os.deleted_at IS NULL AND ").append(scope.sql());
        List<Object> arguments = new ArrayList<>(scope.arguments());
        if (status != null && !status.isBlank()) {
            where.append(" AND os.status = ?");
            arguments.add(status.strip().toUpperCase());
        }
        if (outletId != null) {
            where.append(" AND os.outlet_id = ?");
            arguments.add(outletId);
        }
        if (nppId != null) {
            where.append(" AND os.npp_tenant_id = ?");
            arguments.add(nppId);
        }
        long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_so os
                JOIN outlet o ON o.id = os.outlet_id
                WHERE
                """ + where, Long.class, arguments.toArray());
        arguments.add(size);
        arguments.add((long) page * size);
        List<OrderSummary> items = jdbc.query("""
                SELECT os.id, os.order_code, os.status, os.npp_tenant_id, os.outlet_id,
                    o.code AS outlet_code, o.name AS outlet_name, os.total_vnd,
                    os.created_at, os.updated_at, os.version
                FROM order_so os
                JOIN outlet o ON o.id = os.outlet_id
                WHERE
                """ + where + " ORDER BY os.created_at DESC, os.id LIMIT ? OFFSET ?",
                (rs, row) -> new OrderSummary(rs.getObject("id", UUID.class), rs.getString("order_code"),
                        rs.getString("status"), rs.getObject("npp_tenant_id", UUID.class),
                        rs.getObject("outlet_id", UUID.class), rs.getString("outlet_code"),
                        rs.getString("outlet_name"), rs.getLong("total_vnd"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("version")),
                arguments.toArray());
        return ApiPage.of(items, page, size, total);
    }

    public OrderDetail get(Jwt jwt, UUID orderId) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        OrderHeader order = requireOrder(jwt, actor, "order_so:view", orderId, false);
        return detail(order, true);
    }

    @Transactional
    public OrderDetail create(
            Jwt jwt, String idempotencyKey, OrderCreateRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        UUID nppId = resolveNpp(actor, request.nppTenantId());
        if ("NSX".equals(actor.tenantType())) {
            partnerAccess.requireNpp(jwt, actor, "order_so:create", nppId);
        }
        partnerAccess.requireOutlet(jwt, actor, "order_so:create", request.outletId());
        requireOutletNpp(nppId, request.outletId());
        requireOrderCreateScope(jwt, actor, request.outletId());
        String requestHash = requestHash(nppId, request);
        OrderDetail existing = findIdempotent(actor, nppId, idempotencyKey, requestHash);
        if (existing != null) {
            return existing;
        }

        EffectivePricingService.CalculationResult calculation = calculate(
                jwt, nppId, request.outletId(), request.lines(), request.voucherCode());
        UUID orderId = UUID.randomUUID();
        String code = "SO-" + orderId.toString().replace("-", "").substring(0, 16).toUpperCase();
        int inserted = jdbc.update("""
                INSERT INTO order_so (
                    id, tenant_id, npp_tenant_id, outlet_id, order_code, status,
                    idempotency_key, request_hash, created_by, note, subtotal_vnd,
                    discount_vnd, total_vnd, voucher_code
                ) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """, orderId, nppId, nppId, request.outletId(), code, idempotencyKey, requestHash,
                actor.userId(), clean(request.note()), grossSubtotal(calculation),
                totalDiscount(calculation), calculation.totalVnd(), cleanNullable(request.voucherCode()));
        if (inserted == 0) {
            OrderDetail duplicate = findIdempotent(actor, nppId, idempotencyKey, requestHash);
            if (duplicate == null) {
                throw new ApiProblem("IDEMPOTENCY_CONFLICT",
                        "Không thể hoàn tất yêu cầu trùng lặp; hãy thử lại cùng khóa.",
                        HttpStatus.CONFLICT);
            }
            return duplicate;
        }
        replaceLines(nppId, orderId, calculation.lines());
        jdbc.update("""
                INSERT INTO order_status_history (
                    id, tenant_id, order_so_id, from_status, to_status, actor_id, reason
                ) VALUES (?, ?, ?, NULL, 'DRAFT', ?, ?)
                """, UUID.randomUUID(), nppId, orderId, actor.userId(), "Tạo đơn bán ra.");
        long version = 0;
        if (request.submit() == null || request.submit()) {
            version = orderFlow.transition("SELL_OUT", nppId, orderId, "DRAFT", "SUBMITTED",
                    "order_so:create", actor.userId(), "Gửi đơn bán ra.", version);
            jdbc.update("""
                    UPDATE order_so SET submitted_at = CURRENT_TIMESTAMP, confirmation_due_at = ?
                    WHERE id = ? AND tenant_id = ? AND version = ?
                    """, OffsetDateTime.now(ZoneOffset.UTC).plusHours(confirmationWindowHours),
                    orderId, nppId, version);
        }
        OrderDetail created = loadById(orderId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_so:create", "ORDER_SO_CREATED",
                "order_so", orderId, null, created, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return created;
    }

    @Transactional
    public OrderDetail updateDraft(
            Jwt jwt, UUID orderId, DraftUpdateRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        OrderHeader before = requireOrder(jwt, actor, "order_so:update_draft", orderId, true);
        requireDraftAuthority(jwt, actor, before, "order_so:update_draft");
        if (!"DRAFT".equals(before.status())) {
            throw orderConflict("ORDER_NOT_EDITABLE", "Chỉ được sửa đơn ở trạng thái nháp.", before);
        }
        EffectivePricingService.CalculationResult calculation = calculate(
                jwt, before.nppTenantId(), before.outletId(), request.lines(), request.voucherCode());
        int changed = jdbc.update("""
                UPDATE order_so SET note = ?, voucher_code = ?, subtotal_vnd = ?, discount_vnd = ?,
                    total_vnd = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND tenant_id = ? AND status = 'DRAFT' AND version = ?
                """, clean(request.note()), cleanNullable(request.voucherCode()),
                grossSubtotal(calculation), totalDiscount(calculation), calculation.totalVnd(),
                orderId, before.tenantId(), request.version());
        if (changed == 0) {
            throw versionConflict(orderId);
        }
        jdbc.update("""
                UPDATE order_line SET deleted_at = CURRENT_TIMESTAMP
                WHERE order_so_id = ? AND deleted_at IS NULL
                """, orderId);
        replaceLines(before.tenantId(), orderId, calculation.lines());
        OrderDetail updated = loadById(orderId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_so:update_draft",
                "ORDER_SO_DRAFT_UPDATED", "order_so", orderId, before, updated, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return updated;
    }

    @Transactional
    public OrderDetail submitDraft(
            Jwt jwt, UUID orderId, SubmitOrderRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        OrderHeader order = requireOrder(jwt, actor, "order_so:create", orderId, true);
        requireDraftAuthority(jwt, actor, order, "order_so:create");
        if (!"DRAFT".equals(order.status())) {
            throw orderConflict("ORDER_NOT_DRAFT", "Chỉ được gửi đơn đang ở trạng thái nháp.", order);
        }
        List<EffectivePricingService.CalculationLine> enteredLines = jdbc.query("""
                SELECT product_id, entered_unit_code, entered_qty
                FROM order_line
                WHERE order_so_id = ? AND deleted_at IS NULL AND is_gift = FALSE
                ORDER BY product_id
                """, (rs, row) -> new EffectivePricingService.CalculationLine(
                rs.getObject("product_id", UUID.class), rs.getString("entered_unit_code"),
                rs.getLong("entered_qty")), orderId);
        if (enteredLines.isEmpty()) {
            throw new ApiProblem("ORDER_LINES_REQUIRED", "Đơn hàng cần có ít nhất một mặt hàng.",
                    HttpStatus.BAD_REQUEST);
        }
        EffectivePricingService.CalculationResult calculation = calculate(
                jwt, order.nppTenantId(), order.outletId(), enteredLines, order.voucherCode());
        persistRecalculation(order, calculation);
        long version = orderFlow.transition("SELL_OUT", order.tenantId(), orderId, "DRAFT", "SUBMITTED",
                "order_so:create", actor.userId(), request.reason(), order.version() + 1);
        jdbc.update("""
                UPDATE order_so SET submitted_at = CURRENT_TIMESTAMP, confirmation_due_at = ?
                WHERE id = ? AND tenant_id = ? AND version = ?
                """, OffsetDateTime.now(ZoneOffset.UTC).plusHours(confirmationWindowHours),
                orderId, order.tenantId(), version);
        OrderDetail submitted = loadById(orderId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_so:create",
                "ORDER_SO_SUBMITTED", "order_so", orderId, order, submitted, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return submitted;
    }

    @Transactional
    public OrderDetail cancelBeforeConfirmation(
            Jwt jwt, UUID orderId, CancelOrderRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        OrderHeader order = requireOrder(jwt, actor, "order_so:update_draft", orderId, true);
        requireCreator(actor, order);
        if (!Set.of("DRAFT", "SUBMITTED").contains(order.status())) {
            throw orderConflict("ORDER_ALREADY_CONFIRMED",
                    "Đơn đã được NPP xác nhận; không thể hủy theo luồng này.", order);
        }
        orderFlow.transition("SELL_OUT", order.tenantId(), order.id(), order.status(), "CANCELLED",
                "order_so:update_draft", actor.userId(), request.reason(), request.version());
        OrderDetail cancelled = loadById(orderId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_so:update_draft",
                "ORDER_SO_CANCELLED", "order_so", orderId, order, cancelled, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return cancelled;
    }

    @Transactional
    public OrderDetail cancelConfirmed(
            Jwt jwt, UUID orderId, CancelOrderRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        if (!"NPP".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chỉ NPP mới được hủy đơn đã xác nhận.");
        }
        OrderHeader order = requireOrder(jwt, actor, "order_so:cancel_confirmed", orderId, true);
        if (!Set.of("CONFIRMED", "PARTIALLY_CONFIRMED").contains(order.status())) {
            throw orderConflict("ORDER_NOT_CANCELLABLE", "Trạng thái hiện tại không cho phép hủy đơn.", order);
        }
        orderFlow.transition("SELL_OUT", order.tenantId(), order.id(), order.status(), "CANCELLED",
                "order_so:cancel_confirmed", actor.userId(), request.reason(), request.version());
        OrderDetail cancelled = loadById(orderId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_so:cancel_confirmed",
                "ORDER_SO_CONFIRMED_CANCELLED", "order_so", orderId, order, cancelled, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return cancelled;
    }

    @Transactional
    public OrderDetail confirmFulfillment(
            Jwt jwt, UUID orderId, FulfillmentRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        requireNppActor(actor);
        OrderHeader order = requireOrder(jwt, actor, "order_so:fulfill_confirm", orderId, true);
        if (!"SUBMITTED".equals(order.status())) {
            throw orderConflict("ORDER_NOT_SUBMITTED", "Chỉ đơn đang chờ NPP mới được xác nhận.", order);
        }
        List<OrderLineState> lines = orderLines(order.id());
        Map<UUID, Long> confirmed = validateQuantities(lines, request.lines(), true);
        boolean anyAccepted = confirmed.values().stream().anyMatch(quantity -> quantity > 0);
        boolean allAccepted = lines.stream().allMatch(line -> confirmed.get(line.id()) == line.orderedQty());
        String targetStatus = !anyAccepted ? "REJECTED_BY_NPP" : allAccepted ? "CONFIRMED" : "PARTIALLY_CONFIRMED";
        if (!allAccepted && (request.reason() == null || request.reason().isBlank())) {
            throw new ApiProblem("FULFILLMENT_REASON_REQUIRED",
                    "Cần ghi lý do khi xác nhận một phần hoặc từ chối đơn.", HttpStatus.BAD_REQUEST);
        }
        if ("PARTIALLY_CONFIRMED".equals(targetStatus) && request.expectedDeliveryAt() == null) {
            throw new ApiProblem("EXPECTED_DELIVERY_REQUIRED",
                    "Cần nhập ngày giao dự kiến cho phần hàng còn thiếu.", HttpStatus.BAD_REQUEST);
        }
        if (request.expectedDeliveryAt() != null
                && request.expectedDeliveryAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new ApiProblem("EXPECTED_DELIVERY_IN_PAST",
                    "Ngày giao dự kiến không được ở trong quá khứ.", HttpStatus.BAD_REQUEST);
        }
        for (OrderLineState line : lines) {
            jdbc.update("""
                    UPDATE order_line SET confirmed_qty = ?
                    WHERE id = ? AND tenant_id = ? AND order_so_id = ? AND deleted_at IS NULL
                    """, confirmed.get(line.id()), line.id(), order.tenantId(), order.id());
        }
        jdbc.update("""
                UPDATE order_so SET expected_delivery_at = ?
                WHERE id = ? AND tenant_id = ? AND version = ?
                """, request.expectedDeliveryAt(), order.id(), order.tenantId(), order.version());
        orderFlow.transition("SELL_OUT", order.tenantId(), order.id(),
                order.status(), targetStatus, "order_so:fulfill_confirm", actor.userId(),
                request.reason(), request.version());
        OrderDetail updated = loadById(orderId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_so:fulfill_confirm",
                "ORDER_SO_FULFILLMENT_CONFIRMED", "order_so", orderId, order, updated,
                request.reason(), servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return updated;
    }

    @Transactional
    public OrderDetail ship(
            Jwt jwt, UUID orderId, ShipmentRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        requireNppActor(actor);
        OrderHeader order = requireOrder(jwt, actor, "order_so:ship_update", orderId, true);
        if (!Set.of("CONFIRMED", "PARTIALLY_CONFIRMED").contains(order.status())) {
            throw orderConflict("ORDER_NOT_READY_TO_SHIP", "Đơn chưa được xác nhận để xuất kho.", order);
        }
        List<OrderLineState> lines = orderLines(order.id());
        Map<UUID, Long> shipped = validateQuantities(lines, request.lines(), false);
        if (shipped.values().stream().noneMatch(quantity -> quantity > 0)) {
            throw new ApiProblem("SHIPMENT_QUANTITY_REQUIRED",
                    "Số lượng xuất kho phải lớn hơn 0.", HttpStatus.BAD_REQUEST);
        }
        for (OrderLineState line : lines) {
            jdbc.update("""
                    UPDATE order_line SET shipped_qty = ?
                    WHERE id = ? AND tenant_id = ? AND order_so_id = ? AND deleted_at IS NULL
                    """, shipped.get(line.id()), line.id(), order.tenantId(), order.id());
        }
        orderFlow.transition("SELL_OUT", order.tenantId(), order.id(), order.status(), "SHIPPED",
                "order_so:ship_update", actor.userId(), request.reason(), request.version());
        OrderDetail updated = loadById(orderId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_so:ship_update",
                "ORDER_SO_SHIPPED", "order_so", orderId, order, updated, request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return updated;
    }

    @Transactional
    public OrderDetail receive(
            Jwt jwt, UUID orderId, ReceiptRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        OrderHeader order = requireOrder(jwt, actor, "order_so:receive_confirm", orderId, true);
        if (!"SHIPPED".equals(order.status())) {
            throw orderConflict("ORDER_NOT_SHIPPED", "Chỉ đơn đã xuất kho mới được xác nhận thực nhận.", order);
        }
        List<OrderLineState> lines = orderLines(order.id());
        Map<UUID, ReceiptLineInput> received = validateReceipt(lines, request.lines());
        validateAttachments(actor, request.attachmentIds());
        boolean full = lines.stream().allMatch(line -> {
            ReceiptLineInput receipt = received.get(line.id());
            return receipt.receivedQty() == line.shippedQty() && receipt.damagedQty() == 0;
        });
        for (OrderLineState line : lines) {
            ReceiptLineInput receipt = received.get(line.id());
            jdbc.update("""
                    UPDATE order_line SET received_qty = ?
                    WHERE id = ? AND tenant_id = ? AND order_so_id = ? AND deleted_at IS NULL
                    """, receipt.receivedQty(), line.id(), order.tenantId(), order.id());
            jdbc.update("""
                    INSERT INTO order_receipt_line (
                        id, tenant_id, order_so_id, order_line_id, received_qty,
                        damaged_qty, note, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), order.tenantId(), order.id(), line.id(),
                    receipt.receivedQty(), receipt.damagedQty(), clean(receipt.note()), actor.userId());
        }
        for (UUID attachmentId : request.attachmentIds()) {
            jdbc.update("""
                    INSERT INTO order_receipt_attachment (tenant_id, order_so_id, attachment_id)
                    VALUES (?, ?, ?)
                    """, order.tenantId(), order.id(), attachmentId);
        }
        String target = full ? "RECEIVED_FULL" : "RECEIVED_ISSUE";
        long version = orderFlow.transition("SELL_OUT", order.tenantId(), order.id(),
                "SHIPPED", target, "order_so:receive_confirm", actor.userId(),
                request.reason(), request.version());
        if (full) {
            orderFlow.transition("SELL_OUT", order.tenantId(), order.id(), "RECEIVED_FULL", "CLOSED",
                    "SYSTEM", null, "Tự động đóng sau khi nhận đủ hàng.", version);
        } else {
            createReceiptIssueWork(order, actor, received, request.reason());
        }
        OrderDetail updated = loadById(orderId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "order_so:receive_confirm",
                full ? "ORDER_SO_RECEIVED_FULL" : "ORDER_SO_RECEIVED_WITH_ISSUE",
                "order_so", orderId, order, updated, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return updated;
    }

    @Transactional
    public ReturnSummary createReturn(
            Jwt jwt, UUID orderId, ReturnCreateRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        OrderHeader order = requireOrder(jwt, actor, "return:create", orderId, true);
        if (!"RECEIVED_ISSUE".equals(order.status())) {
            throw orderConflict("RETURN_NOT_ALLOWED", "Đổi trả chỉ được yêu cầu khi đơn có thiếu hoặc hỏng.", order);
        }
        Boolean pendingReturnExists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM return_request
                    WHERE order_so_id = ? AND status = 'SUBMITTED' AND deleted_at IS NULL
                )
                """, Boolean.class, order.id());
        if (Boolean.TRUE.equals(pendingReturnExists)) {
            throw new ApiProblem("RETURN_ALREADY_PENDING",
                    "Đơn đã có yêu cầu đổi trả đang chờ xử lý.", HttpStatus.CONFLICT);
        }
        List<OrderLineState> lines = orderLines(order.id());
        Map<UUID, Long> quantities = validateReturnLines(lines, request.lines());
        Map<UUID, Long> receivedByLine = new HashMap<>();
        lines.forEach(line -> receivedByLine.put(line.id(), line.receivedQty()));
        for (Map.Entry<UUID, Long> entry : quantities.entrySet()) {
            Long alreadyRequested = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(rrl.quantity), 0)
                    FROM return_request_line rrl
                    JOIN return_request rr ON rr.id = rrl.return_request_id
                    WHERE rr.order_so_id = ? AND rrl.order_line_id = ?
                      AND rr.deleted_at IS NULL AND rr.status IN ('SUBMITTED', 'APPROVED')
                    """, Long.class, order.id(), entry.getKey());
            if (alreadyRequested == null || entry.getValue() + alreadyRequested
                    > receivedByLine.get(entry.getKey())) {
                throw new ApiProblem("RETURN_QUANTITY_EXCEEDED",
                        "Tổng số lượng đổi trả vượt số hàng đã nhận.",
                        HttpStatus.CONFLICT, Map.of("orderLineId", entry.getKey().toString()));
            }
        }
        UUID returnId = UUID.randomUUID();
        String requestCode = "RT-" + returnId.toString().replace("-", "").substring(0, 16).toUpperCase();
        jdbc.update("""
                INSERT INTO return_request (
                    id, tenant_id, order_so_id, request_code, status, reason,
                    requested_by, request_type
                ) VALUES (?, ?, ?, ?, 'SUBMITTED', ?, ?, ?)
                """, returnId, order.tenantId(), order.id(), requestCode,
                request.reason().strip(), actor.userId(), request.requestType().strip().toUpperCase());
        quantities.forEach((lineId, quantity) -> jdbc.update("""
                INSERT INTO return_request_line (id, tenant_id, return_request_id, order_line_id, quantity)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), order.tenantId(), returnId, lineId, quantity));
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "return:create",
                "RETURN_REQUEST_CREATED", "return_request", returnId, null, request,
                request.reason(), servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return findReturn(returnId);
    }

    @Transactional
    public ReturnSummary decideReturn(
            Jwt jwt, UUID returnId, ReturnDecisionRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        if (!"NPP".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chỉ NPP mới được duyệt yêu cầu đổi trả.");
        }
        ReturnHeader row = jdbc.query("""
                SELECT rr.id, rr.tenant_id, rr.order_so_id, rr.requested_by, rr.status, rr.version,
                    os.status AS order_status, os.version AS order_version, os.npp_tenant_id, os.outlet_id
                FROM return_request rr JOIN order_so os ON os.id = rr.order_so_id
                WHERE rr.id = ? AND rr.tenant_id = ? AND rr.deleted_at IS NULL
                FOR UPDATE OF rr
                """, (rs, index) -> new ReturnHeader(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("order_so_id", UUID.class),
                rs.getObject("requested_by", UUID.class), rs.getString("status"), rs.getLong("version"),
                rs.getString("order_status"), rs.getLong("order_version"),
                rs.getObject("npp_tenant_id", UUID.class), rs.getObject("outlet_id", UUID.class)),
                returnId, actor.tenantId()).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("RETURN_NOT_FOUND", "Không tìm thấy yêu cầu đổi trả.",
                        HttpStatus.NOT_FOUND));
        partnerAccess.requireOutlet(jwt, actor, "return:approve", row.outletId());
        if (!"SUBMITTED".equals(row.status())) {
            throw new ApiProblem("RETURN_ALREADY_DECIDED", "Yêu cầu đổi trả đã được xử lý.",
                    HttpStatus.CONFLICT);
        }
        if (actor.userId().equals(row.requestedBy())) {
            throw new ApiProblem("APPROVER_MUST_DIFFER",
                    "Người duyệt không được là người tạo yêu cầu.", HttpStatus.FORBIDDEN);
        }
        if (!"RECEIVED_ISSUE".equals(row.orderStatus())) {
            throw new ApiProblem("ORDER_NOT_IN_RETURN_FLOW",
                    "Đơn không còn ở trạng thái xử lý đổi trả.", HttpStatus.CONFLICT);
        }
        if (request.version() != row.version()) {
            throw new ApiProblem("RETURN_VERSION_CONFLICT", "Yêu cầu đổi trả đã được cập nhật.",
                    HttpStatus.CONFLICT, Map.of("returnRequestId", returnId.toString()));
        }
        String status = request.approved() ? "APPROVED" : "REJECTED";
        int changed = jdbc.update("""
                UPDATE return_request SET status = ?, approved_by = ?, resolution = ?,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND tenant_id = ? AND version = ? AND status = 'SUBMITTED'
                """, status, actor.userId(), request.resolution().strip(),
                returnId, actor.tenantId(), request.version());
        if (changed == 0) {
            throw new ApiProblem("RETURN_VERSION_CONFLICT", "Yêu cầu đổi trả đã được cập nhật.",
                    HttpStatus.CONFLICT, Map.of("returnRequestId", returnId.toString()));
        }
        if (request.approved()) {
            OrderHeader order = requireOrder(jwt, actor, "return:approve", row.orderSoId(), true);
            orderFlow.transition("SELL_OUT", order.tenantId(), order.id(), "RECEIVED_ISSUE", "CLOSED",
                    "return:approve", actor.userId(), request.resolution(), request.orderVersion());
        }
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "return:approve",
                "RETURN_REQUEST_" + status, "return_request", returnId, row, request,
                request.resolution(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return findReturn(returnId);
    }

    private List<OrderLineState> orderLines(UUID orderId) {
        return jdbc.query("""
                SELECT id, ordered_qty, confirmed_qty, shipped_qty, received_qty
                FROM order_line
                WHERE order_so_id = ? AND deleted_at IS NULL
                ORDER BY id
                """, (rs, row) -> new OrderLineState(rs.getObject("id", UUID.class),
                rs.getLong("ordered_qty"), rs.getLong("confirmed_qty"),
                rs.getLong("shipped_qty"), rs.getLong("received_qty")), orderId);
    }

    private Map<UUID, Long> validateQuantities(
            List<OrderLineState> orderLines, List<LineQuantity> requested, boolean againstOrdered) {
        Map<UUID, OrderLineState> byId = new HashMap<>();
        orderLines.forEach(line -> byId.put(line.id(), line));
        Map<UUID, Long> quantities = new HashMap<>();
        for (LineQuantity item : requested) {
            OrderLineState line = byId.get(item.orderLineId());
            if (line == null || quantities.containsKey(item.orderLineId())) {
                throw new ApiProblem("INVALID_ORDER_LINE",
                        "Một hoặc nhiều dòng hàng không thuộc đơn này.", HttpStatus.BAD_REQUEST);
            }
            long maximum = againstOrdered ? line.orderedQty() : line.confirmedQty();
            if (item.quantity() < 0 || item.quantity() > maximum) {
                throw new ApiProblem("INVALID_ORDER_QUANTITY",
                        "Số lượng vượt quá số lượng được đặt hoặc xác nhận.",
                        HttpStatus.BAD_REQUEST, Map.of("orderLineId", item.orderLineId().toString()));
            }
            quantities.put(item.orderLineId(), item.quantity());
        }
        if (quantities.size() != byId.size()) {
            throw new ApiProblem("ORDER_LINE_QUANTITIES_REQUIRED",
                    "Cần gửi số lượng cho tất cả dòng hàng trong đơn.", HttpStatus.BAD_REQUEST);
        }
        return quantities;
    }

    private Map<UUID, ReceiptLineInput> validateReceipt(
            List<OrderLineState> orderLines, List<ReceiptLineInput> requested) {
        Map<UUID, OrderLineState> byId = new HashMap<>();
        orderLines.forEach(line -> byId.put(line.id(), line));
        Map<UUID, ReceiptLineInput> receipts = new HashMap<>();
        for (ReceiptLineInput item : requested) {
            OrderLineState line = byId.get(item.orderLineId());
            if (line == null || receipts.putIfAbsent(item.orderLineId(), item) != null) {
                throw new ApiProblem("INVALID_ORDER_LINE",
                        "Một hoặc nhiều dòng hàng không thuộc đơn này.", HttpStatus.BAD_REQUEST);
            }
            if (item.receivedQty() < 0 || item.damagedQty() < 0
                    || item.receivedQty() > line.shippedQty() || item.damagedQty() > item.receivedQty()) {
                throw new ApiProblem("INVALID_RECEIPT_QUANTITY",
                        "Số thực nhận hoặc số hỏng không hợp lệ.",
                        HttpStatus.BAD_REQUEST, Map.of("orderLineId", item.orderLineId().toString()));
            }
        }
        if (receipts.size() != byId.size()) {
            throw new ApiProblem("ORDER_LINE_RECEIPTS_REQUIRED",
                    "Cần gửi số thực nhận cho tất cả dòng hàng trong đơn.", HttpStatus.BAD_REQUEST);
        }
        return receipts;
    }

    private Map<UUID, Long> validateReturnLines(
            List<OrderLineState> orderLines, List<ReturnLineInput> requested) {
        Map<UUID, OrderLineState> byId = new HashMap<>();
        orderLines.forEach(line -> byId.put(line.id(), line));
        Map<UUID, Long> quantities = new HashMap<>();
        for (ReturnLineInput item : requested) {
            OrderLineState line = byId.get(item.orderLineId());
            if (line == null || quantities.containsKey(item.orderLineId())
                    || item.quantity() <= 0 || item.quantity() > line.receivedQty()) {
                throw new ApiProblem("INVALID_RETURN_QUANTITY",
                        "Số lượng đổi trả phải thuộc đơn và không vượt số lượng đã nhận.",
                        HttpStatus.BAD_REQUEST, Map.of("orderLineId", item.orderLineId().toString()));
            }
            quantities.put(item.orderLineId(), item.quantity());
        }
        if (quantities.isEmpty()) {
            throw new ApiProblem("RETURN_LINES_REQUIRED",
                    "Yêu cầu đổi trả cần có ít nhất một dòng hàng.", HttpStatus.BAD_REQUEST);
        }
        return quantities;
    }

    private void validateAttachments(BusinessAccessService.Actor actor, List<UUID> attachmentIds) {
        if (attachmentIds.size() > 5 || new HashSet<>(attachmentIds).size() != attachmentIds.size()) {
            throw new ApiProblem("INVALID_RECEIPT_ATTACHMENTS",
                    "Có thể đính kèm tối đa 5 ảnh, không lặp tệp.", HttpStatus.BAD_REQUEST);
        }
        if (attachmentIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", attachmentIds.stream().map(ignored -> "?").toList());
        List<Object> arguments = new ArrayList<>();
        arguments.add(actor.tenantId());
        arguments.add(actor.userId());
        arguments.addAll(attachmentIds);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM attachment
                WHERE tenant_id = ? AND uploaded_by = ? AND id IN (
                """ + placeholders + ")", Integer.class, arguments.toArray());
        if (count == null || count != attachmentIds.size()) {
            throw new ApiProblem("RECEIPT_ATTACHMENT_NOT_FOUND",
                    "Một hoặc nhiều ảnh không tồn tại hoặc không thuộc tài khoản hiện tại.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private void createReceiptIssueWork(
            OrderHeader order, BusinessAccessService.Actor actor,
            Map<UUID, ReceiptLineInput> receipts, String reason) {
        String details = receipts.values().stream()
                .filter(line -> line.damagedQty() > 0)
                .map(line -> "Dòng " + line.orderLineId() + ": hỏng " + line.damagedQty())
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "; " + right);
        String description = clean(reason);
        if (!details.isBlank()) {
            description = description.isBlank() ? details : description + "\n" + details;
        }
        UUID ticketId = UUID.randomUUID();
        String suffix = ticketId.toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbc.update("""
                INSERT INTO ticket (
                    id, tenant_id, ticket_code, title, description, status, priority,
                    outlet_id, order_so_id, created_by
                ) VALUES (?, ?, ?, ?, ?, 'NEW', 'HIGH', ?, ?, ?)
                """, ticketId, order.tenantId(), "SO-ISSUE-" + suffix,
                "Thiếu hoặc hỏng hàng - " + order.orderCode(), description,
                order.outletId(), order.id(), actor.userId());
        jdbc.update("""
                INSERT INTO task (
                    id, tenant_id, title, description, status, outlet_id, created_by, due_at
                ) VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?)
                """, UUID.randomUUID(), order.tenantId(), "Theo dõi xử lý đơn " + order.orderCode(),
                description, order.outletId(), actor.userId(),
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(24));
        notifyNpp(order.tenantId(), "ORDER_RECEIVED_ISSUE",
                Map.of("orderId", order.id().toString(), "ticketId", ticketId.toString()));
    }

    private void notifyNpp(UUID tenantId, String type, Map<String, String> payload) {
        List<UUID> recipients = jdbc.query("""
                SELECT DISTINCT u.id
                FROM user_role ur JOIN app_users u ON u.id = ur.user_id
                WHERE ur.tenant_id = ? AND u.status = 'ACTIVE'
                  AND ur.role_code IN ('NPP_OWNER', 'NPP_STAFF')
                """, (rs, row) -> rs.getObject(1, UUID.class), tenantId);
        for (UUID recipient : recipients) {
            jdbc.update("""
                    INSERT INTO notification (id, tenant_id, recipient_id, notification_type, payload)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    """, UUID.randomUUID(), tenantId, recipient, type, toJson(payload));
        }
    }

    private String toJson(Map<String, String> payload) {
        return payload.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
                .reduce((left, right) -> left + "," + right)
                .map(json -> "{" + json + "}")
                .orElse("{}");
    }

    private ReturnSummary findReturn(UUID returnId) {
        ReturnSummary summary = jdbc.query("""
                SELECT rr.id, rr.order_so_id, rr.request_code, rr.status, rr.request_type,
                    rr.reason, rr.resolution, rr.requested_by, rr.approved_by, rr.version,
                    rr.created_at, rr.updated_at
                FROM return_request rr WHERE rr.id = ? AND rr.deleted_at IS NULL
                """, (rs, row) -> new ReturnSummary(rs.getObject("id", UUID.class),
                rs.getObject("order_so_id", UUID.class), rs.getString("request_code"),
                rs.getString("status"), rs.getString("request_type"), rs.getString("reason"),
                rs.getString("resolution"), rs.getObject("requested_by", UUID.class),
                rs.getObject("approved_by", UUID.class), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)), returnId)
                .stream().findFirst()
                .orElseThrow(() -> new ApiProblem("RETURN_NOT_FOUND", "Không tìm thấy yêu cầu đổi trả.",
                        HttpStatus.NOT_FOUND));
        List<ReturnLineView> lines = jdbc.query("""
                SELECT rrline.order_line_id, rrline.quantity
                FROM return_request_line rrline
                WHERE rrline.return_request_id = ?
                ORDER BY rrline.order_line_id
                """, (rs, row) -> new ReturnLineView(
                rs.getObject("order_line_id", UUID.class), rs.getLong("quantity")), returnId);
        return new ReturnSummary(summary.id(), summary.orderSoId(), summary.requestCode(), summary.status(),
                summary.requestType(), summary.reason(), summary.resolution(), summary.requestedBy(),
                summary.approvedBy(), summary.version(), summary.createdAt(), summary.updatedAt(), lines);
    }

    private void persistRecalculation(
            OrderHeader order, EffectivePricingService.CalculationResult calculation) {
        int changed = jdbc.update("""
                UPDATE order_so SET subtotal_vnd = ?, discount_vnd = ?, total_vnd = ?,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND tenant_id = ? AND status = 'DRAFT' AND version = ?
                """, grossSubtotal(calculation), totalDiscount(calculation), calculation.totalVnd(),
                order.id(), order.tenantId(), order.version());
        if (changed == 0) {
            throw versionConflict(order.id());
        }
        jdbc.update("""
                UPDATE order_line SET deleted_at = CURRENT_TIMESTAMP
                WHERE order_so_id = ? AND deleted_at IS NULL
                """, order.id());
        replaceLines(order.tenantId(), order.id(), calculation.lines());
    }

    private EffectivePricingService.CalculationResult calculate(
            Jwt jwt, UUID nppId, UUID outletId,
            List<EffectivePricingService.CalculationLine> lines, String voucherCode) {
        Set<String> inputLines = new HashSet<>();
        for (EffectivePricingService.CalculationLine line : lines) {
            String key = line.productId().toString();
            if (!inputLines.add(key)) {
                throw new ApiProblem("DUPLICATE_ORDER_LINE",
                        "Không thể lặp lại cùng SKU và đơn vị trong một đơn.", HttpStatus.BAD_REQUEST);
            }
        }
        return pricing.calculate(jwt, new EffectivePricingService.CalculationRequest(
                nppId, outletId, lines, voucherCode, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    private void replaceLines(
            UUID tenantId, UUID orderId, List<EffectivePricingService.CalculatedLine> lines) {
        for (EffectivePricingService.CalculatedLine line : lines) {
            ProductUnit product = jdbc.query("""
                    SELECT tenant_id, base_unit_code,
                        (SELECT base_units FROM unit_conversion uc
                         WHERE uc.tenant_id = p.tenant_id AND uc.product_id = p.id AND uc.unit_code = ?) AS factor
                    FROM product p WHERE p.id = ? AND p.status = 'ACTIVE'
                    """, (rs, row) -> new ProductUnit(rs.getObject("tenant_id", UUID.class),
                    rs.getString("base_unit_code"), (Long) rs.getObject("factor")),
                    line.unitCode(), line.productId()).stream().findFirst()
                    .orElseThrow(() -> new ApiProblem("PRODUCT_NOT_FOUND", "Không tìm thấy SKU đang kinh doanh.",
                            HttpStatus.NOT_FOUND));
            if (product.factor() == null || product.factor() <= 0) {
                throw new ApiProblem("INVALID_PRODUCT_UNIT", "Đơn vị không có trong quy cách SKU.",
                        HttpStatus.BAD_REQUEST, Map.of("unitCode", line.unitCode()));
            }
            long orderedBaseQty = PricingMath.multiply(line.quantity(), product.factor());
            long gross = line.grossAmountVnd();
            jdbc.update("""
                    INSERT INTO order_line (
                        id, tenant_id, order_so_id, product_id, unit_code, entered_unit_code,
                        entered_qty, ordered_qty, confirmed_qty, shipped_qty, received_qty,
                        unit_price_vnd, is_gift, gross_amount_vnd, discount_vnd,
                        line_amount_vnd, promotion_code
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), tenantId, orderId, line.productId(), product.baseUnitCode(),
                    line.unitCode(), line.quantity(), orderedBaseQty, line.unitPriceVnd(), line.isGift(),
                    gross, line.discountVnd(), line.amountVnd(), line.promotionCode());
        }
    }

    private OrderDetail findIdempotent(
            BusinessAccessService.Actor actor, UUID nppId, String key, String requestHash) {
        List<IdempotentOrder> existing = jdbc.query("""
                SELECT id, request_hash FROM order_so
                WHERE tenant_id = ? AND idempotency_key = ? AND deleted_at IS NULL
                """, (rs, row) -> new IdempotentOrder(
                rs.getObject("id", UUID.class), rs.getString("request_hash")), nppId, key);
        if (existing.isEmpty()) {
            return null;
        }
        IdempotentOrder found = existing.get(0);
        if (found.requestHash() == null || !found.requestHash().equals(requestHash)) {
            throw new ApiProblem("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key đã được dùng cho nội dung đơn hàng khác.",
                    HttpStatus.CONFLICT, Map.of("orderId", found.id().toString()));
        }
        UUID creatorId = jdbc.queryForObject(
                "SELECT created_by FROM order_so WHERE id = ?", UUID.class, found.id());
        if (!actor.userId().equals(creatorId)) {
            throw new AccessDeniedException("Idempotency-Key đã được dùng bởi một tài khoản khác.");
        }
        return loadById(found.id());
    }

    private OrderHeader requireOrder(
            Jwt jwt, BusinessAccessService.Actor actor, String permission, UUID orderId, boolean lock) {
        ScopedOrders scope = scopedOrders(jwt, actor, permission);
        String sql = """
                SELECT os.id, os.tenant_id, os.npp_tenant_id, os.outlet_id, os.order_code, os.status,
                    os.created_by, os.version, os.total_vnd, os.subtotal_vnd, os.discount_vnd,
                    os.note, os.voucher_code, os.confirmation_due_at, os.expected_delivery_at,
                    os.created_at, os.updated_at
                FROM order_so os JOIN outlet o ON o.id = os.outlet_id
                WHERE os.id = ? AND os.deleted_at IS NULL AND
                """ + scope.sql() + (lock ? " FOR UPDATE OF os" : "");
        return jdbc.query(sql, (rs, row) -> orderHeader(rs), prepend(orderId, scope.arguments()).toArray())
                .stream().findFirst()
                .orElseThrow(() -> new ApiProblem("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng.",
                        HttpStatus.NOT_FOUND));
    }

    private ScopedOrders scopedOrders(
            Jwt jwt, BusinessAccessService.Actor actor, String permission) {
        PartnerAccessService.Filter outletScope = partnerAccess.outlets(jwt, actor, permission, "o");
        List<Object> arguments = new ArrayList<>(outletScope.arguments());
        StringBuilder sql = new StringBuilder("o.deleted_at IS NULL AND ").append(outletScope.sql());
        if ("NPP".equals(actor.tenantType())) {
            sql.append(" AND os.tenant_id = ? AND os.npp_tenant_id = ?");
            arguments.add(actor.tenantId());
            arguments.add(actor.tenantId());
        } else if ("OUTLET".equals(actor.tenantType())) {
            sql.append(" AND o.tenant_id = ?");
            arguments.add(actor.tenantId());
        } else if ("NSX".equals(actor.tenantType())) {
            ResolvedScope resolved = access.resolve(jwt, permission);
            if (!Set.of("A", "R", "T").contains(resolved.scope())) {
                sql.append(" AND FALSE");
            } else {
                var today = access.businessToday();
                sql.append("""
                         AND EXISTS (
                            SELECT 1 FROM outlet_npp onp
                            JOIN tenant_relation tr ON tr.npp_tenant_id = onp.npp_tenant_id
                            WHERE onp.outlet_id = os.outlet_id AND onp.npp_tenant_id = os.npp_tenant_id
                              AND onp.status = 'ACTIVE' AND tr.nsx_tenant_id = ?
                              AND tr.status = 'ACTIVE'
                             AND onp.effective_from <= ? AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                             AND tr.effective_from <= ? AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                         )
                        """);
                arguments.add(actor.tenantId());
                arguments.add(today);
                arguments.add(today);
                arguments.add(today);
                arguments.add(today);
            }
        } else {
            sql.append(" AND FALSE");
        }
        return new ScopedOrders(sql.toString(), arguments);
    }

    private OrderDetail detail(OrderHeader order, boolean includeHistory) {
        List<OrderLineView> lines = jdbc.query("""
                SELECT ol.id, ol.product_id, p.sku, p.name AS product_name, ol.unit_code,
                    ol.entered_unit_code, ol.entered_qty, ol.ordered_qty, ol.confirmed_qty,
                    ol.shipped_qty, ol.received_qty, ol.unit_price_vnd, ol.is_gift,
                    ol.gross_amount_vnd, ol.discount_vnd, ol.line_amount_vnd, ol.promotion_code
                FROM order_line ol JOIN product p ON p.id = ol.product_id
                WHERE ol.order_so_id = ? AND ol.deleted_at IS NULL
                ORDER BY p.sku, ol.id
                """, (rs, row) -> new OrderLineView(rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getString("sku"),
                rs.getString("product_name"), rs.getString("unit_code"),
                rs.getString("entered_unit_code"), rs.getLong("entered_qty"),
                rs.getLong("ordered_qty"), rs.getLong("confirmed_qty"),
                rs.getLong("shipped_qty"), rs.getLong("received_qty"),
                rs.getLong("unit_price_vnd"), rs.getBoolean("is_gift"),
                rs.getLong("gross_amount_vnd"), rs.getLong("discount_vnd"),
                rs.getLong("line_amount_vnd"), rs.getString("promotion_code")), order.id());
        List<StatusEvent> history = includeHistory ? jdbc.query("""
                SELECT from_status, to_status, actor_id, occurred_at, reason
                FROM order_status_history
                WHERE tenant_id = ? AND order_so_id = ?
                ORDER BY occurred_at, id
                """, (rs, row) -> new StatusEvent(rs.getString("from_status"),
                rs.getString("to_status"), rs.getObject("actor_id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("reason")),
                order.tenantId(), order.id()) : List.of();
        List<ReceiptLineView> receiptLines = jdbc.query("""
                SELECT id, order_line_id, received_qty, damaged_qty, note, created_by, created_at
                FROM order_receipt_line
                WHERE tenant_id = ? AND order_so_id = ?
                ORDER BY created_at, id
                """, (rs, row) -> new ReceiptLineView(rs.getObject("id", UUID.class),
                rs.getObject("order_line_id", UUID.class), rs.getLong("received_qty"),
                rs.getLong("damaged_qty"), rs.getString("note"),
                rs.getObject("created_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class)),
                order.tenantId(), order.id());
        List<UUID> receiptAttachments = jdbc.query("""
                SELECT attachment_id FROM order_receipt_attachment
                WHERE order_so_id = ? ORDER BY attachment_id
                """, (rs, row) -> rs.getObject("attachment_id", UUID.class), order.id());
        return new OrderDetail(order.id(), order.tenantId(), order.nppTenantId(), order.outletId(),
                order.orderCode(), order.status(), order.createdBy(), order.version(),
                order.subtotalVnd(), order.discountVnd(), order.totalVnd(), order.note(),
                order.voucherCode(), order.confirmationDueAt(), order.expectedDeliveryAt(),
                order.createdAt(), order.updatedAt(), lines, history, receiptLines, receiptAttachments);
    }

    private OrderDetail loadById(UUID orderId) {
        OrderHeader order = jdbc.query("""
                SELECT id, tenant_id, npp_tenant_id, outlet_id, order_code, status, created_by, version,
                    total_vnd, subtotal_vnd, discount_vnd, note, voucher_code, confirmation_due_at,
                    expected_delivery_at, created_at, updated_at
                FROM order_so WHERE id = ? AND deleted_at IS NULL
                """, (rs, row) -> orderHeader(rs), orderId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng.",
                        HttpStatus.NOT_FOUND));
        return detail(order, true);
    }

    private OrderHeader orderHeader(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderHeader(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("npp_tenant_id", UUID.class), rs.getObject("outlet_id", UUID.class),
                rs.getString("order_code"), rs.getString("status"), rs.getObject("created_by", UUID.class),
                rs.getLong("version"), rs.getLong("subtotal_vnd"), rs.getLong("discount_vnd"),
                rs.getLong("total_vnd"), rs.getString("note"), rs.getString("voucher_code"),
                rs.getObject("confirmation_due_at", OffsetDateTime.class),
                rs.getObject("expected_delivery_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private UUID resolveNpp(BusinessAccessService.Actor actor, UUID requestedNppId) {
        if ("NPP".equals(actor.tenantType())) {
            if (requestedNppId != null && !requestedNppId.equals(actor.tenantId())) {
                throw new AccessDeniedException("NPP chỉ được tạo đơn cho chính mình.");
            }
            return actor.tenantId();
        }
        if (requestedNppId == null) {
            throw new ApiProblem("NPP_REQUIRED", "Cần chọn NPP phục vụ đơn hàng.", HttpStatus.BAD_REQUEST);
        }
        return requestedNppId;
    }

    private void requireOutletNpp(UUID nppId, UUID outletId) {
        var today = access.businessToday();
        Boolean served = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM outlet_npp onp
                    JOIN tenant n ON n.id = onp.npp_tenant_id
                    JOIN outlet o ON o.id = onp.outlet_id
                    WHERE onp.outlet_id = ? AND onp.npp_tenant_id = ? AND onp.status = 'ACTIVE'
                      AND n.tenant_type = 'NPP' AND n.status = 'ACTIVE'
                      AND o.status = 'ACTIVE' AND o.deleted_at IS NULL
                      AND onp.effective_from <= ? AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                )
                """, Boolean.class, outletId, nppId, today, today);
        if (!Boolean.TRUE.equals(served)) {
            throw new ApiProblem("OUTLET_NOT_SERVED_BY_NPP",
                    "Điểm bán không được NPP này phục vụ ở thời điểm hiện tại.", HttpStatus.BAD_REQUEST);
        }
    }

    private void requireOrderCreateScope(
            Jwt jwt, BusinessAccessService.Actor actor, UUID outletId) {
        if ("NSX".equals(actor.tenantType())) {
            partnerAccess.requireOutlet(jwt, actor, "order_so:create", outletId);
        } else if ("OUTLET".equals(actor.tenantType())) {
            partnerAccess.requireOutlet(jwt, actor, "order_so:create", outletId);
        }
    }

    private void requireDraftAuthority(
            Jwt jwt, BusinessAccessService.Actor actor, OrderHeader order, String permission) {
        ResolvedScope scope = access.resolve(jwt, permission);
        if ("O".equals(scope.scope()) && !actor.userId().equals(order.createdBy())) {
            throw new AccessDeniedException("Chỉ người tạo mới được sửa hoặc hủy đơn chưa xác nhận.");
        }
    }

    private void requireCreator(BusinessAccessService.Actor actor, OrderHeader order) {
        if (!actor.userId().equals(order.createdBy())) {
            throw new AccessDeniedException("Chỉ người tạo mới được hủy đơn trước khi NPP xác nhận.");
        }
    }

    private void requireNppActor(BusinessAccessService.Actor actor) {
        if (!"NPP".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chức năng này chỉ dành cho tài khoản NPP.");
        }
    }

    private long grossSubtotal(EffectivePricingService.CalculationResult calculation) {
        long gross = 0;
        for (EffectivePricingService.CalculatedLine line : calculation.lines()) {
            gross = PricingMath.add(gross, line.grossAmountVnd());
        }
        return gross;
    }

    private long totalDiscount(EffectivePricingService.CalculationResult calculation) {
        return grossSubtotal(calculation) - calculation.totalVnd();
    }

    private ApiProblem versionConflict(UUID orderId) {
        return new ApiProblem("ORDER_VERSION_CONFLICT", "Đơn hàng đã được cập nhật bởi người khác.",
                HttpStatus.CONFLICT, Map.of("orderId", orderId.toString()));
    }

    private ApiProblem orderConflict(String code, String message, OrderHeader order) {
        return new ApiProblem(code, message, HttpStatus.CONFLICT,
                Map.of("orderId", order.id().toString(), "status", order.status()));
    }

    private static String requestHash(UUID nppId, OrderCreateRequest request) {
        String lines = request.lines().stream()
                .sorted(Comparator.comparing((EffectivePricingService.CalculationLine line) ->
                                line.productId().toString())
                        .thenComparing(line -> line.unitCode().strip().toUpperCase())
                        .thenComparingLong(EffectivePricingService.CalculationLine::quantity))
                .map(line -> line.productId() + ":" + line.unitCode().strip().toUpperCase()
                        + ":" + line.quantity())
                .reduce("", (left, right) -> left + "|" + right);
        String canonical = nppId + "|" + request.outletId() + "|" + lines + "|"
                + cleanNullable(request.voucherCode()) + "|" + clean(request.note()) + "|"
                + clean(request.reason()) + "|"
                + (request.submit() == null || request.submit());
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

    private static String cleanNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record ScopedOrders(String sql, List<Object> arguments) {
    }

    private record ProductUnit(UUID tenantId, String baseUnitCode, Long factor) {
    }

    private record IdempotentOrder(UUID id, String requestHash) {
    }

    private record OrderHeader(
            UUID id, UUID tenantId, UUID nppTenantId, UUID outletId, String orderCode, String status,
            UUID createdBy, long version, long subtotalVnd, long discountVnd, long totalVnd, String note,
            String voucherCode, OffsetDateTime confirmationDueAt, OffsetDateTime expectedDeliveryAt,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record OrderSummary(
            UUID id, String orderCode, String status, UUID nppTenantId, UUID outletId,
            String outletCode, String outletName, long totalVnd, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, long version) {
    }

    public record OrderDetail(
            UUID id, UUID tenantId, UUID nppTenantId, UUID outletId, String orderCode, String status,
            UUID createdBy, long version, long subtotalVnd, long discountVnd, long totalVnd, String note,
            String voucherCode, OffsetDateTime confirmationDueAt, OffsetDateTime expectedDeliveryAt,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, List<OrderLineView> lines,
            List<StatusEvent> history, List<ReceiptLineView> receiptLines, List<UUID> receiptAttachmentIds) {
    }

    public record OrderLineView(
            UUID id, UUID productId, String sku, String productName, String unitCode,
            String enteredUnitCode, long enteredQty, long orderedQty, long confirmedQty,
            long shippedQty, long receivedQty, long unitPriceVnd, boolean gift,
            long grossAmountVnd, long discountVnd, long lineAmountVnd, String promotionCode) {
    }

    public record StatusEvent(
            String fromStatus, String toStatus, UUID actorId, OffsetDateTime occurredAt, String reason) {
    }

    public record ReceiptLineView(
            UUID id, UUID orderLineId, long receivedQty, long damagedQty,
            String note, UUID createdBy, OffsetDateTime createdAt) {
    }

    public record OrderCreateRequest(
            UUID nppTenantId,
            @jakarta.validation.constraints.NotNull UUID outletId,
            @jakarta.validation.constraints.NotEmpty
            @jakarta.validation.Valid List<EffectivePricingService.CalculationLine> lines,
            @jakarta.validation.constraints.Size(max = 80) String voucherCode,
            @jakarta.validation.constraints.Size(max = 2000) String note,
            Boolean submit,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record DraftUpdateRequest(
            @jakarta.validation.constraints.NotEmpty
            @jakarta.validation.Valid List<EffectivePricingService.CalculationLine> lines,
            @jakarta.validation.constraints.Size(max = 80) String voucherCode,
            @jakarta.validation.constraints.Size(max = 2000) String note,
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record SubmitOrderRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record CancelOrderRequest(
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record LineQuantity(
            @jakarta.validation.constraints.NotNull UUID orderLineId,
            @jakarta.validation.constraints.PositiveOrZero long quantity) {
    }

    public record FulfillmentRequest(
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.Valid List<LineQuantity> lines,
            OffsetDateTime expectedDeliveryAt,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.PositiveOrZero long version) {
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
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max = 5)
            List<UUID> attachmentIds,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.PositiveOrZero long version) {
    }

    public record ReturnLineInput(
            @jakarta.validation.constraints.NotNull UUID orderLineId,
            @jakarta.validation.constraints.Positive long quantity) {
    }

    public record ReturnCreateRequest(
            @jakarta.validation.constraints.Pattern(regexp = "RETURN|EXCHANGE|REPLACEMENT")
            @jakarta.validation.constraints.NotBlank String requestType,
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.Valid List<ReturnLineInput> lines,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record ReturnDecisionRequest(
            boolean approved,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000) String resolution,
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.PositiveOrZero long orderVersion) {
    }

    public record ReturnLineView(UUID orderLineId, long quantity) {
    }

    public record ReturnSummary(
            UUID id, UUID orderSoId, String requestCode, String status, String requestType, String reason,
            String resolution, UUID requestedBy, UUID approvedBy, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, List<ReturnLineView> lines) {
        private ReturnSummary(
                UUID id, UUID orderSoId, String requestCode, String status, String requestType, String reason,
                String resolution, UUID requestedBy, UUID approvedBy, long version,
                OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this(id, orderSoId, requestCode, status, requestType, reason, resolution,
                    requestedBy, approvedBy, version, createdAt, updatedAt, List.of());
        }
    }

    private record OrderLineState(
            UUID id, long orderedQty, long confirmedQty, long shippedQty, long receivedQty) {
    }

    private record ReturnHeader(
            UUID id, UUID tenantId, UUID orderSoId, UUID requestedBy, String status, long version,
            String orderStatus, long orderVersion, UUID nppTenantId, UUID outletId) {
    }
}
