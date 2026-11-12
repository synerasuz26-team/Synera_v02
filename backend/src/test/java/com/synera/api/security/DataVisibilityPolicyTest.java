package com.synera.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataVisibilityPolicyTest {
    private final DataVisibilityPolicy policy = new DataVisibilityPolicy();

    @Test
    void blocksSellInPriceForFieldSalesEvenIfClaimIsPresent() {
        Jwt sales = jwt("NSX", List.of("NSX_SALES"), Map.of("price_sellin:view", "T"));
        assertFalse(policy.canViewSellInPrice(sales));
    }

    @Test
    void hidesInternalNotesFromNppAndOutletAndLimitsNppCreditVisibility() {
        Jwt nppStaff = jwt("NPP", List.of("NPP_STAFF"), Map.of());
        Jwt outletOwner = jwt("OUTLET", List.of("OUTLET_OWNER"), Map.of());

        assertFalse(policy.canViewInternalNotes(nppStaff));
        assertFalse(policy.canViewInternalNotes(outletOwner));
        assertEquals(DataVisibilityPolicy.CreditVisibility.REMAINING_ONLY,
                policy.creditVisibility(nppStaff));
        assertEquals(DataVisibilityPolicy.CreditVisibility.FULL, policy.creditVisibility(outletOwner));
    }

    @Test
    void exposesSensitiveSellInPriceOnlyWhenPermissionAndRoleAllowIt() {
        Jwt nsxOrder = jwt("NSX", List.of("NSX_ORDER"), Map.of("price_sellin:view", "A"));
        Jwt nsxSales = jwt("NSX", List.of("NSX_SALES"), Map.of("price_sellin:view", "T"));

        assertTrue(policy.canViewSellInPrice(nsxOrder));
        assertFalse(policy.canViewSellInPrice(nsxSales));
    }

    private static Jwt jwt(String tenantType, List<String> roles, Map<String, String> permissions) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-id")
                .claim("tenant_type", tenantType)
                .claim("roles", roles)
                .claim("permission_scopes", permissions)
                .build();
    }
}
