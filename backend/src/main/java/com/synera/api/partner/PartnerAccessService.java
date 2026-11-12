package com.synera.api.partner;

import com.synera.api.organization.BusinessAccessService;
import com.synera.api.security.ResolvedScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PartnerAccessService {
    private final JdbcTemplate jdbc;
    private final BusinessAccessService access;

    public PartnerAccessService(JdbcTemplate jdbc, BusinessAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public Filter outlets(Jwt jwt, BusinessAccessService.Actor actor, String permission, String alias) {
        LocalDate today = access.businessToday();
        if ("NPP".equals(actor.tenantType())) {
            return new Filter("""
                    EXISTS (
                        SELECT 1 FROM outlet_npp onp
                        WHERE onp.outlet_id = """ + alias + ".id AND onp.npp_tenant_id = ?"
                    + """
                          AND onp.status IN ('ACTIVE', 'PENDING')
                          AND onp.effective_from <= ? AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                    )
                    """, List.of(actor.tenantId(), today, today));
        }
        if ("OUTLET".equals(actor.tenantType())) {
            return new Filter(alias + ".tenant_id = ?", List.of(actor.tenantId()));
        }
        requireNsx(actor);
        ResolvedScope scope = access.resolve(jwt, permission);
        String base = "(" + alias + ".created_by_tenant_id = ? OR EXISTS ("
                + "SELECT 1 FROM outlet_npp onp "
                + "JOIN tenant_relation tr ON tr.npp_tenant_id = onp.npp_tenant_id "
                + "WHERE onp.outlet_id = " + alias + ".id AND tr.nsx_tenant_id = ? "
                + "AND onp.status IN ('ACTIVE', 'PENDING') AND tr.status = 'ACTIVE' "
                + "AND onp.effective_from <= ? AND (onp.effective_to IS NULL OR onp.effective_to >= ?) "
                + "AND tr.effective_from <= ? AND (tr.effective_to IS NULL OR tr.effective_to >= ?))";
        List<Object> arguments = new ArrayList<>(List.of(
                actor.tenantId(), actor.tenantId(), today, today, today, today));
        if ("A".equals(scope.scope())) {
            return new Filter(base, arguments);
        }
        if ("T".equals(scope.scope())) {
            if (scope.routeIds().isEmpty()) {
                return denyFilter(alias, actor.tenantId());
            }
            List<UUID> routeIds = scope.routeIds().stream().sorted().toList();
            String placeholders = placeholders(routeIds.size());
            arguments.add(actor.tenantId());
            arguments.addAll(routeIds);
            arguments.add(today);
            arguments.add(today);
            return new Filter(base + " AND EXISTS (SELECT 1 FROM route_outlet ro "
                    + "WHERE ro.tenant_id = ? AND ro.outlet_id = " + alias + ".id "
                    + "AND ro.route_id IN (" + placeholders + ") "
                    + "AND ro.effective_from <= ? AND (ro.effective_to IS NULL OR ro.effective_to >= ?))",
                    arguments);
        }
        if ("R".equals(scope.scope())) {
            if (scope.orgUnitPaths().isEmpty()) {
                return denyFilter(alias, actor.tenantId());
            }
            arguments.add(actor.tenantId());
            for (String path : scope.orgUnitPaths()) {
                arguments.add(path);
                arguments.add(path.endsWith("/") ? path + "%" : path + "/%");
            }
            arguments.add(today);
            arguments.add(today);
            String paths = String.join(" OR ", scope.orgUnitPaths().stream()
                    .map(ignored -> "(ou.path = ? OR ou.path LIKE ?)").toList());
            return new Filter(base + " AND EXISTS (SELECT 1 FROM route_outlet ro "
                    + "JOIN route r ON r.tenant_id = ro.tenant_id AND r.id = ro.route_id "
                    + "JOIN org_unit ou ON ou.tenant_id = r.tenant_id AND ou.id = r.org_unit_id "
                    + "WHERE ro.tenant_id = ? AND ro.outlet_id = " + alias + ".id AND (" + paths + ") "
                    + "AND ro.effective_from <= ? AND (ro.effective_to IS NULL OR ro.effective_to >= ?))",
                    arguments);
        }
        return denyFilter(alias, actor.tenantId());
    }

    public Filter npps(Jwt jwt, BusinessAccessService.Actor actor, String permission, String alias) {
        if ("NPP".equals(actor.tenantType())) {
            return new Filter(alias + ".id = ? AND " + alias + ".tenant_type = 'NPP'",
                    List.of(actor.tenantId()));
        }
        requireNsx(actor);
        ResolvedScope scope = access.resolve(jwt, permission);
        LocalDate today = access.businessToday();
        List<Object> arguments = new ArrayList<>(List.of(actor.tenantId(), today, today));
        String base = "EXISTS (SELECT 1 FROM tenant_relation tr WHERE tr.npp_tenant_id = " + alias
                + ".id AND tr.nsx_tenant_id = ? AND tr.status = 'ACTIVE' "
                + "AND tr.effective_from <= ? AND (tr.effective_to IS NULL OR tr.effective_to >= ?)";
        if ("A".equals(scope.scope())) {
            return new Filter(base + ")", arguments);
        }
        if ("T".equals(scope.scope())) {
            if (scope.routeIds().isEmpty()) {
                return denyFilter(alias, actor.tenantId());
            }
            List<UUID> routeIds = scope.routeIds().stream().sorted().toList();
            arguments.add(actor.tenantId());
            arguments.addAll(routeIds);
            arguments.add(today);
            arguments.add(today);
            return new Filter(base + " AND EXISTS (SELECT 1 FROM outlet_npp onp "
                    + "JOIN route_outlet ro ON ro.outlet_id = onp.outlet_id "
                    + "WHERE onp.npp_tenant_id = " + alias + ".id AND ro.tenant_id = ? "
                    + "AND ro.route_id IN (" + placeholders(routeIds.size()) + ") "
                    + "AND ro.effective_from <= ? AND (ro.effective_to IS NULL OR ro.effective_to >= ?)))",
                    arguments);
        }
        if ("R".equals(scope.scope())) {
            if (scope.orgUnitPaths().isEmpty()) {
                return denyFilter(alias, actor.tenantId());
            }
            arguments.add(actor.tenantId());
            for (String path : scope.orgUnitPaths()) {
                arguments.add(path);
                arguments.add(path.endsWith("/") ? path + "%" : path + "/%");
            }
            arguments.add(today);
            arguments.add(today);
            String paths = String.join(" OR ", scope.orgUnitPaths().stream()
                    .map(ignored -> "(ou.path = ? OR ou.path LIKE ?)").toList());
            return new Filter(base + " AND EXISTS (SELECT 1 FROM outlet_npp onp "
                    + "JOIN route_outlet ro ON ro.outlet_id = onp.outlet_id "
                    + "JOIN route r ON r.tenant_id = ro.tenant_id AND r.id = ro.route_id "
                    + "JOIN org_unit ou ON ou.tenant_id = r.tenant_id AND ou.id = r.org_unit_id "
                    + "WHERE onp.npp_tenant_id = " + alias + ".id AND ro.tenant_id = ? AND (" + paths + ") "
                    + "AND ro.effective_from <= ? AND (ro.effective_to IS NULL OR ro.effective_to >= ?)))",
                    arguments);
        }
        return denyFilter(alias, actor.tenantId());
    }

    public void requireOutlet(Jwt jwt, BusinessAccessService.Actor actor, String permission, UUID outletId) {
        Filter filter = outlets(jwt, actor, permission, "o");
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM outlet o
                WHERE o.id = ? AND o.deleted_at IS NULL AND
                """ + filter.sql() + ")", Boolean.class, prepend(outletId, filter.arguments()).toArray());
        requireAllowed(allowed);
    }

    public void requireNpp(Jwt jwt, BusinessAccessService.Actor actor, String permission, UUID nppId) {
        Filter filter = npps(jwt, actor, permission, "n");
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM tenant n WHERE n.id = ? AND
                """ + filter.sql() + ")", Boolean.class, prepend(nppId, filter.arguments()).toArray());
        requireAllowed(allowed);
    }

    public void requireNsx(BusinessAccessService.Actor actor) {
        if (!"NSX".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chức năng này chỉ dành cho tài khoản NSX.");
        }
    }

    private Filter denyFilter(String alias, UUID tenantId) {
        return new Filter(alias + ".id IS NULL AND " + alias + ".tenant_id = ?", List.of(tenantId));
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
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

    public record Filter(String sql, List<Object> arguments) {
    }
}
