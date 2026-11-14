package com.synera.api.operations;

import com.synera.api.auth.ApiProblem;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseCapacityPolicyTest {
    @Test
    void blocksHeavyWritesAtBudgetAndAllowsWritesBelowIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class))
                .thenReturn(DatabaseCapacityPolicy.WRITE_BUDGET_BYTES);

        assertThatThrownBy(() -> DatabaseCapacityPolicy.requireCapacityForHeavyWrite(jdbc))
                .isInstanceOf(ApiProblem.class)
                .hasMessageContaining("300 MB");

        when(jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class))
                .thenReturn(DatabaseCapacityPolicy.WRITE_BUDGET_BYTES - 1);
        assertThatCode(() -> DatabaseCapacityPolicy.requireCapacityForHeavyWrite(jdbc))
                .doesNotThrowAnyException();
    }
}
