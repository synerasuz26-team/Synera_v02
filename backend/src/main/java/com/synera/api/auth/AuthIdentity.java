package com.synera.api.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AuthIdentity(
        UUID id,
        String fullName,
        String email,
        String passwordHash,
        String userStatus,
        UUID tenantId,
        String tenantType,
        String tenantCode,
        String tenantName,
        String tenantStatus,
        List<Role> roles,
        Map<String, String> permissionScopes,
        List<UUID> orgUnitIds,
        List<UUID> routeIds) {

    public record Role(String code, String name, int assignmentLevel) {
    }

    public record Permission(String code, String scope) {
    }

    public List<Permission> permissions() {
        return permissionScopes.entrySet().stream()
                .map(entry -> new Permission(entry.getKey(), entry.getValue()))
                .toList();
    }

    public List<String> roleCodes() {
        return roles.stream().map(Role::code).toList();
    }
}
