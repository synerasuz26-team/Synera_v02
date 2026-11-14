package com.synera.api.operations;

import com.synera.api.order.ReconciliationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name="app.jobs.enabled",havingValue="true",matchIfMissing=true)
public class BackgroundScheduler {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_NIGHTLY_CATCHUP_DAYS_PER_TICK=3;
    private final JdbcTemplate jdbc;private final RuntimeSettings settings;private final DistributedJobRunner runner;
    private final ReconciliationService reconciliation;private final ReminderJobs reminders;private final ExpoPushWorker push;
    public BackgroundScheduler(JdbcTemplate jdbc,RuntimeSettings settings,DistributedJobRunner runner,ReconciliationService reconciliation,ReminderJobs reminders,ExpoPushWorker push){this.jdbc=jdbc;this.settings=settings;this.runner=runner;this.reconciliation=reconciliation;this.reminders=reminders;this.push=push;}
    @Scheduled(fixedDelayString="${app.jobs.scan-ms:60000}",initialDelayString="${app.jobs.initial-delay-ms:60000}")
    public void tick(){
        var now=ZonedDateTime.now(BUSINESS_ZONE);
        for(var tenant:jdbc.queryForList("SELECT id,tenant_type FROM tenant WHERE status='ACTIVE'")){
            UUID id=(UUID)tenant.get("id");
            try {
                var config=settings.get(id);
                if(config.enabled("nightly_enabled")
                        &&!now.toLocalTime().isBefore(LocalTime.of((int)config.number("nightly_hour"),(int)config.number("nightly_minute"))))
                    catchUpNightly(id,"NSX".equals(tenant.get("tenant_type")),now.toLocalDate().minusDays(1),config);
                String slot=Long.toString(now.toEpochSecond()/60);
                if(config.enabled("reminders_enabled"))runner.run(id,"REMINDERS",slot,()->DistributedJobRunner.Result.count(reminders.scan(id,config)));
                if(config.enabled("push_enabled"))runner.run(id,"EXPO_PUSH",slot,()->DistributedJobRunner.Result.count(push.deliver(id,config)));
            }catch(Exception e){LoggerFactory.getLogger(getClass()).error("tenant scheduler failed tenant={} error={}",id,e.getClass().getSimpleName());}
        }
    }
    private void catchUpNightly(UUID tenant,boolean nsx,LocalDate dueThrough,RuntimeSettings.Values config){
        String lastKey=jdbc.query("""
                SELECT last_run_key FROM background_job_state WHERE tenant_id=? AND job_name='NIGHTLY'
                """,rs->rs.next()?rs.getString(1):null,tenant);
        LocalDate lastCompleted=lastKey==null?dueThrough.minusDays(1):LocalDate.parse(lastKey);
        for(int count=0;count<MAX_NIGHTLY_CATCHUP_DAYS_PER_TICK&&lastCompleted.isBefore(dueThrough);count++){
            LocalDate day=lastCompleted.plusDays(1);
            String result=runner.run(tenant,"NIGHTLY",day.toString(),()->nightly(tenant,nsx,day,config));
            if(!"SUCCEEDED".equals(result)){
                String persisted=jdbc.query("""
                        SELECT last_run_key FROM background_job_state
                        WHERE tenant_id=? AND job_name='NIGHTLY'
                        """,rs->rs.next()?rs.getString(1):null,tenant);
                if(persisted==null||LocalDate.parse(persisted).isBefore(day))break;
            }
            lastCompleted=day;
        }
    }
    public DistributedJobRunner.Result nightly(UUID tenant,boolean nsx,LocalDate day,RuntimeSettings.Values config){
        long snapshots=nsx?reconciliation.snapshotDay(tenant,day):0;
        long closed=nsx?reconciliation.closeReadyDays(tenant,day):0;
        long expired=jdbc.update("UPDATE attachment SET upload_status='EXPIRED' WHERE tenant_id=? AND upload_status='PENDING' AND expires_at<CURRENT_TIMESTAMP",tenant);
        long tokens=expireRefreshTokens(tenant);
        long siKeys=jdbc.update("""
                WITH expired AS (
                    SELECT id FROM order_si WHERE tenant_id=?
                      AND created_at<CURRENT_TIMESTAMP-INTERVAL '7 days'
                      AND idempotency_key NOT LIKE 'expired:%'
                    ORDER BY created_at LIMIT 500 FOR UPDATE SKIP LOCKED
                )
                UPDATE order_si o SET idempotency_key='expired:'||o.id::text
                FROM expired WHERE o.id=expired.id
                """,tenant);
        long soKeys=jdbc.update("""
                WITH expired AS (
                    SELECT id FROM order_so WHERE tenant_id=?
                      AND created_at<CURRENT_TIMESTAMP-INTERVAL '7 days'
                      AND idempotency_key NOT LIKE 'expired:%'
                    ORDER BY created_at LIMIT 500 FOR UPDATE SKIP LOCKED
                )
                UPDATE order_so o SET idempotency_key='expired:'||o.id::text
                FROM expired WHERE o.id=expired.id
                """,tenant);
        return new DistributedJobRunner.Result(snapshots+closed+expired+tokens+siKeys+soKeys,
                Map.of("snapshots",snapshots,"closedDays",closed,"expiredUploads",expired,
                        "expiredTokens",tokens,"expiredSellInKeys",siKeys,"expiredSellOutKeys",soKeys));
    }
    private long expireRefreshTokens(UUID tenant){
        long total=0;
        while(true){
            var ids=jdbc.query("""
                    SELECT id FROM refresh_token
                    WHERE tenant_id=? AND expires_at<=CURRENT_TIMESTAMP
                    ORDER BY expires_at,id LIMIT 500 FOR UPDATE SKIP LOCKED
                    """,(rs,row)->rs.getObject("id",UUID.class),tenant);
            if(ids.isEmpty())return total;
            String placeholders=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
            var args=new java.util.ArrayList<Object>(ids.size()+1);
            args.add(tenant);args.addAll(ids);
            jdbc.update("UPDATE refresh_token SET rotated_from=NULL WHERE tenant_id=? AND rotated_from IN ("+placeholders+")",args.toArray());
            jdbc.update("UPDATE refresh_token SET replaced_by=NULL WHERE tenant_id=? AND replaced_by IN ("+placeholders+")",args.toArray());
            total+=jdbc.update("DELETE FROM refresh_token WHERE tenant_id=? AND id IN ("+placeholders+")",args.toArray());
        }
    }
}
