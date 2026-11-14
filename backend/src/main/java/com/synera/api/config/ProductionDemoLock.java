package com.synera.api.config;

import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @Profile("production") @Order(0)
public class ProductionDemoLock implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public ProductionDemoLock(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override @Transactional public void run(ApplicationArguments args){
        jdbc.update("INSERT INTO audit_log(id,tenant_id,action,resource_type,resource_id,reason) SELECT gen_random_uuid(),u.tenant_id,'PRODUCTION_DEMO_DISABLED','app_users',u.id,'Production startup guard' FROM app_users u JOIN tenant t ON t.id=u.tenant_id WHERE t.is_demo AND u.status<>'DISABLED'");
        jdbc.update("UPDATE app_users u SET status='DISABLED',updated_at=CURRENT_TIMESTAMP,version=version+1 FROM tenant t WHERE t.id=u.tenant_id AND t.is_demo AND u.status<>'DISABLED'");
        jdbc.update("UPDATE tenant SET status='SUSPENDED',updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE is_demo AND status='ACTIVE'");
        jdbc.update("UPDATE refresh_token r SET revoked_at=CURRENT_TIMESTAMP FROM tenant t WHERE r.tenant_id=t.id AND t.is_demo AND r.revoked_at IS NULL");
        jdbc.update("UPDATE push_device d SET active=false FROM tenant t WHERE d.tenant_id=t.id AND t.is_demo AND d.active");
    }
}
