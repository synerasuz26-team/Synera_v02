package com.synera.api.operations;

import com.synera.api.config.HttpTraffic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.LoggerFactory;
import java.util.Map;

@Service
@ConditionalOnProperty(name="app.operations.enabled",havingValue="true")
public class OperationsMonitor {
    private final JdbcTemplate jdbc;private final Environment env;private final HttpTraffic traffic;
    public OperationsMonitor(JdbcTemplate jdbc,Environment env,HttpTraffic traffic){this.jdbc=jdbc;this.env=env;this.traffic=traffic;}
    @Scheduled(fixedDelayString="${app.operations.scan-ms:60000}",initialDelayString="${app.operations.scan-ms:60000}")
    @Transactional public void scan(){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended('synera:p8-monitor',0))",Boolean.class)))return;
        long size=databaseBytes(),warning=env.getProperty("app.operations.database-warning-bytes",Long.class,350000000L),critical=env.getProperty("app.operations.database-critical-bytes",Long.class,425000000L);
        if(size>warning)LoggerFactory.getLogger(getClass()).warn("database_capacity databaseBytes={} warningBytes={} criticalBytes={} volumeBytesExact=false",size,warning,critical);
        else LoggerFactory.getLogger(getClass()).info("database_capacity databaseBytes={} warningBytes={} criticalBytes={} volumeBytesExact=false",size,warning,critical);
        if(size>warning)notice("DATABASE_CAPACITY", "db-capacity:"+java.time.LocalDate.now(java.time.ZoneOffset.UTC)+":"+(size>critical?"critical":"warning"),Map.of("databaseBytes",size,"severity",size>critical?"CRITICAL":"WARNING","note","Check Railway Volume usage including WAL"),null);
        for(var job:jdbc.queryForList("SELECT id,tenant_id,status FROM background_job_run WHERE job_name='NIGHTLY' AND status IN('FAILED','ABANDONED') AND started_at>CURRENT_TIMESTAMP-INTERVAL '2 days' ORDER BY started_at DESC LIMIT 100")){
            notice("NIGHTLY_JOB_FAILED","nightly-failed:"+job.get("id"),Map.of("jobRunId",job.get("id").toString(),"status",job.get("status")),job.get("tenant_id"));
            LoggerFactory.getLogger(getClass()).error("nightly_job_alert jobRunId={} status={}",job.get("id"),job.get("status"));
        }
        var snapshot=traffic.snapshot();
        if(((Number)snapshot.get("requests")).longValue()>=env.getProperty("app.operations.error-min-requests",Integer.class,20)
            &&((Number)snapshot.get("errorRate")).doubleValue()>=env.getProperty("app.operations.error-rate",Double.class,0.05)){
            notice("API_5XX_WARNING","api-5xx:"+snapshot.get("windowStartedAt"),snapshot,null);
            LoggerFactory.getLogger(getClass()).error("api_5xx_alert requests={} errors5xx={}",snapshot.get("requests"),snapshot.get("errors5xx"));
        }
        housekeeping();
    }
    public long databaseBytes(){return jdbc.queryForObject("SELECT pg_database_size(current_database())",Long.class);}
    public Map<String,Object> snapshot(){
        long bytes=databaseBytes(),warning=env.getProperty("app.operations.database-warning-bytes",Long.class,350000000L);
        long critical=env.getProperty("app.operations.database-critical-bytes",Long.class,425000000L);
        return Map.of("databaseBytes",bytes,"volumeCapacityBytes",500000000L,"databaseBudgetBytes",300000000L,
                "volumeUsageMeasured",false,"status",bytes>critical?"CRITICAL":bytes>warning?"WARNING":"HEALTHY",
                "volumePercent",(int)Math.min(100,Math.round(bytes*100.0/500000000L)),"http",traffic.snapshot());
    }
    private void notice(String type,String key,Map<String,Object> payload,Object tenant){
        String json;try{json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);}catch(Exception e){throw new IllegalArgumentException(e);}
        jdbc.update("""
            INSERT INTO notification(id,tenant_id,recipient_id,notification_type,event_key,payload)
            SELECT gen_random_uuid(),u.tenant_id,u.id,?,?,?::jsonb FROM app_users u JOIN tenant t ON t.id=u.tenant_id
            WHERE u.status='ACTIVE' AND t.status='ACTIVE' AND NOT t.is_demo
              AND (t.tenant_type IN('NSX','PLATFORM') OR u.tenant_id=?::uuid)
              AND EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code
                         WHERE ur.user_id=u.id AND ur.tenant_id=u.tenant_id AND rp.permission_code='config:manage' AND rp.scope_code='A')
              AND (?::uuid IS NULL OR u.tenant_id=?::uuid OR t.tenant_type='PLATFORM')
            ON CONFLICT DO NOTHING
            """,type,key,json,tenant,tenant,tenant);
    }
    private void housekeeping(){
        int jobs=Math.max(1,env.getProperty("app.operations.job-retention-days",Integer.class,3));
        int notices=Math.max(1,env.getProperty("app.operations.notification-retention-days",Integer.class,30));
        // Keep nightly success keys for catch-up idempotency; never delete audit or business records.
        jdbc.update("DELETE FROM background_job_run WHERE id IN(SELECT id FROM background_job_run WHERE job_name IN('REMINDERS','EXPO_PUSH') AND status IN('SUCCEEDED','SKIPPED') AND finished_at<CURRENT_TIMESTAMP-(? * INTERVAL '1 day') ORDER BY finished_at LIMIT 500)",jobs);
        jdbc.update("DELETE FROM push_delivery WHERE id IN(SELECT p.id FROM push_delivery p JOIN notification n ON n.id=p.notification_id WHERE p.status IN('DELIVERED','CANCELLED','FAILED') AND n.created_at<CURRENT_TIMESTAMP-(? * INTERVAL '1 day') ORDER BY p.updated_at LIMIT 500)",notices);
        jdbc.update("DELETE FROM notification WHERE id IN(SELECT n.id FROM notification n WHERE n.created_at<CURRENT_TIMESTAMP-(? * INTERVAL '1 day') AND NOT EXISTS(SELECT 1 FROM push_delivery p WHERE p.notification_id=n.id) ORDER BY n.created_at LIMIT 500)",notices);
        jdbc.update("DELETE FROM auth_login_attempt WHERE email IN(SELECT email FROM auth_login_attempt WHERE updated_at<CURRENT_TIMESTAMP-INTERVAL '7 days' AND (locked_until IS NULL OR locked_until<CURRENT_TIMESTAMP) LIMIT 500)");
    }
}
