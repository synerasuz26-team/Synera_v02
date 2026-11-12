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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class VisitService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final BusinessAccessService access;
    private final AuditService audit;

    public VisitService(
            JdbcTemplate jdbc, ObjectMapper objectMapper,
            BusinessAccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.access = access;
        this.audit = audit;
    }

    public ApiPage<VisitSummary> list(
            Jwt jwt, LocalDate date, String status, UUID routeId, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        ResolvedScope scope = access.resolve(jwt, "visit:view");
        BusinessAccessService.Filter scopeFilter = access.visitFilter(scope, "v");
        StringBuilder where = new StringBuilder(scopeFilter.sql());
        List<Object> baseArgs = new ArrayList<>(scopeFilter.arguments());
        if (date != null) {
            where.append(" AND timezone('Asia/Ho_Chi_Minh', v.planned_at)::date = ?");
            baseArgs.add(date);
        }
        if (status != null) {
            where.append(" AND v.status = ?");
            baseArgs.add(status);
        }
        if (routeId != null) {
            access.requireRouteAccess(jwt, "visit:view", routeId);
            where.append(" AND v.route_id = ?");
            baseArgs.add(routeId);
        }
        long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM visit v
                WHERE v.deleted_at IS NULL AND
                """ + where, Long.class, baseArgs.toArray());
        List<Object> arguments = new ArrayList<>(baseArgs);
        arguments.add(size);
        arguments.add((long) page * size);
        List<VisitSummary> rows = jdbc.query("""
                SELECT v.id, v.route_id, v.outlet_id, o.name AS outlet_name,
                    v.sales_user_id, u.full_name AS sales_name, v.status, v.planned_at,
                    v.checked_in_at, v.checked_out_at, v.version
                FROM visit v
                JOIN outlet o ON o.id = v.outlet_id
                JOIN app_users u ON u.id = v.sales_user_id
                WHERE v.deleted_at IS NULL AND
                """ + where + """
                ORDER BY v.planned_at NULLS LAST, o.name LIMIT ? OFFSET ?
                """, this::mapVisit, arguments.toArray());
        return ApiPage.of(rows, page, size, total);
    }

    @Transactional
    public VisitActionResult checkIn(
            Jwt jwt, UUID visitId, GpsActionRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireVisitAccess(jwt, "visit:checkin", visitId);
        VisitRow visit = lockVisit(actor.tenantId(), visitId);
        if (!"PLANNED".equals(visit.status())) {
            throw invalidVisitState(visit.status(), "PLANNED");
        }
        return applyGpsAction(jwt, actor, visit, request, "CHECK_IN", servletRequest);
    }

    @Transactional
    public VisitActionResult checkOut(
            Jwt jwt, UUID visitId, GpsActionRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireVisitAccess(jwt, "visit:checkin", visitId);
        VisitRow visit = lockVisit(actor.tenantId(), visitId);
        if (!"IN_PROGRESS".equals(visit.status())) {
            throw invalidVisitState(visit.status(), "IN_PROGRESS");
        }
        return applyGpsAction(jwt, actor, visit, request, "CHECK_OUT", servletRequest);
    }

    @Transactional
    public VisitActionResult reviewGpsException(
            Jwt jwt, UUID exceptionId, GpsExceptionReviewRequest request, boolean approve,
            HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        GpsException exception = jdbc.query("""
                SELECT id, visit_id, action, latitude, longitude, distance_m, status,
                    requested_by, reason
                FROM visit_gps_exception
                WHERE tenant_id = ? AND id = ?
                FOR UPDATE
                """, (rs, row) -> new GpsException(
                rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
                rs.getString("action"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                (Integer) rs.getObject("distance_m"), rs.getString("status"),
                rs.getObject("requested_by", UUID.class), rs.getString("reason")),
                actor.tenantId(), exceptionId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("GPS_EXCEPTION_NOT_FOUND", "Không tìm thấy ngoại lệ GPS.",
                        HttpStatus.NOT_FOUND));
        if (!"PENDING".equals(exception.status())) {
            throw new ApiProblem("GPS_EXCEPTION_ALREADY_REVIEWED", "Ngoại lệ GPS đã được xử lý.",
                    HttpStatus.CONFLICT);
        }
        if (actor.userId().equals(exception.requestedBy())) {
            throw new ApiProblem("APPROVER_MUST_DIFFER", "Người xác nhận phải khác người đề nghị.",
                    HttpStatus.CONFLICT);
        }
        access.requireVisitAccess(jwt, "visit:gps_override", exception.visitId());
        VisitRow visit = lockVisit(actor.tenantId(), exception.visitId());
        String nextStatus = approve ? "APPROVED" : "REJECTED";
        int changed = jdbc.update("""
                UPDATE visit_gps_exception
                SET status = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP, review_reason = ?
                WHERE tenant_id = ? AND id = ? AND status = 'PENDING'
                """, nextStatus, actor.userId(), request.reason(), actor.tenantId(), exceptionId);
        if (changed == 0) {
            throw new ApiProblem("GPS_EXCEPTION_ALREADY_REVIEWED", "Ngoại lệ GPS đã được xử lý.",
                    HttpStatus.CONFLICT);
        }
        if (approve) {
            applyApprovedException(actor.tenantId(), visit, exception, exceptionId);
        }
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "visit:gps_override",
                approve ? "VISIT_GPS_EXCEPTION_APPROVED" : "VISIT_GPS_EXCEPTION_REJECTED",
                "visit", visit.id(), exception,
                Map.of("exceptionId", exceptionId.toString(), "status", nextStatus, "reason", request.reason()),
                request.reason(), servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return new VisitActionResult(visit.id(), approve ? approvedVisitStatus(exception.action()) : visit.status(),
                exceptionId, exception.distanceM(), false);
    }

    @Transactional
    public ReassignmentResult reassign(
            Jwt jwt, UUID visitId, ReassignVisitRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireVisitAccess(jwt, "visit:reassign", visitId);
        VisitRow visit = lockVisit(actor.tenantId(), visitId);
        if (!Set.of("PLANNED", "IN_PROGRESS").contains(visit.status())) {
            throw invalidVisitState(visit.status(), "PLANNED hoặc IN_PROGRESS");
        }
        Boolean eligible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM app_users u
                    JOIN user_role ur ON ur.tenant_id = u.tenant_id AND ur.user_id = u.id
                    WHERE u.id = ? AND u.tenant_id = ? AND u.status = 'ACTIVE'
                      AND ur.role_code IN ('NSX_SALES', 'NSX_SUP')
                )
                """, Boolean.class, request.nextSalesUserId(), actor.tenantId());
        if (!Boolean.TRUE.equals(eligible)) {
            throw new ApiProblem("USER_NOT_ASSIGNABLE", "Người đi thay không phải nhân viên thị trường đang hoạt động.",
                    HttpStatus.BAD_REQUEST);
        }
        Boolean onRoute = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM user_route
                    WHERE tenant_id = ? AND user_id = ? AND route_id = ?
                )
                """, Boolean.class, actor.tenantId(), request.nextSalesUserId(), visit.routeId());
        if (!Boolean.TRUE.equals(onRoute)) {
            throw new ApiProblem("USER_NOT_ON_ROUTE", "Người đi thay chưa được gán vào tuyến này.",
                    HttpStatus.BAD_REQUEST);
        }
        if (visit.salesUserId().equals(request.nextSalesUserId())) {
            throw new ApiProblem("SAME_ASSIGNEE", "Người đi thay phải khác người đang phụ trách.",
                    HttpStatus.BAD_REQUEST);
        }
        jdbc.update("""
                INSERT INTO visit_reassignment_history (
                    id, tenant_id, visit_id, previous_sales_user_id, next_sales_user_id, reassigned_by, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.tenantId(), visitId, visit.salesUserId(),
                request.nextSalesUserId(), actor.userId(), request.reason());
        int changed = jdbc.update("""
                UPDATE visit SET sales_user_id = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE tenant_id = ? AND id = ? AND version = ? AND deleted_at IS NULL
                """, request.nextSalesUserId(), actor.tenantId(), visitId, visit.version());
        if (changed == 0) {
            throw versionConflict("visit", visitId);
        }
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "visit:reassign", "VISIT_REASSIGNED",
                "visit", visitId, visit.salesUserId(), request.nextSalesUserId(), request.reason(),
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return new ReassignmentResult(visitId, visit.salesUserId(), request.nextSalesUserId());
    }

    @Transactional
    public ChecklistResult saveChecklist(
            Jwt jwt, UUID visitId, ChecklistRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireVisitAccess(jwt, "visit:record", visitId);
        VisitRow visit = lockVisit(actor.tenantId(), visitId);
        if (!"IN_PROGRESS".equals(visit.status())) {
            throw invalidVisitState(visit.status(), "IN_PROGRESS");
        }
        String checklistCode = request.checklistCode().strip().toUpperCase();
        Boolean templateExists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM checklist_template
                    WHERE tenant_id = ? AND code = ? AND status = 'ACTIVE'
                )
                """, Boolean.class, actor.tenantId(), checklistCode);
        if (!Boolean.TRUE.equals(templateExists)) {
            throw new ApiProblem("CHECKLIST_NOT_FOUND", "Không tìm thấy checklist đang hiệu lực.",
                    HttpStatus.NOT_FOUND);
        }
        String resultJson = toJson(request.result());
        jdbc.update("""
                INSERT INTO visit_checklist_result (id, tenant_id, visit_id, checklist_code, result)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (tenant_id, visit_id, checklist_code)
                DO UPDATE SET result = EXCLUDED.result, recorded_at = CURRENT_TIMESTAMP
                """, UUID.randomUUID(), actor.tenantId(), visitId, checklistCode, resultJson);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "visit:record", "VISIT_CHECKLIST_RECORDED",
                "visit", visitId, null, request, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return new ChecklistResult(checklistCode, request.result(), OffsetDateTime.now(ZoneOffset.UTC));
    }

    public List<ChecklistResult> checklistResults(Jwt jwt, UUID visitId) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireVisitAccess(jwt, "visit:view", visitId);
        return jdbc.query("""
                SELECT checklist_code, result, recorded_at FROM visit_checklist_result
                WHERE tenant_id = ? AND visit_id = ? ORDER BY recorded_at
                """, (rs, row) -> new ChecklistResult(rs.getString("checklist_code"),
                readJson(rs.getString("result")), rs.getObject("recorded_at", OffsetDateTime.class)),
                actor.tenantId(), visitId);
    }

    public ApiPage<GpsExceptionSummary> pendingGpsExceptions(
            Jwt jwt, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        ResolvedScope scope = access.resolve(jwt, "visit:gps_override");
        BusinessAccessService.Filter visitFilter = access.visitFilter(scope, "v");
        String where = "g.status = 'PENDING' AND v.deleted_at IS NULL AND " + visitFilter.sql();
        List<Object> args = new ArrayList<>(visitFilter.arguments());
        long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM visit_gps_exception g
                JOIN visit v ON v.id = g.visit_id AND v.tenant_id = g.tenant_id
                WHERE """ + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<GpsExceptionSummary> items = jdbc.query("""
                SELECT g.id, g.visit_id, g.action, g.latitude, g.longitude, g.distance_m,
                    g.reason, g.requested_by, v.outlet_id, o.name AS outlet_name, g.created_at
                FROM visit_gps_exception g
                JOIN visit v ON v.id = g.visit_id AND v.tenant_id = g.tenant_id
                JOIN outlet o ON o.id = v.outlet_id
                WHERE """ + where + """
                ORDER BY g.created_at LIMIT ? OFFSET ?
                """, (rs, row) -> new GpsExceptionSummary(
                rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
                rs.getString("action"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                (Integer) rs.getObject("distance_m"), rs.getString("reason"),
                rs.getObject("requested_by", UUID.class), rs.getObject("outlet_id", UUID.class),
                rs.getString("outlet_name"), rs.getObject("created_at", OffsetDateTime.class)), args.toArray());
        return ApiPage.of(items, page, size, total);
    }

    public DailyReport dailyReport(Jwt jwt, LocalDate date) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        ResolvedScope scope = access.resolve(jwt, "daily_report:view");
        BusinessAccessService.Filter filter = access.visitFilter(scope, "v");
        return jdbc.query("""
                SELECT COUNT(*) AS planned,
                    COUNT(*) FILTER (WHERE v.status = 'COMPLETED') AS completed,
                    COUNT(*) FILTER (WHERE v.status = 'IN_PROGRESS') AS in_progress,
                    COUNT(*) FILTER (WHERE v.status = 'PLANNED') AS not_started
                FROM visit v
                WHERE v.deleted_at IS NULL
                  AND timezone('Asia/Ho_Chi_Minh', v.planned_at)::date = ?
                  AND """ + filter.sql(),
                rs -> {
                    if (!rs.next()) {
                        return new DailyReport(date, 0, 0, 0, 0, OffsetDateTime.now(ZoneOffset.UTC));
                    }
                    return new DailyReport(date, rs.getLong("planned"), rs.getLong("completed"),
                            rs.getLong("in_progress"), rs.getLong("not_started"),
                            OffsetDateTime.now(ZoneOffset.UTC));
                }, prepend(date, filter.arguments()).toArray());
    }

    private VisitActionResult applyGpsAction(
            Jwt jwt, BusinessAccessService.Actor actor, VisitRow visit, GpsActionRequest request,
            String action, HttpServletRequest servletRequest) {
        GpsFence fence = gpsFence(actor.tenantId(), visit.routeId(), visit.outletId());
        Integer distance = fence.latitude() == null || fence.longitude() == null
                ? null
                : GpsDistance.meters(fence.latitude(), fence.longitude(), request.latitude(), request.longitude());
        boolean outside = distance == null || distance > fence.radiusM();
        if (outside) {
            if (request.reason() == null || request.reason().isBlank()) {
                throw new ApiProblem("GPS_EXCEPTION_REASON_REQUIRED",
                        "Cần nêu lý do khi vị trí GPS ngoài bán kính hoặc chưa cấu hình tọa độ.",
                        HttpStatus.BAD_REQUEST);
            }
            UUID existingException = pendingException(actor.tenantId(), visit.id(), action);
            if (existingException != null) {
                return new VisitActionResult(visit.id(), visit.status(), existingException, distance, true);
            }
            UUID exceptionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO visit_gps_exception (
                        id, tenant_id, visit_id, action, latitude, longitude, distance_m, reason, requested_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, exceptionId, actor.tenantId(), visit.id(), action,
                    request.latitude(), request.longitude(), distance, request.reason().strip(), actor.userId());
            audit.recordIfSensitive(actor.tenantId(), actor.userId(), "visit:checkin",
                    "VISIT_GPS_EXCEPTION_REQUESTED", "visit", visit.id(), null,
                    Map.of("exceptionId", exceptionId.toString(), "action", action,
                            "distanceM", distance == null ? -1 : distance, "reason", request.reason()),
                    request.reason(), servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                    servletRequest.getHeader("User-Agent"));
            return new VisitActionResult(visit.id(), visit.status(), exceptionId, distance, true);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String sql = "CHECK_IN".equals(action)
                ? """
                    UPDATE visit SET status = 'IN_PROGRESS', checked_in_at = ?, checkin_latitude = ?,
                        checkin_longitude = ?, checkin_distance_m = ?, updated_at = CURRENT_TIMESTAMP,
                        version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = 'PLANNED'
                    """
                : """
                    UPDATE visit SET status = 'COMPLETED', checked_out_at = ?, checkout_latitude = ?,
                        checkout_longitude = ?, checkout_distance_m = ?, updated_at = CURRENT_TIMESTAMP,
                        version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = 'IN_PROGRESS'
                    """;
        int changed = jdbc.update(sql, now, request.latitude(), request.longitude(), distance,
                actor.tenantId(), visit.id(), visit.version());
        if (changed == 0) {
            throw versionConflict("visit", visit.id());
        }
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "visit:checkin",
                "CHECK_IN".equals(action) ? "VISIT_CHECKED_IN" : "VISIT_CHECKED_OUT",
                "visit", visit.id(), null, Map.of("latitude", request.latitude(),
                        "longitude", request.longitude(), "distanceM", distance),
                request.reason() == null ? "" : request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return new VisitActionResult(visit.id(), approvedVisitStatus(action), null, distance, false);
    }

    private void applyApprovedException(
            UUID tenantId, VisitRow visit, GpsException exception, UUID exceptionId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String sql = "CHECK_IN".equals(exception.action())
                ? """
                    UPDATE visit SET status = 'IN_PROGRESS', checked_in_at = ?, checkin_latitude = ?,
                        checkin_longitude = ?, checkin_distance_m = ?, checkin_gps_exception_id = ?,
                        updated_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = 'PLANNED'
                    """
                : """
                    UPDATE visit SET status = 'COMPLETED', checked_out_at = ?, checkout_latitude = ?,
                        checkout_longitude = ?, checkout_distance_m = ?, checkout_gps_exception_id = ?,
                        updated_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = 'IN_PROGRESS'
                    """;
        int changed = jdbc.update(sql, now, exception.latitude(), exception.longitude(),
                exception.distanceM(), exceptionId, tenantId, visit.id(), visit.version());
        if (changed == 0) {
            throw versionConflict("visit", visit.id());
        }
    }

    private GpsFence gpsFence(UUID tenantId, UUID routeId, UUID outletId) {
        LocalDate today = access.businessToday();
        return jdbc.query("""
                SELECT latitude, longitude, gps_radius_m FROM route_outlet
                WHERE tenant_id = ? AND route_id = ? AND outlet_id = ?
                  AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                  AND EXISTS (
                    SELECT 1 FROM outlet_npp onp
                    JOIN tenant_relation tr ON tr.npp_tenant_id = onp.npp_tenant_id
                    WHERE onp.outlet_id = route_outlet.outlet_id
                      AND tr.nsx_tenant_id = route_outlet.tenant_id
                      AND onp.status = 'ACTIVE' AND tr.status = 'ACTIVE'
                      AND onp.effective_from <= ? AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                      AND tr.effective_from <= ? AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                  )
                ORDER BY effective_from DESC LIMIT 1
                """, (rs, row) -> new GpsFence(nullableDouble(rs, "latitude"),
                nullableDouble(rs, "longitude"), rs.getInt("gps_radius_m")),
                tenantId, routeId, outletId, today, today, today, today, today, today).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("ROUTE_OUTLET_NOT_FOUND",
                        "Điểm bán không còn thuộc tuyến của lượt ghé.", HttpStatus.CONFLICT));
    }

    private UUID pendingException(UUID tenantId, UUID visitId, String action) {
        return jdbc.query("""
                SELECT id FROM visit_gps_exception
                WHERE tenant_id = ? AND visit_id = ? AND action = ? AND status = 'PENDING'
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, visitId, action)
                .stream().findFirst().orElse(null);
    }

    private VisitRow lockVisit(UUID tenantId, UUID visitId) {
        return jdbc.query("""
                SELECT id, route_id, outlet_id, sales_user_id, status, version
                FROM visit WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                FOR UPDATE
                """, (rs, row) -> new VisitRow(rs.getObject("id", UUID.class),
                rs.getObject("route_id", UUID.class), rs.getObject("outlet_id", UUID.class),
                rs.getObject("sales_user_id", UUID.class), rs.getString("status"), rs.getLong("version")),
                tenantId, visitId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("VISIT_NOT_FOUND", "Không tìm thấy lượt ghé.",
                        HttpStatus.NOT_FOUND));
    }

    private VisitSummary mapVisit(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new VisitSummary(rs.getObject("id", UUID.class), rs.getObject("route_id", UUID.class),
                rs.getObject("outlet_id", UUID.class), rs.getString("outlet_name"),
                rs.getObject("sales_user_id", UUID.class), rs.getString("sales_name"),
                rs.getString("status"), rs.getObject("planned_at", OffsetDateTime.class),
                rs.getObject("checked_in_at", OffsetDateTime.class),
                rs.getObject("checked_out_at", OffsetDateTime.class), rs.getLong("version"));
    }

    private Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private String toJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiProblem("INVALID_CHECKLIST_RESULT", "Không thể đọc nội dung checklist.",
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

    private List<Object> prepend(Object first, List<Object> rest) {
        List<Object> values = new ArrayList<>();
        values.add(first);
        values.addAll(rest);
        return values;
    }

    private ApiProblem invalidVisitState(String current, String expected) {
        return new ApiProblem("INVALID_VISIT_STATE", "Không thể thực hiện thao tác ở trạng thái hiện tại.",
                HttpStatus.CONFLICT, Map.of("currentStatus", current, "requiredStatus", expected));
    }

    private ApiProblem versionConflict(String resource, UUID id) {
        return new ApiProblem("VERSION_CONFLICT", "Dữ liệu đã được người khác cập nhật.",
                HttpStatus.CONFLICT, Map.of("resource", resource, "id", id.toString()));
    }

    private static String approvedVisitStatus(String action) {
        return "CHECK_IN".equals(action) ? "IN_PROGRESS" : "COMPLETED";
    }

    public record VisitSummary(
            UUID id, UUID routeId, UUID outletId, String outletName, UUID salesUserId,
            String salesName, String status, OffsetDateTime plannedAt,
            OffsetDateTime checkedInAt, OffsetDateTime checkedOutAt, long version) {
    }

    public record VisitActionResult(
            UUID visitId, String status, UUID gpsExceptionId, Integer distanceM, boolean awaitingApproval) {
    }

    public record GpsActionRequest(
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin("-90") @jakarta.validation.constraints.DecimalMax("90")
            Double latitude,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin("-180") @jakarta.validation.constraints.DecimalMax("180")
            Double longitude,
            @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record GpsExceptionReviewRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record ReassignVisitRequest(
            @jakarta.validation.constraints.NotNull UUID nextSalesUserId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record ReassignmentResult(UUID visitId, UUID previousSalesUserId, UUID nextSalesUserId) {
    }

    public record GpsExceptionSummary(
            UUID id, UUID visitId, String action, double latitude, double longitude, Integer distanceM,
            String reason, UUID requestedBy, UUID outletId, String outletName, OffsetDateTime createdAt) {
    }

    public record ChecklistRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80)
            String checklistCode,
            @jakarta.validation.constraints.NotNull JsonNode result,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record ChecklistResult(String checklistCode, JsonNode result, OffsetDateTime recordedAt) {
    }

    public record DailyReport(
            LocalDate date, long planned, long completed, long inProgress, long notStarted,
            OffsetDateTime updatedAt) {
    }

    private record VisitRow(UUID id, UUID routeId, UUID outletId, UUID salesUserId, String status, long version) {
    }

    private record GpsFence(Double latitude, Double longitude, int radiusM) {
    }

    private record GpsException(
            UUID id, UUID visitId, String action, double latitude, double longitude, Integer distanceM,
            String status, UUID requestedBy, String reason) {
    }
}
