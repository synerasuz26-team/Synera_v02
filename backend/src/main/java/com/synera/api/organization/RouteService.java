package com.synera.api.organization;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class RouteService {
    private final JdbcTemplate jdbc;
    private final BusinessAccessService access;
    private final AuditService audit;

    public RouteService(JdbcTemplate jdbc, BusinessAccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    public ApiPage<RouteSummary> list(Jwt jwt, String query, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        LocalDate today = access.businessToday();
        ResolvedScope scope = access.resolve(jwt, "route:view");
        BusinessAccessService.Filter filter = access.routeFilter(scope, "r");
        List<Object> countArguments = new ArrayList<>(filter.arguments());
        StringBuilder where = new StringBuilder(filter.sql());
        if (query != null && !query.isBlank()) {
            where.append(" AND (LOWER(r.code) LIKE ? OR LOWER(r.name) LIKE ?)");
            String pattern = "%" + query.strip().toLowerCase() + "%";
            countArguments.add(pattern);
            countArguments.add(pattern);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM route r WHERE " + where,
                Long.class, countArguments.toArray());
        List<Object> arguments = new ArrayList<>(List.of(today, today));
        arguments.addAll(countArguments);
        arguments.add(size);
        arguments.add((long) page * size);
        List<RouteSummary> items = jdbc.query("""
                SELECT r.id, r.code, r.name, r.status, r.org_unit_id, ou.name AS org_unit_name,
                    r.version, (SELECT COUNT(*) FROM route_outlet ro
                        WHERE ro.tenant_id = r.tenant_id AND ro.route_id = r.id
                          AND ro.effective_from <= ? AND (ro.effective_to IS NULL OR ro.effective_to >= ?))
                        AS outlet_count
                FROM route r
                LEFT JOIN org_unit ou ON ou.tenant_id = r.tenant_id AND ou.id = r.org_unit_id
                WHERE """ + where + """
                ORDER BY r.code LIMIT ? OFFSET ?
                """, (rs, row) -> new RouteSummary(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("status"), rs.getObject("org_unit_id", UUID.class),
                rs.getString("org_unit_name"), rs.getLong("outlet_count"), rs.getLong("version")),
                arguments.toArray());
        return ApiPage.of(items, page, size, total);
    }

    @Transactional
    public RouteSummary create(Jwt jwt, RouteRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireOrgUnitAccess(jwt, "route:manage", request.orgUnitId());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO route (id, tenant_id, org_unit_id, code, name, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """, id, actor.tenantId(), request.orgUnitId(), normalizeCode(request.code()), request.name().strip());
        RouteSummary created = find(actor.tenantId(), id);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "route:manage", "ROUTE_CREATED",
                "route", id, null, created, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return created;
    }

    @Transactional
    public RouteSummary update(
            Jwt jwt, UUID id, RouteUpdateRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireRouteAccess(jwt, "route:manage", id);
        RouteSummary before = find(actor.tenantId(), id);
        ResolvedScope scope = access.resolve(jwt, "route:manage");
        if ("T".equals(scope.scope())) {
            if (!Objects.equals(before.orgUnitId(), request.orgUnitId())) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Không được chuyển tuyến ra ngoài phạm vi được phân quyền.");
            }
        } else {
            access.requireOrgUnitAccess(jwt, "route:manage", request.orgUnitId());
        }
        int updated = jdbc.update("""
                UPDATE route SET org_unit_id = ?, code = ?, name = ?, status = ?,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE tenant_id = ? AND id = ? AND version = ?
                """, request.orgUnitId(), normalizeCode(request.code()), request.name().strip(), request.status(),
                actor.tenantId(), id, request.version());
        if (updated == 0) {
            throw versionConflict("route", id);
        }
        RouteSummary after = find(actor.tenantId(), id);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "route:manage", "ROUTE_UPDATED",
                "route", id, before, after, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return after;
    }

    @Transactional
    public void assignSales(
            Jwt jwt, UUID routeId, SalesAssignmentRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireRouteAccess(jwt, "route:assign", routeId);
        Boolean eligible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM app_users u
                    JOIN user_role ur ON ur.tenant_id = u.tenant_id AND ur.user_id = u.id
                    WHERE u.id = ? AND u.tenant_id = ? AND u.status = 'ACTIVE'
                      AND ur.role_code IN ('NSX_SALES', 'NSX_SUP')
                )
                """, Boolean.class, request.userId(), actor.tenantId());
        if (!Boolean.TRUE.equals(eligible)) {
            throw new ApiProblem("USER_NOT_ASSIGNABLE", "Nhân viên không hoạt động hoặc ngoài tenant.",
                    HttpStatus.BAD_REQUEST);
        }
        jdbc.update("""
                INSERT INTO user_route (tenant_id, user_id, route_id)
                VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, user_id, route_id) DO NOTHING
                """, actor.tenantId(), request.userId(), routeId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "route:assign", "ROUTE_SALES_ASSIGNED",
                "route", routeId, null, Map.of("userId", request.userId().toString()),
                request.reason(), servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
    }

    public List<RouteOutlet> outlets(Jwt jwt, UUID routeId) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireRouteAccess(jwt, "route:view", routeId);
        LocalDate today = access.businessToday();
        return jdbc.query("""
                SELECT o.id, o.code, o.name, o.address, ro.position, ro.effective_from, ro.effective_to,
                    ro.latitude, ro.longitude, ro.gps_radius_m
                FROM route_outlet ro
                JOIN outlet o ON o.id = ro.outlet_id AND o.deleted_at IS NULL
                WHERE ro.tenant_id = ? AND ro.route_id = ?
                  AND ro.effective_from <= ? AND (ro.effective_to IS NULL OR ro.effective_to >= ?)
                  AND EXISTS (
                    SELECT 1 FROM outlet_npp onp
                    JOIN tenant_relation tr ON tr.npp_tenant_id = onp.npp_tenant_id
                    WHERE onp.outlet_id = o.id AND tr.nsx_tenant_id = ro.tenant_id
                      AND onp.status = 'ACTIVE' AND tr.status = 'ACTIVE'
                      AND onp.effective_from <= ? AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                      AND tr.effective_from <= ? AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                  )
                ORDER BY ro.position, o.name
                """, (rs, row) -> mapRouteOutlet(rs), actor.tenantId(), routeId,
                today, today, today, today, today, today);
    }

    @Transactional
    public void assignOutlet(
            Jwt jwt, UUID routeId, OutletAssignmentRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireRouteAccess(jwt, "route:assign", routeId);
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new ApiProblem("INVALID_EFFECTIVE_PERIOD", "Ngày kết thúc phải từ ngày bắt đầu trở đi.",
                    HttpStatus.BAD_REQUEST);
        }
        LocalDate effectiveDate = request.effectiveFrom();
        Boolean outletExists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM outlet o
                    JOIN outlet_npp onp ON onp.outlet_id = o.id
                    JOIN tenant_relation tr ON tr.npp_tenant_id = onp.npp_tenant_id
                    WHERE o.id = ? AND o.deleted_at IS NULL
                      AND tr.nsx_tenant_id = ?
                      AND onp.status = 'ACTIVE' AND tr.status = 'ACTIVE'
                      AND onp.effective_from <= ? AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                      AND tr.effective_from <= ? AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                )
                """, Boolean.class, request.outletId(), actor.tenantId(),
                effectiveDate, effectiveDate, effectiveDate, effectiveDate);
        if (!Boolean.TRUE.equals(outletExists)) {
            throw new ApiProblem("OUTLET_NOT_SERVICED",
                    "Điểm bán không được phục vụ bởi NPP đang hợp tác với NSX.",
                    HttpStatus.BAD_REQUEST);
        }
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO route_outlet (id, tenant_id, route_id, outlet_id, position, effective_from, effective_to)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, route_id, outlet_id, effective_from)
                DO UPDATE SET position = EXCLUDED.position, effective_to = EXCLUDED.effective_to
                """, assignmentId, actor.tenantId(), routeId, request.outletId(), request.position(),
                request.effectiveFrom(), request.effectiveTo());
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "route:assign", "ROUTE_OUTLET_ASSIGNED",
                "route", routeId, null, request, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
    }

    @Transactional
    public RouteOutlet setOutletLocation(
            Jwt jwt, UUID routeId, UUID outletId, OutletLocationRequest request,
            HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireRouteAccess(jwt, "route:manage", routeId);
        LocalDate today = access.businessToday();
        int updated = jdbc.update("""
                UPDATE route_outlet SET latitude = ?, longitude = ?, gps_radius_m = ?
                WHERE tenant_id = ? AND route_id = ? AND outlet_id = ?
                  AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                """, request.latitude(), request.longitude(), request.gpsRadiusM(),
                actor.tenantId(), routeId, outletId, today, today);
        if (updated == 0) {
            throw new ApiProblem("ROUTE_OUTLET_NOT_FOUND", "Điểm bán chưa được gán vào tuyến.",
                    HttpStatus.NOT_FOUND);
        }
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "route:manage", "OUTLET_GPS_UPDATED",
                "outlet", outletId, null, request, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return jdbc.query("""
                SELECT o.id, o.code, o.name, o.address, ro.position, ro.effective_from, ro.effective_to,
                    ro.latitude, ro.longitude, ro.gps_radius_m
                FROM route_outlet ro JOIN outlet o ON o.id = ro.outlet_id
                WHERE ro.tenant_id = ? AND ro.route_id = ? AND ro.outlet_id = ?
                  AND ro.effective_from <= ? AND (ro.effective_to IS NULL OR ro.effective_to >= ?)
                  AND EXISTS (
                    SELECT 1 FROM outlet_npp onp
                    JOIN tenant_relation tr ON tr.npp_tenant_id = onp.npp_tenant_id
                    WHERE onp.outlet_id = o.id AND tr.nsx_tenant_id = ro.tenant_id
                      AND onp.status = 'ACTIVE' AND tr.status = 'ACTIVE'
                      AND onp.effective_from <= ? AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                      AND tr.effective_from <= ? AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                  )
                """, (rs, row) -> mapRouteOutlet(rs), actor.tenantId(), routeId, outletId,
                today, today, today, today, today, today).stream()
                .findFirst()
                .orElseThrow(() -> new ApiProblem("ROUTE_OUTLET_NOT_FOUND", "Không tìm thấy điểm bán.",
                        HttpStatus.NOT_FOUND));
    }

    public List<McpEntry> mcp(Jwt jwt, UUID routeId, LocalDate date) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireRouteAccess(jwt, "route:view", routeId);
        return jdbc.query("""
                SELECT ms.outlet_id, o.name AS outlet_name, ms.week_of_month, ms.day_of_week,
                    ms.effective_from, ms.effective_to
                FROM mcp_schedule ms
                JOIN outlet o ON o.id = ms.outlet_id
                WHERE ms.tenant_id = ? AND ms.route_id = ? AND ms.cancelled_at IS NULL
                  AND ms.effective_from <= ? AND (ms.effective_to IS NULL OR ms.effective_to >= ?)
                  AND EXISTS (
                    SELECT 1 FROM outlet_npp onp
                    JOIN tenant_relation tr ON tr.npp_tenant_id = onp.npp_tenant_id
                    WHERE onp.outlet_id = o.id AND tr.nsx_tenant_id = ms.tenant_id
                      AND onp.status = 'ACTIVE' AND tr.status = 'ACTIVE'
                      AND onp.effective_from <= ? AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                      AND tr.effective_from <= ? AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                  )
                ORDER BY ms.week_of_month, ms.day_of_week, o.name
                """, (rs, row) -> new McpEntry(rs.getObject("outlet_id", UUID.class),
                rs.getString("outlet_name"), rs.getInt("week_of_month"), rs.getInt("day_of_week"),
                rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class)),
                actor.tenantId(), routeId, date, date, date, date, date, date);
    }

    @Transactional
    public List<McpEntry> replaceMcp(
            Jwt jwt, UUID routeId, McpUpdateRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        access.requireRouteAccess(jwt, "route:manage", routeId);
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new ApiProblem("INVALID_EFFECTIVE_PERIOD", "Ngày kết thúc phải từ ngày bắt đầu trở đi.",
                    HttpStatus.BAD_REQUEST);
        }
        for (McpSlot slot : request.slots()) {
            Boolean assigned = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM route_outlet
                        WHERE tenant_id = ? AND route_id = ? AND outlet_id = ?
                          AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                    )
                    """, Boolean.class, actor.tenantId(), routeId, slot.outletId(),
                    request.effectiveFrom(), request.effectiveFrom());
            if (!Boolean.TRUE.equals(assigned)) {
                throw new ApiProblem("OUTLET_NOT_ON_ROUTE", "Điểm bán chưa thuộc tuyến được chọn.",
                        HttpStatus.BAD_REQUEST);
            }
        }
        jdbc.update("""
                UPDATE mcp_schedule SET cancelled_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND route_id = ? AND cancelled_at IS NULL
                  AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                """, actor.tenantId(), routeId, request.effectiveFrom(), request.effectiveFrom());
        for (McpSlot slot : request.slots()) {
            jdbc.update("""
                    INSERT INTO mcp_schedule (
                        id, tenant_id, route_id, outlet_id, week_of_month, day_of_week,
                        effective_from, effective_to, cancelled_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
                    ON CONFLICT (tenant_id, route_id, outlet_id, week_of_month, day_of_week, effective_from)
                    DO UPDATE SET effective_to = EXCLUDED.effective_to, cancelled_at = NULL
                    """, UUID.randomUUID(), actor.tenantId(), routeId, slot.outletId(),
                    slot.weekOfMonth(), slot.dayOfWeek(), request.effectiveFrom(), request.effectiveTo());
        }
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "route:manage", "MCP_SCHEDULE_UPDATED",
                "route", routeId, null, request, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return mcp(jwt, routeId, request.effectiveFrom());
    }

    private RouteSummary find(UUID tenantId, UUID routeId) {
        return jdbc.query("""
                SELECT r.id, r.code, r.name, r.status, r.org_unit_id, ou.name AS org_unit_name,
                    r.version, (SELECT COUNT(*) FROM route_outlet ro
                        WHERE ro.tenant_id = r.tenant_id AND ro.route_id = r.id) AS outlet_count
                FROM route r LEFT JOIN org_unit ou
                    ON ou.tenant_id = r.tenant_id AND ou.id = r.org_unit_id
                WHERE r.tenant_id = ? AND r.id = ?
                """, (rs, row) -> new RouteSummary(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("status"),
                rs.getObject("org_unit_id", UUID.class), rs.getString("org_unit_name"),
                rs.getLong("outlet_count"), rs.getLong("version")), tenantId, routeId).stream()
                .findFirst().orElseThrow(() -> new ApiProblem("ROUTE_NOT_FOUND", "Không tìm thấy tuyến.",
                        HttpStatus.NOT_FOUND));
    }

    private RouteOutlet mapRouteOutlet(java.sql.ResultSet rs) throws java.sql.SQLException {
        double latitude = rs.getDouble("latitude");
        Double nullableLatitude = rs.wasNull() ? null : latitude;
        double longitude = rs.getDouble("longitude");
        Double nullableLongitude = rs.wasNull() ? null : longitude;
        return new RouteOutlet(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("address"), rs.getInt("position"),
                rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class),
                nullableLatitude, nullableLongitude, rs.getInt("gps_radius_m"));
    }

    private String normalizeCode(String code) {
        return code.strip().toUpperCase();
    }

    private ApiProblem versionConflict(String resource, UUID id) {
        return new ApiProblem("VERSION_CONFLICT", "Dữ liệu đã được người khác cập nhật.",
                HttpStatus.CONFLICT, Map.of("resource", resource, "id", id.toString()));
    }

    public record RouteSummary(
            UUID id, String code, String name, String status, UUID orgUnitId,
            String orgUnitName, long outletCount, long version) {
    }

    public record RouteOutlet(
            UUID id, String code, String name, String address, int position,
            LocalDate effectiveFrom, LocalDate effectiveTo, Double latitude, Double longitude, int gpsRadiusM) {
    }

    public record McpEntry(
            UUID outletId, String outletName, int weekOfMonth, int dayOfWeek,
            LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    public record RouteRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String code,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 160) String name,
            @jakarta.validation.constraints.NotNull UUID orgUnitId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record RouteUpdateRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String code,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 160) String name,
            @jakarta.validation.constraints.NotNull UUID orgUnitId,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record SalesAssignmentRequest(
            @jakarta.validation.constraints.NotNull UUID userId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record OutletAssignmentRequest(
            @jakarta.validation.constraints.NotNull UUID outletId,
            @jakarta.validation.constraints.PositiveOrZero int position,
            @jakarta.validation.constraints.NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record OutletLocationRequest(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("-90")
            @jakarta.validation.constraints.DecimalMax("90") Double latitude,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("-180")
            @jakarta.validation.constraints.DecimalMax("180") Double longitude,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(10000) int gpsRadiusM,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record McpUpdateRequest(
            @jakarta.validation.constraints.NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @jakarta.validation.constraints.NotNull
            List<@jakarta.validation.constraints.NotNull @jakarta.validation.Valid McpSlot> slots,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record McpSlot(
            @jakarta.validation.constraints.NotNull UUID outletId,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(5) int weekOfMonth,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(7) int dayOfWeek) {
    }
}
