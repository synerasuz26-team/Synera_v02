package com.synera.api.organization;

import com.synera.api.security.PermissionScopeResolver;
import com.synera.api.security.ResolvedScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class BusinessAccessService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbc;
    private final PermissionScopeResolver permissionScopes;

    public BusinessAccessService(JdbcTemplate jdbc, PermissionScopeResolver permissionScopes) {
        this.jdbc = jdbc;
        this.permissionScopes = permissionScopes;
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
            throw new AccessDeniedException("Chức năng này chỉ dành cho tài khoản NSX.");
        }
    }

    public ResolvedScope resolve(Jwt jwt, String permission) {
        return permissionScopes.resolveScope(jwt, permission);
    }

    public Filter orgUnitFilter(ResolvedScope scope, String alias) {
        return switch (scope.scope()) {
            case "A" -> new Filter(alias + ".tenant_id = ?", List.of(scope.tenantId()));
            case "R" -> new Filter(alias + ".tenant_id = ? AND " + pathPredicate(alias + ".path", scope.orgUnitPaths()),
                    prepend(scope.tenantId(), pathArguments(scope.orgUnitPaths())));
            default -> new Filter(alias + ".tenant_id = ? AND FALSE", List.of(scope.tenantId()));
        };
    }

    public Filter routeFilter(ResolvedScope scope, String alias) {
        if ("A".equals(scope.scope())) {
            return new Filter(alias + ".tenant_id = ?", List.of(scope.tenantId()));
        }
        if ("R".equals(scope.scope())) {
            List<Object> arguments = new ArrayList<>();
            arguments.add(scope.tenantId());
            arguments.addAll(pathArguments(scope.orgUnitPaths()));
            return new Filter(alias + """
                    .tenant_id = ? AND EXISTS (
                        SELECT 1 FROM org_unit scope_unit
                        WHERE scope_unit.tenant_id = """ + alias + ".tenant_id AND scope_unit.id = "
                    + alias + ".org_unit_id AND " + pathPredicate("scope_unit.path", scope.orgUnitPaths()) + ")",
                    arguments);
        }
        return routeIdFilter(scope, alias);
    }

    public Filter visitFilter(ResolvedScope scope, String alias) {
        if ("A".equals(scope.scope())) {
            return new Filter(alias + ".tenant_id = ?", List.of(scope.tenantId()));
        }
        if ("O".equals(scope.scope())) {
            return new Filter(alias + ".tenant_id = ? AND " + alias + ".sales_user_id = ?",
                    List.of(scope.tenantId(), scope.ownerId()));
        }
        if ("T".equals(scope.scope())) {
            if (scope.routeIds().isEmpty()) {
                return new Filter(alias + ".tenant_id = ? AND FALSE", List.of(scope.tenantId()));
            }
            List<UUID> routeIds = scope.routeIds().stream().sorted().toList();
            String placeholders = String.join(",", routeIds.stream().map(ignored -> "?").toList());
            List<Object> arguments = new ArrayList<>();
            arguments.add(scope.tenantId());
            arguments.addAll(routeIds);
            return new Filter(alias + ".tenant_id = ? AND " + alias + ".route_id IN ("
                    + placeholders + ")", arguments);
        }
        if ("R".equals(scope.scope())) {
            List<Object> arguments = new ArrayList<>();
            arguments.add(scope.tenantId());
            arguments.addAll(pathArguments(scope.orgUnitPaths()));
            return new Filter(alias + """
                    .tenant_id = ? AND EXISTS (
                        SELECT 1 FROM route scope_route
                        JOIN org_unit scope_unit ON scope_unit.tenant_id = scope_route.tenant_id
                            AND scope_unit.id = scope_route.org_unit_id
                        WHERE scope_route.tenant_id = """ + alias + ".tenant_id AND scope_route.id = "
                    + alias + ".route_id AND " + pathPredicate("scope_unit.path", scope.orgUnitPaths()) + ")",
                    arguments);
        }
        return new Filter(alias + ".tenant_id = ? AND FALSE", List.of(scope.tenantId()));
    }

    public void requireOrgUnitAccess(Jwt jwt, String permission, UUID orgUnitId) {
        ResolvedScope scope = resolve(jwt, permission);
        Filter filter = orgUnitFilter(scope, "ou");
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM org_unit ou
                    WHERE ou.id = ? AND """ + filter.sql() + """
                )
                """, Boolean.class, prepend(orgUnitId, filter.arguments()).toArray());
        requireAllowed(allowed);
    }

    public void requireRouteAccess(Jwt jwt, String permission, UUID routeId) {
        ResolvedScope scope = resolve(jwt, permission);
        Filter filter = routeFilter(scope, "r");
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM route r
                    WHERE r.id = ? AND """ + filter.sql() + """
                )
                """, Boolean.class, prepend(routeId, filter.arguments()).toArray());
        requireAllowed(allowed);
    }

    public void requireVisitAccess(Jwt jwt, String permission, UUID visitId) {
        ResolvedScope scope = resolve(jwt, permission);
        Filter filter = visitFilter(scope, "v");
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM visit v
                    WHERE v.id = ? AND v.deleted_at IS NULL AND """ + filter.sql() + """
                )
                """, Boolean.class, prepend(visitId, filter.arguments()).toArray());
        requireAllowed(allowed);
    }

    public LocalDate businessDate(OffsetDateTime dateTime) {
        return dateTime.toInstant().atZone(BUSINESS_ZONE).toLocalDate();
    }

    public LocalDate businessToday() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    private Filter routeIdFilter(ResolvedScope scope, String alias) {
        if (scope.routeIds().isEmpty()) {
            return new Filter(alias + ".tenant_id = ? AND FALSE", List.of(scope.tenantId()));
        }
        List<UUID> routeIds = scope.routeIds().stream().sorted().toList();
        String placeholders = String.join(",", routeIds.stream().map(ignored -> "?").toList());
        List<Object> arguments = new ArrayList<>();
        arguments.add(scope.tenantId());
        arguments.addAll(routeIds);
        return new Filter(alias + ".tenant_id = ? AND " + alias + ".id IN (" + placeholders + ")", arguments);
    }

    private static String pathPredicate(String column, List<String> paths) {
        if (paths.isEmpty()) {
            return "FALSE";
        }
        return "(" + String.join(" OR ",
                paths.stream().map(ignored -> column + " = ? OR " + column + " LIKE ?").toList()) + ")";
    }

    private static List<Object> pathArguments(List<String> paths) {
        List<Object> arguments = new ArrayList<>();
        for (String path : paths) {
            arguments.add(path);
            arguments.add(path.endsWith("/") ? path + "%" : path + "/%");
        }
        return arguments;
    }

    private static List<Object> prepend(Object first, List<Object> rest) {
        List<Object> result = new ArrayList<>();
        result.add(first);
        result.addAll(rest);
        return result;
    }

    private static void requireAllowed(Boolean allowed) {
        if (!Boolean.TRUE.equals(allowed)) {
            throw new AccessDeniedException("Dữ liệu nằm ngoài phạm vi được phân quyền.");
        }
    }

    public record Actor(UUID userId, UUID tenantId, String tenantType) {
    }

    public record Filter(String sql, List<Object> arguments) {
    }
}
