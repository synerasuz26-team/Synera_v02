package com.synera.api.order;

import com.synera.api.auth.ApiProblem;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.startsWith;

class OrderFlowServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrderFlowService flow = new OrderFlowService(jdbc);

    @Test
    void rejectsTransitionsMissingFromSeededFlowWithConflict() {
        doReturn(false).when(jdbc).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));

        ApiProblem problem = assertThrows(ApiProblem.class, () -> flow.transition(
                "SELL_OUT", UUID.randomUUID(), UUID.randomUUID(), "SUBMITTED", "CLOSED",
                "order_so:fulfill_confirm", UUID.randomUUID(), "reason", 3));

        assertEquals("INVALID_ORDER_TRANSITION", problem.code());
        assertEquals(409, problem.status().value());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void recordsOneStatusTransitionAndReturnsIncrementedVersion() {
        doReturn(true).when(jdbc).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));
        doReturn(1).when(jdbc).update(anyString(), any(Object[].class));
        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        long version = flow.transition("SELL_OUT", tenantId, orderId, "SUBMITTED", "CONFIRMED",
                "order_so:fulfill_confirm", actorId, "Đủ hàng", 5);

        assertEquals(6, version);
        verify(jdbc).update(startsWith("UPDATE order_so SET status"), any(Object[].class));
        verify(jdbc).update(startsWith("INSERT INTO order_status_history"), any(Object[].class));
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void allowsSystemOnlyTransitionAndRecordsNoHumanActor() {
        doReturn(true).when(jdbc).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));
        AtomicReference<Object> historyActor = new AtomicReference<>();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("INSERT INTO order_status_history")) {
                historyActor.set(invocation.getArgument(7));
            }
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));

        flow.transition("SELL_OUT", UUID.randomUUID(), UUID.randomUUID(),
                "RECEIVED_FULL", "CLOSED", "SYSTEM", null, "Tự động đóng.", 2);

        assertNull(historyActor.get());
    }
}
