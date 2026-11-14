package com.synera.api.acceptance;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class P7SafetyTest {
    @Test void rejectsDevProductionAndUnownedTargetsBeforeOpeningAConnection(){
        var env=new HashMap<>(Map.of("P7_TEST_DB_URL","jdbc:postgresql://127.0.0.1:54321/synera_p7","P7_TEST_RUN","1","P7_RUN_ID","123456abcdef","P7_DB_CONTAINER","synera-p7-123456abcdef"));
        assertThatCode(()->P7HarnessTest.validateTarget(env)).doesNotThrowAnyException();
        for(String url:List.of("jdbc:postgresql://127.0.0.1:54321/synera_dev","jdbc:postgresql://production.example:5432/synera_p7","jdbc:postgresql://127.0.0.1:54321/synera_p7?currentSchema=public")){
            env.put("P7_TEST_DB_URL",url);assertThatThrownBy(()->P7HarnessTest.validateTarget(env)).isInstanceOf(IllegalStateException.class);
        }
        env.put("P7_TEST_DB_URL","jdbc:postgresql://127.0.0.1:54321/synera_p7");env.put("P7_DB_CONTAINER","user-database");assertThatThrownBy(()->P7HarnessTest.validateTarget(env)).isInstanceOf(IllegalStateException.class);
    }
}
