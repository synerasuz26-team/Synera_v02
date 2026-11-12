package com.synera.api.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import com.synera.api.security.PermissionScopeResolver;
import com.synera.api.security.ResolvedScope;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CatalogAccessService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate jdbc;
    private final PermissionScopeResolver scopes;

    public CatalogAccessService(JdbcTemplate jdbc, PermissionScopeResolver scopes) {
        this.jdbc = jdbc;
        this.scopes = scopes;
    }

    public Actor actor(Jwt jwt) {
        try {
            return new Actor(
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("tenant_id")),
                    jwt.getClaimAsString("tenant_type"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new AccessDeniedException("Thông tin tài khoản không hợp lệ.");
        }
    }

    public void requireNsx(Actor actor) {
        if (!"NSX".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chỉ tài khoản NSX được quản lý danh mục và giá.");
        }
    }

    public String productVisibility(Actor actor) {
        return switch (actor.tenantType()) {
            case "NSX" -> "p.tenant_id = ?";
            case "NPP" -> """
                    EXISTS (
                        SELECT 1 FROM tenant_relation tr
                        WHERE tr.nsx_tenant_id = p.tenant_id
                          AND tr.npp_tenant_id = ?
                          AND tr.status = 'ACTIVE'
                          AND tr.effective_from <= ?
                          AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                    )
                    """;
            case "OUTLET" -> """
                    EXISTS (
                        SELECT 1
                        FROM outlet_npp onp
                        JOIN tenant_relation tr ON tr.npp_tenant_id = onp.npp_tenant_id
                        WHERE onp.outlet_id = ?
                          AND tr.nsx_tenant_id = p.tenant_id
                          AND onp.status = 'ACTIVE'
                          AND tr.status = 'ACTIVE'
                          AND onp.effective_from <= ?
                          AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                          AND tr.effective_from <= ?
                          AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                    )
                    """;
            default -> "FALSE";
        };
    }

    public Object[] productVisibilityArguments(Actor actor) {
        LocalDate today = businessToday();
        return switch (actor.tenantType()) {
            case "NSX" -> new Object[]{actor.tenantId()};
            case "NPP" -> new Object[]{actor.tenantId(), today, today};
            case "OUTLET" -> new Object[]{actor.tenantId(), today, today, today, today};
            default -> new Object[0];
        };
    }

    public LocalDate businessDate(OffsetDateTime dateTime) {
        return dateTime.toInstant().atZone(BUSINESS_ZONE).toLocalDate();
    }

    public LocalDate businessToday() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    public void requireRelatedNpp(Actor actor, UUID nppId, LocalDate onDate) {
        if ("NPP".equals(actor.tenantType()) && actor.tenantId().equals(nppId)) {
            return;
        }
        if ("OUTLET".equals(actor.tenantType())) {
            throw new AccessDeniedException("Tài khoản điểm bán không được truy vấn qua NPP khác.");
        }
        if (!"NSX".equals(actor.tenantType())
                || !exists("""
                        SELECT EXISTS (
                            SELECT 1 FROM tenant_relation
                            WHERE nsx_tenant_id = ? AND npp_tenant_id = ? AND status = 'ACTIVE'
                              AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                        )
                        """, actor.tenantId(), nppId, onDate, onDate)) {
            throw new AccessDeniedException("NPP không thuộc phạm vi hợp tác của NSX.");
        }
    }

    public void requireOutletServedBy(UUID nppId, UUID outletId, LocalDate onDate) {
        if (!exists("""
                SELECT EXISTS (
                    SELECT 1 FROM outlet_npp
                    WHERE npp_tenant_id = ? AND outlet_id = ? AND status = 'ACTIVE'
                      AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                )
                """, nppId, outletId, onDate, onDate)) {
            throw new AccessDeniedException("Điểm bán không thuộc NPP được chọn.");
        }
    }

    public void requireOutletOwner(Actor actor, UUID outletId) {
        if (!"OUTLET".equals(actor.tenantType())
                || !exists("SELECT EXISTS (SELECT 1 FROM outlet WHERE id = ? AND tenant_id = ?)",
                        outletId, actor.tenantId())) {
            throw new AccessDeniedException("Điểm bán nằm ngoài phạm vi tài khoản.");
        }
    }

    public void requireScopedOutlet(Jwt jwt, String permission, UUID outletId) {
        ResolvedScope scope = scopes.resolveScope(jwt, permission);
        boolean allowed = switch (scope.scope()) {
            case "A" -> true;
            case "T" -> assignedRouteContainsOutlet(scope, outletId);
            case "R" -> assignedOrgPathContainsOutlet(scope, outletId);
            case "O" -> scope.ownerId() != null
                    && exists("""
                            SELECT EXISTS (
                                SELECT 1 FROM visit
                                WHERE tenant_id = ? AND sales_user_id = ? AND outlet_id = ?
                            )
                            """, scope.tenantId(), scope.ownerId(), outletId);
            default -> false;
        };
        if (!allowed) {
            throw new AccessDeniedException("Dữ liệu nằm ngoài phạm vi được phân quyền.");
        }
    }

    private boolean assignedRouteContainsOutlet(ResolvedScope scope, UUID outletId) {
        if (scope.routeIds().isEmpty()) {
            return false;
        }
        List<UUID> routeIds = scope.routeIds().stream().sorted().toList();
        String placeholders = String.join(",", routeIds.stream().map(ignored -> "?").toList());
        LocalDate today = businessToday();
        List<Object> arguments = new ArrayList<>();
        arguments.add(scope.tenantId());
        arguments.add(outletId);
        arguments.addAll(routeIds);
        arguments.add(today);
        arguments.add(today);
        return exists("""
                SELECT EXISTS (
                    SELECT 1 FROM route_outlet
                    WHERE tenant_id = ? AND outlet_id = ? AND route_id IN (""" + placeholders + """
                    ) AND effective_from <= ?
                      AND (effective_to IS NULL OR effective_to >= ?)
                )
                """, arguments.toArray());
    }

    private boolean assignedOrgPathContainsOutlet(ResolvedScope scope, UUID outletId) {
        if (scope.orgUnitPaths().isEmpty()) {
            return false;
        }
        String pathConditions = String.join(" OR ",
                scope.orgUnitPaths().stream().map(ignored -> "(ou.path = ? OR ou.path LIKE ?)").toList());
        List<Object> arguments = new ArrayList<>();
        arguments.add(scope.tenantId());
        arguments.add(outletId);
        for (String path : scope.orgUnitPaths()) {
            arguments.add(path);
            arguments.add(path.endsWith("/") ? path + "%" : path + "/%");
        }
        LocalDate today = businessToday();
        arguments.add(2, today);
        arguments.add(3, today);
        return exists("""
                SELECT EXISTS (
                    SELECT 1
                    FROM route_outlet ro
                    JOIN route r ON r.id = ro.route_id AND r.tenant_id = ro.tenant_id
                    JOIN org_unit ou ON ou.id = r.org_unit_id AND ou.tenant_id = r.tenant_id
                    WHERE ro.tenant_id = ? AND ro.outlet_id = ?
                      AND ro.effective_from <= ?
                      AND (ro.effective_to IS NULL OR ro.effective_to >= ?)
                      AND (""" + pathConditions + """
                      )
                )
                """, arguments.toArray());
    }

    private boolean exists(String sql, Object... arguments) {
        Boolean result = jdbc.queryForObject(sql, Boolean.class, arguments);
        return Boolean.TRUE.equals(result);
    }

    public record Actor(UUID userId, UUID tenantId, String tenantType) {
    }
}
