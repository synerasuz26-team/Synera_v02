package com.synera.api.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PermissionScopeResolver {
    private final JdbcTemplate jdbc;

    public PermissionScopeResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ResolvedScope resolveScope(Jwt user, String permission) {
        Map<String, Object> scopes = user.getClaimAsMap("permission_scopes");
        Object scopeValue = scopes == null ? null : scopes.get(permission);
        if (!(scopeValue instanceof String scope) || !List.of("A", "R", "T", "O").contains(scope)) {
            throw new AccessDeniedException("Bạn không có quyền thực hiện thao tác này.");
        }
        UUID tenantId = UUID.fromString(user.getClaimAsString("tenant_id"));
        UUID ownerId = UUID.fromString(user.getSubject());
        Set<UUID> orgUnitIds = ids(user.getClaimAsStringList("org_unit_ids"));
        Set<UUID> routeIds = ids(user.getClaimAsStringList("route_ids"));
        List<String> paths = scope.equals("R") ? assignedPaths(tenantId, orgUnitIds) : List.of();
        return new ResolvedScope(scope, tenantId, ownerId, orgUnitIds, paths, routeIds);
    }

    private List<String> assignedPaths(UUID tenantId, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .map(id -> jdbc.query("""
                        SELECT path FROM org_unit WHERE tenant_id = ? AND id = ?
                        """, rs -> rs.next() ? rs.getString("path") : null, tenantId, id))
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
    }

    private static Set<UUID> ids(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        Set<UUID> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(UUID.fromString(value)));
        return Set.copyOf(result);
    }
}
