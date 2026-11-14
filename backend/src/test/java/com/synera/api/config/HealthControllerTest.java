package com.synera.api.config;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class HealthControllerTest {
    @Test void readinessAndDatabaseAreBothRequired(){
        var db=mock(JdbcTemplate.class);var availability=mock(ApplicationAvailability.class);var controller=new HealthController(db,availability);
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);assertThat(controller.health().getStatusCode().value()).isEqualTo(503);verifyNoInteractions(db);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);when(db.queryForObject("SELECT 1",Integer.class)).thenReturn(1);assertThat(controller.health().getStatusCode().value()).isEqualTo(200);
        when(db.queryForObject("SELECT 1",Integer.class)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database connection details"));
        assertThat(controller.health().getStatusCode().value()).isEqualTo(503);assertThat(controller.health().getBody()).containsOnlyKeys("status");
    }
}
