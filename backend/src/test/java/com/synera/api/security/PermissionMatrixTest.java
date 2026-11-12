package com.synera.api.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionMatrixTest {
    private final PermissionAuthorization authorization = new PermissionAuthorization();

    @Test
    void everySeededRolePermissionPairMatchesTheWorkbookMatrix() throws IOException {
        JsonNode seed;
        try (var input = new ClassPathResource("seed/system-seed.json").getInputStream()) {
            seed = new ObjectMapper().readTree(input);
        }

        List<String> permissions = new ArrayList<>();
        seed.path("permissions").forEach(row -> permissions.add(row.path("code").asText()));
        Map<String, Set<String>> grants = new HashMap<>();
        seed.path("rolePermissions").forEach(row -> grants
                .computeIfAbsent(row.path("roleCode").asText(), ignored -> new HashSet<>())
                .add(row.path("permissionCode").asText()));

        int checkedPairs = 0;
        for (JsonNode role : seed.path("roles")) {
            String roleCode = role.path("code").asText();
            Set<String> roleGrants = grants.getOrDefault(roleCode, Set.of());
            var authorities = roleGrants.stream()
                    .map(permission -> new SimpleGrantedAuthority("PERM_" + permission))
                    .toList();
            var authentication = new TestingAuthenticationToken(roleCode, "token", authorities);
            for (String permission : permissions) {
                assertEquals(roleGrants.contains(permission), authorization.hasPermission(authentication, permission),
                        roleCode + " × " + permission);
                checkedPairs++;
            }
        }
        assertEquals(16 * 108, checkedPairs);
    }
}
