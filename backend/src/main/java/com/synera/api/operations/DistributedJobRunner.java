package com.synera.api.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** The advisory transaction lock lives on the same DB connection as ALL job writes. */
@Service
public class DistributedJobRunner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    public DistributedJobRunner(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json){
        this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);this.json=json;
    }
    public String run(UUID tenant,String name,String key,Supplier<Result> work) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO background_job_run(id,tenant_id,job_name,run_key,status) VALUES(?,?,?,?,'RUNNING')",id,tenant,name,key);
        LoggerFactory.getLogger(getClass()).info("job started id={} tenant={} name={}",id,tenant,name);
        try {
            Result result=transaction.execute(status->{
                Boolean locked=jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",Boolean.class,tenant+":"+name);
                if(!Boolean.TRUE.equals(locked))return null;
                jdbc.update("UPDATE background_job_run SET status='ABANDONED',finished_at=CURRENT_TIMESTAMP,error_code='WORKER_LOST' WHERE tenant_id=? AND job_name=? AND status='RUNNING' AND id<>?",tenant,name,id);
                Boolean done=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM background_job_run WHERE tenant_id=? AND job_name=? AND run_key=? AND status='SUCCEEDED')",Boolean.class,tenant,name,key);
                if(Boolean.TRUE.equals(done)){
                    jdbc.update("""
                        INSERT INTO background_job_state(tenant_id,job_name,last_run_key,last_run_at)
                        SELECT tenant_id,job_name,run_key,finished_at FROM background_job_run
                        WHERE tenant_id=? AND job_name=? AND run_key=? AND status='SUCCEEDED'
                        ON CONFLICT(tenant_id,job_name) DO UPDATE SET
                            last_run_key=EXCLUDED.last_run_key,last_run_at=EXCLUDED.last_run_at,
                            updated_at=CURRENT_TIMESTAMP
                        """,tenant,name,key);
                    return null;
                }
                Result out=work.get();
                // Mark success before releasing the lock, atomically with the business changes.
                jdbc.update("UPDATE background_job_run SET status='SUCCEEDED',finished_at=CURRENT_TIMESTAMP,record_count=?,details=?::jsonb WHERE id=?",out.count(),encode(out.details()),id);
                jdbc.update("""
                    INSERT INTO background_job_state(tenant_id,job_name,last_run_key,last_run_at)
                    VALUES(?,?,?,CURRENT_TIMESTAMP)
                    ON CONFLICT(tenant_id,job_name) DO UPDATE SET
                        last_run_key=EXCLUDED.last_run_key,last_run_at=EXCLUDED.last_run_at,
                        updated_at=CURRENT_TIMESTAMP
                    """,tenant,name,key);
                return out;
            });
            if(result==null)jdbc.update("UPDATE background_job_run SET status='SKIPPED',finished_at=CURRENT_TIMESTAMP WHERE id=?",id);
            String outcome=result==null?"SKIPPED":"SUCCEEDED";
            LoggerFactory.getLogger(getClass()).info("job finished id={} status={} records={}",id,outcome,result==null?0:result.count());
            return outcome;
        }catch(Exception error){
            jdbc.update("UPDATE background_job_run SET status='FAILED',finished_at=CURRENT_TIMESTAMP,error_code=? WHERE id=?",error.getClass().getSimpleName(),id);
            LoggerFactory.getLogger(getClass()).error("job failed id={} error={}",id,error.getClass().getSimpleName());
            return "FAILED";
        }
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
    public record Result(long count,Map<String,Object> details){public static Result count(long count){return new Result(count,Map.of());}}
}
