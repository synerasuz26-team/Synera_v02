package com.synera.api.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.ApiPage;
import com.synera.api.security.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Map;

@RestController @Validated @RequestMapping("/api/v1/operations") @SecurityRequirement(name="bearerAuth")
public class OperationsController {
    private final ActiveIdentity identities;private final RuntimeSettings settings;private final JdbcTemplate jdbc;private final AuditService audit;
    public OperationsController(ActiveIdentity identities,RuntimeSettings settings,JdbcTemplate jdbc,AuditService audit){this.identities=identities;this.settings=settings;this.jdbc=jdbc;this.audit=audit;}
    @GetMapping("/settings")
    public Map<String,Object> settings(@AuthenticationPrincipal Jwt jwt){var actor=identities.admin(jwt);return Map.of("value",settings.get(actor.tenant()).json(),"version",jdbc.queryForObject("SELECT version FROM tenant_config WHERE tenant_id=? AND config_key='p6.runtime'",Long.class,actor.tenant()));}
    @PutMapping("/settings") @Transactional
    public Map<String,Object> update(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody SettingsRequest body,HttpServletRequest request){
        var actor=identities.admin(jwt);settings.validate(body.value());var old=settings.get(actor.tenant()).json();
        int n=jdbc.update("UPDATE tenant_config SET config_value=?::jsonb,version=version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND config_key='p6.runtime' AND version=?",body.value().toString(),actor.user(),actor.tenant(),body.version());
        if(n!=1)throw new ApiProblem("CONFIG_VERSION_CONFLICT","Cấu hình đã thay đổi, hãy tải lại.",HttpStatus.CONFLICT);
        audit.recordIfSensitive(actor.tenant(),actor.user(),"config:manage","RUNTIME_SETTINGS_UPDATED","tenant_config",actor.tenant(),old,body.value(),body.reason(),request.getRemoteAddr(),jwt.getClaimAsString("device_id"),request.getHeader("User-Agent"));
        return settings(jwt);
    }
    @GetMapping("/jobs")
    public ApiPage<Map<String,Object>> jobs(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="25") @Min(1) @Max(100) int size){
        var actor=identities.admin(jwt);long count=jdbc.queryForObject("SELECT count(*) FROM background_job_run WHERE tenant_id=?",Long.class,actor.tenant());
        var rows=jdbc.queryForList("SELECT id,job_name,run_key,status,started_at,finished_at,record_count,details::text AS details,error_code FROM background_job_run WHERE tenant_id=? ORDER BY started_at DESC,id LIMIT ? OFFSET ?",actor.tenant(),size,(long)page*size);
        return ApiPage.of(rows,page,size,count);
    }
    public record SettingsRequest(@NotNull JsonNode value,@Min(0) long version,@NotBlank @Size(max=500) String reason){}
}
