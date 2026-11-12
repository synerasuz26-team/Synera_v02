package com.synera.api.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RoleAssignmentPolicy {
    public void validate(
            UUID actorId,
            UUID targetId,
            UUID actorTenantId,
            UUID targetTenantId,
            String tenantType,
            String roleTenantType,
            int actorMaxLevel,
            int requestedLevel) {
        if (actorId.equals(targetId)) {
            throw new AccessDeniedException("Không thể tự gán vai cho chính mình.");
        }
        if (!actorTenantId.equals(targetTenantId)
                || !tenantType.equals(roleTenantType)
                || requestedLevel > actorMaxLevel) {
            throw new AccessDeniedException("Vai được gán không phù hợp với tenant hoặc cấp quyền của bạn.");
        }
    }
}
