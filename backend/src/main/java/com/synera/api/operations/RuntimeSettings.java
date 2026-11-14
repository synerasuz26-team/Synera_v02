package com.synera.api.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

@Service
public class RuntimeSettings {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final Map<String, Long> LIMITS = Map.ofEntries(
        Map.entry("nightly_hour",23L),Map.entry("nightly_minute",59L),
        Map.entry("reminder_lead_minutes",10080L),Map.entry("escalation_minutes",43200L),
        Map.entry("ticket_sla_minutes",43200L),Map.entry("ticket_warning_minutes",43200L),
        Map.entry("npp_cycle_days",365L),Map.entry("variance_warning_percent",100L),
        Map.entry("approval_threshold_vnd",9_000_000_000_000L),Map.entry("upload_max_bytes",10_485_760L),
        Map.entry("upload_expiry_minutes",60L),Map.entry("image_max_edge",4096L),
        Map.entry("thumbnail_edge",512L),Map.entry("token_retention_days",365L),
        Map.entry("push_max_attempts",10L),Map.entry("confirmation_minutes",43200L),Map.entry("delivery_sla_minutes",43200L));
    public RuntimeSettings(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public static long number(JdbcTemplate jdbc,UUID tenant,String key){
        Long value=jdbc.queryForObject("SELECT (config_value->>?)::bigint FROM tenant_config WHERE tenant_id=? AND config_key='p6.runtime'",Long.class,key,tenant);
        if(value==null)throw new IllegalStateException("Thiếu cấu hình "+key);return value;
    }
    public Values get(UUID tenant) {
        String value=jdbc.queryForObject("SELECT config_value::text FROM tenant_config WHERE tenant_id=? AND config_key='p6.runtime'",String.class,tenant);
        try { return new Values(json.readTree(value)); }
        catch(Exception e){throw new IllegalStateException("Cấu hình P6 không hợp lệ.",e);}
    }
    public void validate(JsonNode value) {
        if(value==null || !value.isObject() || value.size()!=LIMITS.size()+3)throw new IllegalArgumentException("Cần gửi đầy đủ các khóa cấu hình P6, không thêm khóa lạ.");
        LIMITS.forEach((key,max)->{
            JsonNode n=value.get(key);long min=key.equals("nightly_hour")||key.equals("nightly_minute")||key.equals("approval_threshold_vnd")||key.equals("variance_warning_percent")?0:1;
            if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<min||n.longValue()>max)throw new IllegalArgumentException("Ngưỡng không hợp lệ: "+key);
        });
        for(String key:java.util.List.of("nightly_enabled","reminders_enabled","push_enabled"))
            if(!value.path(key).isBoolean())throw new IllegalArgumentException("Cờ không hợp lệ: "+key);
        if(value.path("thumbnail_edge").asLong()>value.path("image_max_edge").asLong())throw new IllegalArgumentException("Thumbnail phải nhỏ hơn ảnh chính.");
    }
    public record Values(JsonNode json) {
        public long number(String key){JsonNode n=json.get(key);if(n==null||!n.isIntegralNumber())throw new IllegalStateException("Thiếu cấu hình "+key);return n.longValue();}
        public boolean enabled(String key){JsonNode n=json.get(key);if(n==null||!n.isBoolean())throw new IllegalStateException("Thiếu cấu hình "+key);return n.booleanValue();}
    }
}
