package com.synera.api.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ResolvedScope(
        String scope,
        UUID tenantId,
        UUID ownerId,
        Set<UUID> orgUnitIds,
        List<String> orgUnitPaths,
        Set<UUID> routeIds) {
    public boolean hasRows() {
        return switch (scope) {
            case "A" -> true;
            case "R" -> !orgUnitPaths.isEmpty();
            case "T" -> !routeIds.isEmpty();
            case "O" -> ownerId != null;
            default -> false;
        };
    }
}
