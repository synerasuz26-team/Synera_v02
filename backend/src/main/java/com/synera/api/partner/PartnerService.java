package com.synera.api.partner;

import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.ApiPage;
import com.synera.api.organization.BusinessAccessService;
import com.synera.api.security.AuditService;
import com.synera.api.security.ResolvedScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PartnerService {
    private final JdbcTemplate jdbc;
    private final BusinessAccessService access;
    private final PartnerAccessService partnerAccess;
    private final AuditService audit;

    public PartnerService(
            JdbcTemplate jdbc, BusinessAccessService access,
            PartnerAccessService partnerAccess, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.partnerAccess = partnerAccess;
        this.audit = audit;
    }

    public ApiPage<NppSummary> npps(Jwt jwt, String query, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        PartnerAccessService.Filter scope = partnerAccess.npps(jwt, actor, "npp:view", "n");
        StringBuilder where = new StringBuilder("n.tenant_type = 'NPP' AND ").append(scope.sql());
        List<Object> arguments = new ArrayList<>(scope.arguments());
        appendSearch(where, arguments, query, "n.code", "n.name");
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM tenant n WHERE " + where,
                Long.class, arguments.toArray());
        arguments.add(size);
        arguments.add((long) page * size);
        List<NppSummary> items = jdbc.query("""
                SELECT n.id, n.code, n.name, n.status, n.version,
                    (SELECT COUNT(*) FROM outlet_npp onp
                     WHERE onp.npp_tenant_id = n.id AND onp.status = 'ACTIVE') AS outlet_count
                FROM tenant n WHERE
                """ + where + " ORDER BY n.name LIMIT ? OFFSET ?",
                (rs, row) -> new NppSummary(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("status"), rs.getLong("outlet_count"),
                        rs.getLong("version")), arguments.toArray());
        return ApiPage.of(items, page, size, total);
    }

    public NppSummary npp(Jwt jwt, UUID nppId) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        partnerAccess.requireNpp(jwt, actor, "npp:view", nppId);
        return jdbc.query("""
                SELECT n.id, n.code, n.name, n.status, n.version,
                    (SELECT COUNT(*) FROM outlet_npp onp
                     WHERE onp.npp_tenant_id = n.id AND onp.status = 'ACTIVE') AS outlet_count
                FROM tenant n WHERE n.id = ? AND n.tenant_type = 'NPP'
                """, (rs, row) -> new NppSummary(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("status"), rs.getLong("outlet_count"),
                rs.getLong("version")), nppId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("NPP_NOT_FOUND", "Không tìm thấy nhà phân phối.",
                        HttpStatus.NOT_FOUND));
    }

    public ApiPage<OutletSummary> outlets(Jwt jwt, String query, String status, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        PartnerAccessService.Filter scope = partnerAccess.outlets(jwt, actor, "outlet:view", "o");
        StringBuilder where = new StringBuilder("o.deleted_at IS NULL AND ").append(scope.sql());
        List<Object> arguments = new ArrayList<>(scope.arguments());
        appendSearch(where, arguments, query, "o.code", "o.name");
        if (status != null && !status.isBlank()) {
            where.append(" AND o.status = ?");
            arguments.add(status.strip().toUpperCase());
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM outlet o WHERE " + where,
                Long.class, arguments.toArray());
        arguments.add(size);
        arguments.add((long) page * size);
        List<OutletSummary> items = jdbc.query("""
                SELECT o.id, o.code, o.name, o.address, o.status, o.version,
                    (SELECT COUNT(*) FROM outlet_npp onp
                     WHERE onp.outlet_id = o.id AND onp.status IN ('ACTIVE', 'PENDING')) AS npp_count
                FROM outlet o WHERE
                """ + where + " ORDER BY o.name, o.code LIMIT ? OFFSET ?",
                (rs, row) -> new OutletSummary(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("address"), rs.getString("status"),
                        rs.getInt("npp_count"), rs.getLong("version")), arguments.toArray());
        return ApiPage.of(items, page, size, total);
    }

    public Outlet360 outlet(Jwt jwt, UUID outletId) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        partnerAccess.requireOutlet(jwt, actor, "outlet:view", outletId);
        OutletSummary outlet = jdbc.query("""
                SELECT o.id, o.code, o.name, o.address, o.status, o.version,
                    (SELECT COUNT(*) FROM outlet_npp onp
                     WHERE onp.outlet_id = o.id AND onp.status IN ('ACTIVE', 'PENDING')) AS npp_count
                FROM outlet o WHERE o.id = ? AND o.deleted_at IS NULL
                """, (rs, row) -> new OutletSummary(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("address"), rs.getString("status"),
                rs.getInt("npp_count"), rs.getLong("version")), outletId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("OUTLET_NOT_FOUND", "Không tìm thấy điểm bán.",
                        HttpStatus.NOT_FOUND));
        long orders = orderCount(actor, outletId);
        long tickets = countTenantRecords("ticket", actor.tenantId(), outletId);
        long tasks = countOpenTasks(actor.tenantId(), outletId);
        return new Outlet360(outlet, orders, tickets, tasks, timeline(actor, outletId));
    }

    @Transactional
    public OutletSummary createOutlet(
            Jwt jwt, OutletCreateRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        if (!"NSX".equals(actor.tenantType()) && !"NPP".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chỉ NSX hoặc NPP được tạo hồ sơ điểm bán.");
        }
        LocalDate today = access.businessToday();
        UUID nppId = request.nppTenantId();
        if ("NPP".equals(actor.tenantType())) {
            if (nppId != null && !actor.tenantId().equals(nppId)) {
                throw new AccessDeniedException("NPP chỉ được tạo điểm bán cho chính mình.");
            }
            nppId = actor.tenantId();
        } else if (nppId != null) {
            requireRelatedNpp(actor.tenantId(), nppId, today);
        }
        if (request.routeId() != null) {
            access.requireRouteAccess(jwt, "outlet:create", request.routeId());
        } else if ("NSX".equals(actor.tenantType())
                && "T".equals(access.resolve(jwt, "outlet:create").scope())) {
            throw new ApiProblem("ROUTE_REQUIRED", "Cần chọn tuyến trong phạm vi được phân quyền.",
                    HttpStatus.BAD_REQUEST);
        }
        UUID outletTenantId = UUID.randomUUID();
        String tenantCode = "OUTLET-" + outletTenantId.toString().replace("-", "").substring(0, 20).toUpperCase();
        jdbc.update("""
                INSERT INTO tenant (id, tenant_type, code, name, status)
                VALUES (?, 'OUTLET', ?, ?, 'ACTIVE')
                """, outletTenantId, tenantCode, request.name().strip());
        UUID outletId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outlet (
                    id, tenant_id, code, name, address, status, created_by, created_by_tenant_id
                ) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, outletId, outletTenantId, normalizeCode(request.code()), request.name().strip(),
                clean(request.address()), actor.userId(), actor.tenantId());
        if (nppId != null) {
            addOutletNpp(outletId, nppId, "PENDING", today);
        }
        if (request.routeId() != null) {
            addRouteOutlet(actor.tenantId(), request.routeId(), outletId, today);
        }
        if (request.contactName() != null && !request.contactName().isBlank()) {
            jdbc.update("""
                    INSERT INTO contact (id, tenant_id, outlet_id, full_name, phone, is_primary)
                    VALUES (?, ?, ?, ?, ?, TRUE)
                    """, UUID.randomUUID(), actor.tenantId(), outletId, request.contactName().strip(),
                    request.contactPhone() == null ? "" : request.contactPhone().strip());
        }
        OutletSummary created = findOutlet(outletId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "outlet:create", "OUTLET_CREATED",
                "outlet", outletId, null, created, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return created;
    }

    @Transactional
    public OutletSummary updateOutlet(
            Jwt jwt, UUID outletId, OutletUpdateRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        partnerAccess.requireOutlet(jwt, actor, "outlet:update", outletId);
        OutletSummary before = findOutlet(outletId);
        if (!"OUTLET".equals(actor.tenantType()) && request.name() == null) {
            throw new ApiProblem("OUTLET_NAME_REQUIRED", "Tên điểm bán không được để trống.",
                    HttpStatus.BAD_REQUEST);
        }
        String name = request.name() == null ? before.name() : request.name().strip();
        if (name.isBlank()) {
            throw new ApiProblem("OUTLET_NAME_REQUIRED", "Tên điểm bán không được để trống.",
                    HttpStatus.BAD_REQUEST);
        }
        if ("OUTLET".equals(actor.tenantType()) && !before.name().equals(name)) {
            throw new AccessDeniedException("Điểm bán chỉ được cập nhật địa chỉ và thông tin liên hệ.");
        }
        int changed = jdbc.update("""
                UPDATE outlet SET name = ?, address = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND version = ? AND deleted_at IS NULL
                """, name, request.address().strip(), outletId, request.version());
        if (changed == 0) {
            throw versionConflict("outlet", outletId);
        }
        OutletSummary updated = findOutlet(outletId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "outlet:update", "OUTLET_UPDATED",
                "outlet", outletId, before, updated, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return updated;
    }

    @Transactional
    public OutletSummary approveOutlet(
            Jwt jwt, UUID outletId, OutletApprovalRequest request, boolean approve,
            HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        partnerAccess.requireNsx(actor);
        LocalDate today = access.businessToday();
        OutletSummary before = findOutlet(outletId);
        if (!"PENDING".equals(before.status())) {
            throw new ApiProblem("OUTLET_NOT_PENDING", "Hồ sơ điểm bán không còn chờ duyệt.",
                    HttpStatus.CONFLICT);
        }
        UUID nppId = request.nppTenantId() == null ? pendingNpp(outletId) : request.nppTenantId();
        if (nppId == null) {
            throw new ApiProblem("NPP_REQUIRED", "Cần chọn NPP phục vụ trước khi duyệt điểm bán.",
                    HttpStatus.BAD_REQUEST);
        }
        requireRelatedNpp(actor.tenantId(), nppId, today);
        access.requireRouteAccess(jwt, "outlet:approve", request.routeId());
        requireOutletInNsxNetwork(actor.tenantId(), outletId);
        String status = approve ? "ACTIVE" : "REJECTED";
        int changed = jdbc.update("""
                UPDATE outlet SET status = ?, approved_by = ?, approved_at = ?,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND status = 'PENDING' AND version = ?
                """, status, approve ? actor.userId() : null, approve ? OffsetDateTime.now() : null,
                outletId, before.version());
        if (changed == 0) {
            throw versionConflict("outlet", outletId);
        }
        if (approve) {
            addOutletNpp(outletId, nppId, "ACTIVE", today);
            if (request.routeId() != null) {
                addRouteOutlet(actor.tenantId(), request.routeId(), outletId, today);
            }
        } else {
            jdbc.update("""
                    UPDATE outlet_npp SET status = 'REJECTED'
                    WHERE outlet_id = ? AND npp_tenant_id = ? AND status = 'PENDING'
                    """, outletId, nppId);
        }
        jdbc.update("""
                INSERT INTO outlet_approval_history (
                    id, tenant_id, outlet_id, npp_tenant_id, route_id, decision, actor_id, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.tenantId(), outletId, nppId, request.routeId(),
                approve ? "APPROVED" : "REJECTED", actor.userId(), request.reason());
        OutletSummary after = findOutlet(outletId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "outlet:approve",
                approve ? "OUTLET_APPROVED" : "OUTLET_REJECTED", "outlet", outletId,
                before, after, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return after;
    }

    @Transactional
    public void assignNpp(
            Jwt jwt, UUID outletId, OutletNppAssignmentRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        partnerAccess.requireNsx(actor);
        ResolvedScope scope = access.resolve(jwt, "outlet:assign");
        if (request.routeId() != null) {
            access.requireRouteAccess(jwt, "outlet:assign", request.routeId());
        } else if ("R".equals(scope.scope())) {
            throw new ApiProblem("ROUTE_REQUIRED", "Cần chọn tuyến trong phạm vi được phân quyền.",
                    HttpStatus.BAD_REQUEST);
        } else {
            partnerAccess.requireOutlet(jwt, actor, "outlet:assign", outletId);
        }
        LocalDate today = access.businessToday();
        requireRelatedNpp(actor.tenantId(), request.nppTenantId(), today);
        requireOutletInNsxNetwork(actor.tenantId(), outletId);
        OutletSummary before = findOutlet(outletId);
        String assignmentStatus = "ACTIVE".equals(before.status()) ? "ACTIVE" : "PENDING";
        addOutletNpp(outletId, request.nppTenantId(), assignmentStatus, today);
        if (request.routeId() != null) {
            addRouteOutlet(actor.tenantId(), request.routeId(), outletId, today);
        }
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "outlet:assign", "OUTLET_NPP_ASSIGNED",
                "outlet", outletId, null, request, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
    }

    public List<ContactSummary> contacts(Jwt jwt, UUID outletId) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        partnerAccess.requireOutlet(jwt, actor, "contact:manage", outletId);
        return jdbc.query("""
                SELECT id, full_name, phone, email, title, is_primary, version
                FROM contact
                WHERE tenant_id = ? AND outlet_id = ?
                ORDER BY is_primary DESC, full_name
                """, (rs, row) -> new ContactSummary(rs.getObject("id", UUID.class),
                rs.getString("full_name"), rs.getString("phone"), rs.getString("email"),
                rs.getString("title"), rs.getBoolean("is_primary"), rs.getLong("version")),
                actor.tenantId(), outletId);
    }

    @Transactional
    public ContactSummary createContact(
            Jwt jwt, UUID outletId, ContactRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        partnerAccess.requireOutlet(jwt, actor, "contact:manage", outletId);
        if (request.isPrimary()) {
            jdbc.update("UPDATE contact SET is_primary = FALSE WHERE tenant_id = ? AND outlet_id = ?",
                    actor.tenantId(), outletId);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO contact (id, tenant_id, outlet_id, full_name, phone, email, title, is_primary)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, actor.tenantId(), outletId, request.fullName().strip(),
                request.phone() == null ? "" : request.phone().strip(),
                request.email() == null ? "" : request.email().strip(),
                request.title() == null ? "" : request.title().strip(), request.isPrimary());
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "contact:manage", "CONTACT_CREATED",
                "contact", id, null, request, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return findContact(actor.tenantId(), outletId, id);
    }

    @Transactional
    public ContactSummary updateContact(
            Jwt jwt, UUID outletId, UUID contactId, ContactUpdateRequest request,
            HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        partnerAccess.requireOutlet(jwt, actor, "contact:manage", outletId);
        ContactSummary before = findContact(actor.tenantId(), outletId, contactId);
        if (request.isPrimary()) {
            jdbc.update("""
                    UPDATE contact SET is_primary = FALSE
                    WHERE tenant_id = ? AND outlet_id = ? AND id <> ?
                    """, actor.tenantId(), outletId, contactId);
        }
        int changed = jdbc.update("""
                UPDATE contact SET full_name = ?, phone = ?, email = ?, title = ?, is_primary = ?,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE tenant_id = ? AND outlet_id = ? AND id = ? AND version = ?
                """, request.fullName().strip(), request.phone() == null ? "" : request.phone().strip(),
                request.email() == null ? "" : request.email().strip(),
                request.title() == null ? "" : request.title().strip(), request.isPrimary(),
                actor.tenantId(), outletId, contactId, request.version());
        if (changed == 0) {
            throw versionConflict("contact", contactId);
        }
        ContactSummary after = findContact(actor.tenantId(), outletId, contactId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "contact:manage", "CONTACT_UPDATED",
                "contact", contactId, before, after, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return after;
    }

    private long orderCount(BusinessAccessService.Actor actor, UUID outletId) {
        LocalDate today = access.businessToday();
        String sql;
        Object[] arguments;
        if ("NPP".equals(actor.tenantType())) {
            sql = "SELECT COUNT(*) FROM order_so WHERE outlet_id = ? AND npp_tenant_id = ? AND deleted_at IS NULL";
            arguments = new Object[]{outletId, actor.tenantId()};
        } else if ("NSX".equals(actor.tenantType())) {
            sql = """
                    SELECT COUNT(*) FROM order_so os
                    WHERE os.outlet_id = ? AND os.deleted_at IS NULL
                      AND EXISTS (
                        SELECT 1 FROM outlet_npp onp
                        JOIN tenant_relation tr ON tr.npp_tenant_id = onp.npp_tenant_id
                        WHERE onp.outlet_id = os.outlet_id AND onp.npp_tenant_id = os.npp_tenant_id
                          AND tr.nsx_tenant_id = ? AND onp.status = 'ACTIVE' AND tr.status = 'ACTIVE'
                          AND onp.effective_from <= ? AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                          AND tr.effective_from <= ? AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                      )
                    """;
            arguments = new Object[]{outletId, actor.tenantId(), today, today, today, today};
        } else {
            sql = """
                    SELECT COUNT(*) FROM order_so os
                    WHERE os.outlet_id = ? AND os.deleted_at IS NULL
                      AND EXISTS (
                        SELECT 1 FROM outlet_npp onp
                        WHERE onp.outlet_id = os.outlet_id AND onp.npp_tenant_id = os.npp_tenant_id
                          AND onp.status = 'ACTIVE' AND onp.effective_from <= ?
                          AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                      )
                    """;
            arguments = new Object[]{outletId, today, today};
        }
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private long countTenantRecords(String table, UUID tenantId, UUID outletId) {
        if (!"ticket".equals(table)) {
            throw new IllegalArgumentException("Không hỗ trợ bảng tổng hợp.");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                + " WHERE tenant_id = ? AND outlet_id = ? AND deleted_at IS NULL",
                Long.class, tenantId, outletId);
    }

    private long countOpenTasks(UUID tenantId, UUID outletId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM task
                WHERE tenant_id = ? AND outlet_id = ? AND deleted_at IS NULL
                  AND status IN ('OPEN', 'IN_PROGRESS')
                """, Long.class, tenantId, outletId);
    }

    private List<TimelineItem> timeline(BusinessAccessService.Actor actor, UUID outletId) {
        boolean hideInternal = !"NSX".equals(actor.tenantType());
        LocalDate today = access.businessToday();
        String orderScope;
        List<Object> orderArguments;
        if ("NSX".equals(actor.tenantType())) {
            orderScope = """
                    EXISTS (
                        SELECT 1 FROM tenant_relation tr
                        WHERE tr.nsx_tenant_id = ? AND tr.npp_tenant_id = os.npp_tenant_id
                          AND tr.status = 'ACTIVE' AND tr.effective_from <= ?
                          AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                    )
                    """;
            orderArguments = List.of(actor.tenantId(), today, today);
        } else if ("NPP".equals(actor.tenantType())) {
            orderScope = "os.npp_tenant_id = ?";
            orderArguments = List.of(actor.tenantId());
        } else {
            orderScope = """
                    EXISTS (
                        SELECT 1 FROM outlet_npp onp
                        WHERE onp.outlet_id = os.outlet_id AND onp.npp_tenant_id = os.npp_tenant_id
                          AND onp.status = 'ACTIVE' AND onp.effective_from <= ?
                          AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                    )
                    """;
            orderArguments = List.of(today, today);
        }
        String sql = """
                SELECT id, event_type, summary, occurred_at, actor_name FROM (
                    SELECT i.id, 'INTERACTION' AS event_type, i.summary, i.occurred_at,
                        u.full_name AS actor_name
                    FROM interaction i JOIN app_users u ON u.id = i.actor_id
                    WHERE i.tenant_id = ? AND i.outlet_id = ?
                      AND (? = FALSE OR i.visibility <> 'INTERNAL')
                    UNION ALL
                    SELECT t.id, 'TICKET' AS event_type, t.title AS summary, t.created_at AS occurred_at,
                        u.full_name AS actor_name
                    FROM ticket t JOIN app_users u ON u.id = t.created_by
                    WHERE t.tenant_id = ? AND t.outlet_id = ? AND t.deleted_at IS NULL
                    UNION ALL
                    SELECT t.id, 'TASK' AS event_type, t.title AS summary, t.created_at AS occurred_at,
                        u.full_name AS actor_name
                    FROM task t JOIN app_users u ON u.id = t.created_by
                    WHERE t.tenant_id = ? AND t.outlet_id = ? AND t.deleted_at IS NULL
                    UNION ALL
                    SELECT os.id, 'ORDER' AS event_type, os.order_code AS summary, os.created_at AS occurred_at,
                        u.full_name AS actor_name
                    FROM order_so os JOIN app_users u ON u.id = os.created_by
                    WHERE os.outlet_id = ? AND os.deleted_at IS NULL AND
                """ + orderScope + """
                ) timeline
                ORDER BY occurred_at DESC LIMIT 50
                """;
        List<Object> arguments = new ArrayList<>(List.of(
                actor.tenantId(), outletId, hideInternal,
                actor.tenantId(), outletId, actor.tenantId(), outletId, outletId));
        arguments.addAll(orderArguments);
        return jdbc.query(sql, (rs, row) -> new TimelineItem(rs.getObject("id", UUID.class),
                rs.getString("event_type"), rs.getString("summary"),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("actor_name")),
                arguments.toArray());
    }

    private ContactSummary findContact(UUID tenantId, UUID outletId, UUID contactId) {
        return jdbc.query("""
                SELECT id, full_name, phone, email, title, is_primary, version
                FROM contact WHERE tenant_id = ? AND outlet_id = ? AND id = ?
                """, (rs, row) -> new ContactSummary(rs.getObject("id", UUID.class),
                rs.getString("full_name"), rs.getString("phone"), rs.getString("email"),
                rs.getString("title"), rs.getBoolean("is_primary"), rs.getLong("version")),
                tenantId, outletId, contactId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("CONTACT_NOT_FOUND", "Không tìm thấy người liên hệ.",
                        HttpStatus.NOT_FOUND));
    }

    private OutletSummary findOutlet(UUID outletId) {
        return jdbc.query("""
                SELECT id, code, name, address, status, version,
                    (SELECT COUNT(*) FROM outlet_npp onp
                     WHERE onp.outlet_id = outlet.id AND onp.status IN ('ACTIVE', 'PENDING')) AS npp_count
                FROM outlet WHERE id = ? AND deleted_at IS NULL
                """, (rs, row) -> new OutletSummary(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("address"), rs.getString("status"),
                rs.getInt("npp_count"), rs.getLong("version")), outletId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("OUTLET_NOT_FOUND", "Không tìm thấy điểm bán.",
                        HttpStatus.NOT_FOUND));
    }

    private UUID pendingNpp(UUID outletId) {
        return jdbc.query("""
                SELECT npp_tenant_id FROM outlet_npp
                WHERE outlet_id = ? AND status = 'PENDING'
                ORDER BY effective_from DESC LIMIT 1
                """, (rs, row) -> rs.getObject("npp_tenant_id", UUID.class), outletId)
                .stream().findFirst().orElse(null);
    }

    private void requireRelatedNpp(UUID nsxTenantId, UUID nppId, LocalDate date) {
        Boolean related = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM tenant_relation
                    WHERE nsx_tenant_id = ? AND npp_tenant_id = ? AND status = 'ACTIVE'
                      AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                )
                """, Boolean.class, nsxTenantId, nppId, date, date);
        if (!Boolean.TRUE.equals(related)) {
            throw new ApiProblem("NPP_NOT_RELATED", "NPP không thuộc quan hệ hợp tác đang hiệu lực.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private void requireOutletInNsxNetwork(UUID nsxTenantId, UUID outletId) {
        LocalDate today = access.businessToday();
        Boolean eligible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM outlet o
                    WHERE o.id = ? AND o.deleted_at IS NULL
                      AND (
                        o.created_by_tenant_id = ?
                        OR EXISTS (
                            SELECT 1 FROM outlet_npp onp
                            JOIN tenant_relation tr ON tr.npp_tenant_id = onp.npp_tenant_id
                            WHERE onp.outlet_id = o.id
                              AND onp.status IN ('ACTIVE', 'PENDING')
                              AND tr.nsx_tenant_id = ? AND tr.status = 'ACTIVE'
                              AND onp.effective_from <= ? AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                              AND tr.effective_from <= ? AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                        )
                      )
                )
                """, Boolean.class, outletId, nsxTenantId, nsxTenantId,
                today, today, today, today);
        if (!Boolean.TRUE.equals(eligible)) {
            throw new AccessDeniedException("Điểm bán không thuộc mạng lưới hoặc yêu cầu của NSX.");
        }
    }

    private void addOutletNpp(UUID outletId, UUID nppId, String status, LocalDate date) {
        jdbc.update("""
                INSERT INTO outlet_npp (id, tenant_id, outlet_id, npp_tenant_id, status, effective_from)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, outlet_id, npp_tenant_id, effective_from)
                DO UPDATE SET status = EXCLUDED.status
                """, UUID.randomUUID(), nppId, outletId, nppId, status, date);
    }

    private void addRouteOutlet(UUID nsxTenantId, UUID routeId, UUID outletId, LocalDate date) {
        jdbc.update("""
                INSERT INTO route_outlet (id, tenant_id, route_id, outlet_id, position, effective_from)
                VALUES (?, ?, ?, ?, 0, ?)
                ON CONFLICT (tenant_id, route_id, outlet_id, effective_from) DO NOTHING
                """, UUID.randomUUID(), nsxTenantId, routeId, outletId, date);
    }

    private void appendSearch(StringBuilder where, List<Object> arguments, String query, String code, String name) {
        if (query != null && !query.isBlank()) {
            where.append(" AND (LOWER(").append(code).append(") LIKE ? OR LOWER(")
                    .append(name).append(") LIKE ?)");
            String pattern = "%" + query.strip().toLowerCase() + "%";
            arguments.add(pattern);
            arguments.add(pattern);
        }
    }

    private String normalizeCode(String code) {
        return code.strip().toUpperCase();
    }

    private String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private ApiProblem versionConflict(String resource, UUID id) {
        return new ApiProblem("VERSION_CONFLICT", "Dữ liệu đã được người khác cập nhật.",
                HttpStatus.CONFLICT, Map.of("resource", resource, "id", id.toString()));
    }

    public record NppSummary(UUID id, String code, String name, String status, long outletCount, long version) {
    }

    public record OutletSummary(
            UUID id, String code, String name, String address, String status, int nppCount, long version) {
    }

    public record Outlet360(
            OutletSummary outlet, long orderCount, long ticketCount, long openTaskCount,
            List<TimelineItem> timeline) {
    }

    public record TimelineItem(UUID id, String type, String summary, OffsetDateTime occurredAt, String actorName) {
    }

    public record ContactSummary(
            UUID id, String fullName, String phone, String email, String title, boolean primary, long version) {
    }

    public record OutletCreateRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String code,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String name,
            @jakarta.validation.constraints.Size(max = 2000) String address,
            UUID nppTenantId,
            UUID routeId,
            @jakarta.validation.constraints.Size(max = 160) String contactName,
            @jakarta.validation.constraints.Size(max = 40) String contactPhone,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record OutletUpdateRequest(
            @jakarta.validation.constraints.Size(max = 200) String name,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max = 2000) String address,
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record OutletApprovalRequest(
            UUID nppTenantId,
            @jakarta.validation.constraints.NotNull UUID routeId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record OutletNppAssignmentRequest(
            @jakarta.validation.constraints.NotNull UUID nppTenantId,
            UUID routeId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record ContactRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 160) String fullName,
            @jakarta.validation.constraints.Size(max = 40) String phone,
            @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max = 254) String email,
            @jakarta.validation.constraints.Size(max = 120) String title,
            boolean isPrimary,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record ContactUpdateRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 160) String fullName,
            @jakarta.validation.constraints.Size(max = 40) String phone,
            @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max = 254) String email,
            @jakarta.validation.constraints.Size(max = 120) String title,
            boolean isPrimary,
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }
}
