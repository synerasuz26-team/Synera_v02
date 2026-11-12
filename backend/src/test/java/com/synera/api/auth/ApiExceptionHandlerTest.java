package com.synera.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void reportsClosedPeriodMutationAsSpecificConflict() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "constraint violation", new SQLException("period_closed_data_lock"));

        var response = handler.dataConflict(exception);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("PERIOD_CLOSED_LOCK", response.getBody().code());
    }

    @Test
    void keepsOtherIntegrityConflictsGeneric() {
        var response = handler.dataConflict(new DataIntegrityViolationException("duplicate"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("DATA_CONFLICT", response.getBody().code());
    }
}
