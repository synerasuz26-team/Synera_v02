package com.synera.api.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataVisibilityPolicy {
    public boolean canViewSellInPrice(Jwt user) {
        List<String> roles = user.getClaimAsStringList("roles");
        return !"OUTLET".equals(user.getClaimAsString("tenant_type"))
                && (roles == null || !roles.contains("NSX_SALES"))
                && hasPermission(user, "price_sellin:view");
    }

    public boolean canViewInternalNotes(Jwt user) {
        List<String> roles = user.getClaimAsStringList("roles");
        return "NSX".equals(user.getClaimAsString("tenant_type"))
                && (roles == null || !roles.contains("NSX_SALES"));
    }

    public CreditVisibility creditVisibility(Jwt user) {
        if (!"NPP".equals(user.getClaimAsString("tenant_type"))) {
            return CreditVisibility.FULL;
        }
        List<String> roles = user.getClaimAsStringList("roles");
        return roles != null && roles.contains("NPP_OWNER")
                ? CreditVisibility.FULL
                : CreditVisibility.REMAINING_ONLY;
    }

    private static boolean hasPermission(Jwt user, String permission) {
        var permissions = user.getClaimAsMap("permission_scopes");
        return permissions != null && permissions.containsKey(permission);
    }

    public enum CreditVisibility {
        FULL,
        REMAINING_ONLY
    }
}
