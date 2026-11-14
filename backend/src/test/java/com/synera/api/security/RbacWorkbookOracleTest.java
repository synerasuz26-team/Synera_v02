package com.synera.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RbacWorkbookOracleTest {
    @Test void seedGrantsAndAuditFlagsMatchIndependentWorkbookSnapshot()throws Exception{
        var json=new ObjectMapper();var oracle=json.readTree(new ClassPathResource("acceptance/rbac-oracle.json").getInputStream());var seed=json.readTree(new ClassPathResource("seed/system-seed.json").getInputStream());
        assertThat(oracle.path("sourceSha256").asText()).matches("[a-f0-9]{64}");assertThat(oracle.path("roles").size()).isEqualTo(16);assertThat(oracle.path("permissions").size()).isEqualTo(108);
        Map<String,Map<String,String>> actual=new HashMap<>();for(var row:seed.path("rolePermissions"))actual.computeIfAbsent(row.path("roleCode").asText(),key->new HashMap<>()).put(row.path("permissionCode").asText(),row.path("scope").asText());
        int pairs=0;
        for(var role:oracle.path("roles"))for(var permission:oracle.path("permissions")){
            String code=role.asText(),key=permission.asText();var expected=oracle.path("grants").path(code).path(key);
            assertThat(actual.getOrDefault(code,Map.of()).get(key)).as(code+" × "+key).isEqualTo(expected.isMissingNode()?null:expected.asText());pairs++;
        }
        assertThat(pairs).isEqualTo(1728);
        for(var permission:seed.path("permissions")){String code=permission.path("code").asText();if(!code.equals("foundation:import"))assertThat(permission.path("sensitive").asBoolean()).as(code+" audit").isEqualTo(oracle.path("sensitive").path(code).asBoolean());}
        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(json.convertValue(oracle.path("roles"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}));
    }
}
