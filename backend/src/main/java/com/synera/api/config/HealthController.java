package com.synera.api.config;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    private final ApplicationAvailability availability;
    public HealthController(JdbcTemplate jdbc,ApplicationAvailability availability){this.jdbc=jdbc;this.availability=availability;}
    @GetMapping("/health") public ResponseEntity<Map<String,String>> health(){
        if(availability.getReadinessState()!=ReadinessState.ACCEPTING_TRAFFIC)return ResponseEntity.status(503).body(Map.of("status","DOWN"));
        try {
            jdbc.queryForObject("SELECT 1",Integer.class);
            return ResponseEntity.ok(Map.of("status","UP"));
        }catch(org.springframework.dao.DataAccessException e){return ResponseEntity.status(503).body(Map.of("status","DOWN"));}
    }
}
