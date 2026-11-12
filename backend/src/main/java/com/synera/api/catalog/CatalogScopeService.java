package com.synera.api.catalog;

import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.CatalogAccessService.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CatalogScopeService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate jdbc;
    private final CatalogAccessService access;

    public CatalogScopeService(JdbcTemplate jdbc, CatalogAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public void validate(Actor actor, List<CatalogScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return;
        }
        if (scopes.stream().map(scope -> scope.scopeType() + ":" + scope.scopeId())
                .distinct().count() != scopes.size()) {
            throw new ApiProblem("DUPLICATE_CATALOG_SCOPE", "Phạm vi bị trùng.", HttpStatus.BAD_REQUEST);
        }
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        for (CatalogScope scope : scopes) {
            boolean valid = switch (scope.scopeType()) {
                case "NPP" -> {
                    access.requireRelatedNpp(actor, scope.scopeId(), today);
                    yield true;
                }
                case "OUTLET" -> exists("""
                        SELECT EXISTS (
                            SELECT 1 FROM outlet o
                            JOIN outlet_npp onp ON onp.outlet_id = o.id
                            JOIN tenant_relation tr ON tr.npp_tenant_id = onp.npp_tenant_id
                            WHERE o.id = ? AND tr.nsx_tenant_id = ?
                              AND onp.status = 'ACTIVE' AND tr.status = 'ACTIVE'
                              AND onp.effective_from <= ?
                              AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                              AND tr.effective_from <= ?
                              AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                        )
                        """, scope.scopeId(), actor.tenantId(), today, today, today, today);
                case "ORG_UNIT" -> exists(
                        "SELECT EXISTS (SELECT 1 FROM org_unit WHERE tenant_id = ? AND id = ?)",
                        actor.tenantId(), scope.scopeId());
                default -> false;
            };
            if (!valid) {
                throw new ApiProblem("INVALID_CATALOG_SCOPE", "Phạm vi không thuộc tenant NSX.",
                        HttpStatus.BAD_REQUEST, Map.of("scopeType", scope.scopeType()));
            }
        }
    }

    public String matchesPredicate(String table, String recordIdColumn, String recordAlias) {
        validateScopeTable(table, recordIdColumn);
        return """
                (
                    NOT EXISTS (
                        SELECT 1 FROM """ + table + " s WHERE s.tenant_id = " + recordAlias
                + ".tenant_id AND s." + recordIdColumn + " = " + recordAlias + """
                            .id
                    )
                    OR EXISTS (
                        SELECT 1 FROM """ + table + " s WHERE s.tenant_id = " + recordAlias
                + ".tenant_id AND s." + recordIdColumn + " = " + recordAlias + """
                            .id AND (
                                (s.scope_type = 'NPP' AND s.scope_id = ?)
                                OR (s.scope_type = 'OUTLET' AND s.scope_id = ?)
                                OR (s.scope_type = 'ORG_UNIT' AND EXISTS (
                                    SELECT 1
                                    FROM org_unit scoped_unit
                                    JOIN route_outlet ro ON ro.tenant_id = scoped_unit.tenant_id
                                    JOIN route rt ON rt.id = ro.route_id AND rt.tenant_id = ro.tenant_id
                                    JOIN org_unit route_unit ON route_unit.id = rt.org_unit_id
                                        AND route_unit.tenant_id = rt.tenant_id
                                    WHERE scoped_unit.tenant_id = """ + recordAlias + ".tenant_id\n"
                + """
                                      AND scoped_unit.id = s.scope_id
                                      AND ro.outlet_id = ?
                                      AND ro.effective_from <= ?::date
                                      AND (ro.effective_to IS NULL OR ro.effective_to >= ?::date)
                                      AND (route_unit.path = scoped_unit.path
                                          OR route_unit.path LIKE scoped_unit.path || '/%')
                                ))
                            )
                    )
                )
                """;
    }

    public List<CatalogScope> list(String table, String recordIdColumn, UUID tenantId, UUID recordId) {
        validateScopeTable(table, recordIdColumn);
        return jdbc.query("SELECT scope_type, scope_id FROM " + table
                        + " WHERE tenant_id = ? AND " + recordIdColumn + " = ? ORDER BY scope_type, scope_id",
                (rs, row) -> new CatalogScope(rs.getString("scope_type"),
                        rs.getObject("scope_id", UUID.class)),
                tenantId, recordId);
    }

    public boolean matches(
            String table, String recordIdColumn, UUID tenantId, UUID recordId,
            UUID nppId, UUID outletId, OffsetDateTime at) {
        validateScopeTable(table, recordIdColumn);
        String recordTable = switch (table) {
            case "price_list_scope" -> "price_list";
            case "promotion_scope" -> "promotion";
            case "voucher_scope" -> "voucher";
            default -> throw new IllegalArgumentException("Không hỗ trợ loại phạm vi danh mục.");
        };
        List<Object> arguments = new ArrayList<>(matchArguments(nppId, outletId, at));
        arguments.add(tenantId);
        arguments.add(recordId);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT "
                + matchesPredicate(table, recordIdColumn, "r")
                + " FROM " + recordTable + " r WHERE r.tenant_id = ? AND r.id = ?",
                Boolean.class, arguments.toArray()));
    }

    public List<Object> matchArguments(UUID nppId, UUID outletId, OffsetDateTime at) {
        LocalDate date = at.toInstant().atZone(BUSINESS_ZONE).toLocalDate();
        return Arrays.asList(nppId, outletId, outletId, date, date);
    }

    public String path(UUID tenantId, UUID orgUnitId) {
        return jdbc.query("""
                SELECT path FROM org_unit WHERE tenant_id = ? AND id = ?
                """, rs -> rs.next() ? rs.getString("path") : null, tenantId, orgUnitId);
    }

    private void validateScopeTable(String table, String recordIdColumn) {
        if (!List.of("price_list_scope", "promotion_scope", "voucher_scope").contains(table)
                || !List.of("price_list_id", "promotion_id", "voucher_id").contains(recordIdColumn)) {
            throw new IllegalArgumentException("Không hỗ trợ loại phạm vi danh mục.");
        }
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    public record CatalogScope(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "ORG_UNIT|NPP|OUTLET") String scopeType,
            @jakarta.validation.constraints.NotNull UUID scopeId) {
    }
}
