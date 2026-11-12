package com.synera.api.auth;

import java.util.List;
import java.util.UUID;

public record AuthMeResponse(User user, Tenant tenant, List<AuthIdentity.Role> roles,
                             List<AuthIdentity.Permission> permissions) {
    public static AuthMeResponse from(AuthIdentity identity) {
        return new AuthMeResponse(
                new User(identity.id(), identity.fullName(), identity.email()),
                new Tenant(identity.tenantId(), identity.tenantType(), identity.tenantCode(), identity.tenantName()),
                identity.roles(),
                identity.permissions());
    }

    public record User(UUID id, String fullName, String email) {
    }

    public record Tenant(UUID id, String type, String code, String name) {
    }
}
