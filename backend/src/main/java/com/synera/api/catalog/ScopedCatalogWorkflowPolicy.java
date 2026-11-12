package com.synera.api.catalog;

import com.synera.api.auth.ApiProblem;
import com.synera.api.security.PermissionScopeResolver;
import com.synera.api.security.ResolvedScope;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class ScopedCatalogWorkflowPolicy {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final CatalogAccessService access;
    private final CatalogScopeService catalogScopes;
    private final PermissionScopeResolver permissionScopes;
    private final JdbcTemplate jdbc;

    public ScopedCatalogWorkflowPolicy(
            CatalogAccessService access,
            CatalogScopeService catalogScopes,
            PermissionScopeResolver permissionScopes,
            JdbcTemplate jdbc) {
        this.access = access;
        this.catalogScopes = catalogScopes;
        this.permissionScopes = permissionScopes;
        this.jdbc = jdbc;
    }

    public void requireRecordAccess(
            Jwt jwt, String permission, String table, String recordIdColumn,
            UUID tenantId, UUID recordId, UUID nppId, UUID outletId) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        ResolvedScope resolved = permissionScopes.resolveScope(jwt, permission);
        if ("A".equals(resolved.scope())) {
            return;
        }
        if (!List.of("R", "T").contains(resolved.scope())) {
            throw new AccessDeniedException("Dữ liệu nằm ngoài phạm vi được phân quyền.");
        }
        if (nppId == null || outletId == null) {
            throw new ApiProblem("OUTLET_CONTEXT_REQUIRED",
                    "Cần truyền NPP và điểm bán để áp dụng phạm vi phân quyền.", HttpStatus.BAD_REQUEST);
        }
        OffsetDateTime now = OffsetDateTime.now(BUSINESS_ZONE);
        var date = access.businessDate(now);
        access.requireRelatedNpp(actor, nppId, date);
        access.requireOutletServedBy(nppId, outletId, date);
        access.requireScopedOutlet(jwt, permission, outletId);

        List<CatalogScopeService.CatalogScope> recordScopes =
                catalogScopes.list(table, recordIdColumn, tenantId, recordId);
        if (recordScopes.isEmpty()) {
            throw new AccessDeniedException("Không thể duyệt dữ liệu ngoài phạm vi được phân quyền.");
        }
        for (CatalogScopeService.CatalogScope recordScope : recordScopes) {
            switch (recordScope.scopeType()) {
                case "OUTLET" -> {
                    if (!recordScope.scopeId().equals(outletId)) {
                        access.requireScopedOutlet(jwt, permission, recordScope.scopeId());
                    }
                }
                case "ORG_UNIT" -> requireOrgUnitScope(resolved, tenantId, recordScope.scopeId());
                case "NPP" -> requireNppScope(jwt, permission, recordScope.scopeId(), date);
                default -> throw new AccessDeniedException(
                        "Phạm vi dữ liệu quá rộng để thực hiện thao tác này.");
            }
        }
        if (!catalogScopes.matches(table, recordIdColumn, tenantId, recordId,
                nppId, outletId, now)) {
            throw new AccessDeniedException("Dữ liệu nằm ngoài phạm vi được phân quyền.");
        }
    }

    public void requireSelectedOutlet(Jwt jwt, String permission, UUID nppId, UUID outletId) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        ResolvedScope resolved = permissionScopes.resolveScope(jwt, permission);
        if ("A".equals(resolved.scope())) {
            return;
        }
        if (!List.of("R", "T").contains(resolved.scope())) {
            throw new AccessDeniedException("Dữ liệu nằm ngoài phạm vi được phân quyền.");
        }
        if (nppId == null || outletId == null) {
            throw new ApiProblem("OUTLET_CONTEXT_REQUIRED",
                    "Cần truyền NPP và điểm bán để áp dụng phạm vi phân quyền.", HttpStatus.BAD_REQUEST);
        }
        var date = access.businessToday();
        access.requireRelatedNpp(actor, nppId, date);
        access.requireOutletServedBy(nppId, outletId, date);
        access.requireScopedOutlet(jwt, permission, outletId);
    }

    public void requireScopeCoverage(
            Jwt jwt, String permission, UUID tenantId, List<CatalogScopeService.CatalogScope> recordScopes) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        ResolvedScope resolved = permissionScopes.resolveScope(jwt, permission);
        if ("A".equals(resolved.scope())) {
            return;
        }
        if (!List.of("R", "T").contains(resolved.scope()) || recordScopes.isEmpty()) {
            throw new AccessDeniedException("Phạm vi dữ liệu quá rộng để thực hiện thao tác này.");
        }
        var date = access.businessToday();
        for (CatalogScopeService.CatalogScope recordScope : recordScopes) {
            switch (recordScope.scopeType()) {
                case "OUTLET" -> access.requireScopedOutlet(jwt, permission, recordScope.scopeId());
                case "ORG_UNIT" -> requireOrgUnitScope(resolved, tenantId, recordScope.scopeId());
                case "NPP" -> requireNppScope(jwt, permission, recordScope.scopeId(), date);
                default -> throw new AccessDeniedException(
                        "Phạm vi dữ liệu quá rộng để thực hiện thao tác này.");
            }
        }
    }

    private void requireNppScope(
            Jwt jwt, String permission, UUID nppId, java.time.LocalDate date) {
        access.requireRelatedNpp(access.actor(jwt), nppId, date);
        List<UUID> outletIds = jdbc.query("""
                SELECT DISTINCT outlet_id FROM outlet_npp
                WHERE tenant_id = ? AND npp_tenant_id = ? AND status = 'ACTIVE'
                  AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                """, (rs, row) -> rs.getObject("outlet_id", UUID.class),
                nppId, nppId, date, date);
        if (outletIds.isEmpty()) {
            throw new AccessDeniedException("Phạm vi NPP chưa có điểm bán được phục vụ.");
        }
        for (UUID outletId : outletIds) {
            access.requireScopedOutlet(jwt, permission, outletId);
        }
    }

    private void requireOrgUnitScope(ResolvedScope scope, UUID tenantId, UUID orgUnitId) {
        if (!"R".equals(scope.scope())) {
            throw new AccessDeniedException("Phạm vi tổ chức không thuộc quyền được gán.");
        }
        String path = catalogScopes.path(tenantId, orgUnitId);
        boolean withinAssignedPath = path != null && scope.orgUnitPaths().stream()
                .anyMatch(assignedPath -> path.equals(assignedPath)
                        || path.startsWith(assignedPath.endsWith("/") ? assignedPath : assignedPath + "/"));
        if (!withinAssignedPath) {
            throw new AccessDeniedException("Phạm vi tổ chức nằm ngoài quyền được gán.");
        }
    }
}
