package com.synera.api.operations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @ConditionalOnProperty(name="app.operations.enabled",havingValue="true")
public class OperationsHealthController {
    private final ActiveIdentity identity;private final OperationsMonitor monitor;
    public OperationsHealthController(ActiveIdentity identity,OperationsMonitor monitor){this.identity=identity;this.monitor=monitor;}
    @GetMapping("/api/v1/operations/health") public Map<String,Object> health(@AuthenticationPrincipal Jwt jwt){
        identity.systemAdmin(jwt);
        return monitor.snapshot();
    }
}
