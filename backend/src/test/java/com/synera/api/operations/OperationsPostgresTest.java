package com.synera.api.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synera.api.files.*;
import com.synera.api.order.*;
import com.synera.api.organization.BusinessAccessService;
import com.synera.api.partner.PartnerAccessService;
import com.synera.api.security.*;
import com.synera.api.seed.SystemSeedService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="P6_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/synera_p6")
class OperationsPostgresTest {
    static AnnotationConfigApplicationContext context;static JdbcTemplate jdbc;static ObjectMapper json;
    UUID tenant,user,npp,owner,outlet;Jwt jwt,ownerJwt;
    @BeforeAll static void start(){
        context=new AnnotationConfigApplicationContext(Config.class);jdbc=context.getBean(JdbcTemplate.class);json=context.getBean(ObjectMapper.class);
        Flyway.configure().dataSource(context.getBean(DataSource.class)).load().migrate();new SystemSeedService(jdbc,json).seed();
        if(System.getenv("P6_S3_ENDPOINT")!=null){
            if(!System.getenv("P6_S3_ENDPOINT").matches("http://127\\.0\\.0\\.1:[0-9]+"))throw new IllegalArgumentException("Test storage must be local");
            try(var s3=software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(URI.create(System.getenv("P6_S3_ENDPOINT"))).region(software.amazon.awssdk.regions.Region.US_EAST_1).credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("p6-local","p6-local-test-only"))).forcePathStyle(true).build()){
                s3.createBucket(r->r.bucket("synera-p6"));
                s3.putBucketCors(r->r.bucket("synera-p6").corsConfiguration(c->c.corsRules(rule->rule.allowedOrigins("http://127.0.0.1:4176").allowedMethods("PUT","GET","HEAD").allowedHeaders("*").exposeHeaders("ETag"))));
            }
        }
    }
    @AfterAll static void close(){
        if(context!=null){
            if(System.getenv("P6_E2E_PASSWORD")!=null){
                UUID tenant=stable("ui-tenant"),user=stable("ui-user");createTenant(tenant,"NSX");
                jdbc.update("INSERT INTO app_users(id,tenant_id,full_name,email,password_hash) VALUES(?,?,?,'p6-admin@example.invalid',?) ON CONFLICT(id) DO UPDATE SET password_hash=EXCLUDED.password_hash",user,tenant,"Quản trị kiểm thử P6",new com.synera.api.auth.MigratingPasswordEncoder().encode(System.getenv("P6_E2E_PASSWORD")));
                for(String role:List.of("NSX_ADMIN","NSX_LEADER","NSX_SALES"))jdbc.update("INSERT INTO user_role(id,tenant_id,user_id,role_code) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",UUID.randomUUID(),tenant,user,role);
                jdbc.update("INSERT INTO notification(id,tenant_id,recipient_id,notification_type,event_key,payload) VALUES(?,?,?,'TASK_DUE','p6-ui-task','{\"title\":\"Lời hẹn kiểm thử P6\"}') ON CONFLICT DO NOTHING",UUID.randomUUID(),tenant,user);
                UUID visit=stable("ui-visit"),outlet=stable("ui-outlet");jdbc.update("INSERT INTO outlet(id,tenant_id,code,name) VALUES(?,?,?,'Điểm bán kiểm thử P6') ON CONFLICT DO NOTHING",outlet,tenant,"P6-UI-OUTLET");
                jdbc.update("INSERT INTO visit(id,tenant_id,outlet_id,sales_user_id) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",visit,tenant,outlet,user);
            }
            context.close();
        }
    }
    @BeforeEach void fixture(){
        tenant=UUID.randomUUID();user=UUID.randomUUID();npp=UUID.randomUUID();owner=UUID.randomUUID();outlet=UUID.randomUUID();
        createTenant(tenant,"NSX");createTenant(npp,"NPP");createUser(user,tenant,"NSX_LEADER","NSX_ADMIN","NSX_SALES");createUser(owner,npp,"NPP_OWNER");
        jdbc.update("INSERT INTO outlet(id,tenant_id,code,name) VALUES(?,?,?,'Outlet')",outlet,tenant,"OUT-"+outlet);
        jdbc.update("INSERT INTO tenant_relation(id,tenant_id,nsx_tenant_id,npp_tenant_id,effective_from,created_at) VALUES(?,?,?,?,CURRENT_DATE-30,CURRENT_TIMESTAMP-INTERVAL '30 days')",UUID.randomUUID(),tenant,tenant,npp);
        jwt=jwt(user,tenant);ownerJwt=jwt(owner,npp);
    }
    @Test void databaseLockSerializesTwoWorkersAndReplayIsSkipped()throws Exception{
        var runner=context.getBean(DistributedJobRunner.class);var started=new CountDownLatch(1);var release=new CountDownLatch(1);var effects=new AtomicInteger();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->runner.run(tenant,"CONCURRENCY","one",()->{effects.incrementAndGet();started.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}return DistributedJobRunner.Result.count(7);}));
            assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(runner.run(tenant,"CONCURRENCY","one",()->{effects.incrementAndGet();return DistributedJobRunner.Result.count(99);})).isEqualTo("SKIPPED");
            release.countDown();assertThat(first.get(10,TimeUnit.SECONDS)).isEqualTo("SUCCEEDED");
        }
        assertThat(runner.run(tenant,"CONCURRENCY","one",()->{effects.incrementAndGet();return DistributedJobRunner.Result.count(99);})).isEqualTo("SKIPPED");
        assertThat(effects.get()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM background_job_run WHERE tenant_id=? AND status='SUCCEEDED' AND record_count=7 AND finished_at IS NOT NULL",tenant)).isEqualTo(1);
    }
    @Test void failedJobRollsBackBusinessWritesButKeepsFailureAndCanRetry(){
        var runner=context.getBean(DistributedJobRunner.class);UUID notification=UUID.randomUUID();
        assertThat(runner.run(tenant,"FAILURE","one",()->{notice(notification,user,tenant);throw new IllegalStateException("test secret must not be logged");})).isEqualTo("FAILED");
        assertThat(count("SELECT count(*) FROM notification WHERE id=?",notification)).isZero();
        assertThat(jdbc.queryForObject("SELECT error_code FROM background_job_run WHERE tenant_id=? AND job_name='FAILURE'",String.class,tenant)).isEqualTo("IllegalStateException");
        assertThat(runner.run(tenant,"FAILURE","one",()->{notice(notification,user,tenant);return DistributedJobRunner.Result.count(1);})).isEqualTo("SUCCEEDED");
    }
    @Test void remindersAreDeduplicatedEscalateAfterReminderAndNeverConfirmOrders(){
        var config=context.getBean(RuntimeSettings.class).get(tenant);var reminders=context.getBean(ReminderJobs.class);
        var task=context.getBean(TaskService.class).create(jwt,"Lời hẹn","",user,OffsetDateTime.now().minusMinutes(5));
        UUID interaction=UUID.randomUUID();jdbc.update("INSERT INTO interaction(id,tenant_id,outlet_id,actor_id,interaction_type,summary,occurred_at,follow_up_at) VALUES(?,?,?,?,'CALL','Appointment',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",interaction,tenant,outlet,user);
        UUID ticket=ticket();jdbc.update("UPDATE ticket SET due_at=CURRENT_TIMESTAMP+INTERVAL '10 minutes' WHERE id=?",ticket);
        UUID order=orderSo("SHIPPED");
        assertThat(reminders.scan(tenant,config)).isGreaterThanOrEqualTo(5);
        assertThat(reminders.scan(tenant,config)).isZero();
        assertThat(count("SELECT count(*) FROM notification WHERE recipient_id=? AND notification_type='ORDER_CONFIRMATION_ESCALATION'",owner)).isZero();
        jdbc.update("UPDATE notification SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 days' WHERE recipient_id=? AND notification_type='ORDER_CONFIRMATION_REMINDER'",owner);
        assertThat(reminders.scan(tenant,config)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM order_so WHERE id=?",String.class,order)).isEqualTo("SHIPPED");
        assertThat(count("SELECT count(*) FROM notification WHERE recipient_id=? AND notification_type='NPP_ORDER_CYCLE'",owner)).isEqualTo(1);
        context.getBean(TaskService.class).finish(jwt,task.id(),0);
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id=?",String.class,task.id())).isEqualTo("DONE");
    }
    @Test void inboxIsRecipientScopedAndPushDoesNotLeakAcrossTenant(){
        var inbox=context.getBean(NotificationService.class);notice(UUID.randomUUID(),owner,tenant); // normalize source tenant to recipient tenant
        assertThat(inbox.list(jwt,false,0,25).items()).isEmpty();
        var own=inbox.list(ownerJwt,false,0,25).items().getFirst();
        assertThat(inbox.read(jwt,own.id())).isZero();assertThat(inbox.read(ownerJwt,own.id())).isEqualTo(1);
        assertThat(inbox.list(ownerJwt,true,0,25).items()).isEmpty();
        assertThatThrownBy(()->inbox.list(jwt(user,npp),false,0,25)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void taskAssignmentQueuesOnlyRecipientDevicesAndRejectsCrossTenant(){
        var inbox=context.getBean(NotificationService.class);inbox.register(jwt,"p6-device","ExpoPushToken[test-device-"+user+"]");
        var tasks=context.getBean(TaskService.class);var task=tasks.create(jwt,"Assigned","",user,OffsetDateTime.now().plusHours(1));
        assertThat(count("SELECT count(*) FROM push_delivery p JOIN notification n ON n.id=p.notification_id WHERE n.payload->>'taskId'=?",task.id().toString())).isEqualTo(1);
        assertThatThrownBy(()->tasks.assign(jwt,task.id(),owner,0)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->inbox.register(jwt,"foreign-device","ExpoPushToken[test-device-"+user+"]")).isInstanceOf(IllegalArgumentException.class);
        assertThat(inbox.unregister(jwt,"p6-device")).isEqualTo(1);
    }
    @Test void settingsValidatePersistAuditAndUseOptimisticLocking(){
        var settings=context.getBean(RuntimeSettings.class);var current=settings.get(tenant);settings.validate(current.json());
        var changed=current.json().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("variance_warning_percent",12);
        var controller=context.getBean(OperationsController.class);var request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");
        controller.update(jwt,new OperationsController.SettingsRequest(changed,0,"P6 threshold"),request);
        assertThat(RuntimeSettings.number(jdbc,tenant,"variance_warning_percent")).isEqualTo(12);
        assertThatThrownBy(()->controller.update(jwt,new OperationsController.SettingsRequest(changed,0,"stale"),request)).isInstanceOf(com.synera.api.auth.ApiProblem.class);
        assertThatThrownBy(()->controller.settings(ownerJwt)).isInstanceOf(AccessDeniedException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("upload_max_bytes",Long.MAX_VALUE);
        assertThatThrownBy(()->settings.validate(changed)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id=? AND action='RUNTIME_SETTINGS_UPDATED'",tenant)).isEqualTo(1);
    }
    @Test void databaseStorageReportIsRestrictedToSysAdmin(){
        UUID platform=UUID.randomUUID(),admin=UUID.randomUUID();
        createTenant(platform,"PLATFORM");createUser(admin,platform,"SYS_ADMIN");
        var storage=new StorageController(context.getBean(ActiveIdentity.class),jdbc);
        var report=storage.storage(jwt(admin,platform));
        assertThat(report.volumeCapacityBytes()).isEqualTo(500_000_000L);
        assertThat(report.databaseBudgetBytes()).isEqualTo(300_000_000L);
        assertThat(report.volumeUsageMeasured()).isFalse();
        assertThat(report.tables()).extracting(StorageController.TableUsage::table).contains("tenant","order_si","audit_log");
        assertThatThrownBy(()->storage.storage(jwt(user,tenant)))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void staleJwtPermissionsDoNotAuthorizeAnImageUploadAfterRoleRevocation(){
        UUID ticket=ticket();jdbc.update("DELETE FROM user_role WHERE tenant_id=? AND user_id=?",tenant,user);
        Jwt forged=Jwt.withTokenValue("test").header("alg","none").subject(user.toString()).claim("tenant_id",tenant.toString()).claim("permission_scopes",Map.of("ticket:process","A")).build();
        assertThatThrownBy(()->context.getBean(AttachmentService.class).prepare(forged,"TICKET",ticket,"image.png","image/png",100)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void visitListAndPhotoScopeWorkThroughExistingPostgresServices(){
        UUID visit=UUID.randomUUID();jdbc.update("INSERT INTO visit(id,tenant_id,outlet_id,sales_user_id) VALUES(?,?,?,?)",visit,tenant,outlet,user);
        Jwt fresh=context.getBean(ActiveIdentity.class).fresh(jwt);
        var visits=context.getBean(com.synera.api.organization.VisitService.class).list(fresh,null,null,null,0,25);
        assertThat(visits.items()).hasSize(1);assertThat(visits.items().getFirst().id()).isEqualTo(visit);
        assertThat(context.getBean(AttachmentService.class).prepare(jwt,"VISIT",visit,"test.png","image/png",100)).containsKey("url");
    }
    @Test void tPlusOnePreservesHumanConfirmationsAndOnlyClosesReadyDays(){
        var reconciliation=context.getBean(ReconciliationService.class);LocalDate day=LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1);
        reconciliation.snapshotDay(tenant,day);assertThat(reconciliation.closeReadyDays(tenant,day)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM reconciliation WHERE tenant_id=?",String.class,tenant)).isEqualTo("OPEN");
        jdbc.update("UPDATE reconciliation SET status='CONFIRMED',nsx_confirmed_by=?,npp_confirmed_by=? WHERE tenant_id=?",user,owner,tenant);
        assertThat(reconciliation.closeReadyDays(tenant,day)).isEqualTo(1);assertThat(reconciliation.closeReadyDays(tenant,day)).isZero();
    }
    @Test void expoTicketsReceiptsTransientFailureAndInvalidDeviceAreHandled()throws Exception{
        var inbox=context.getBean(NotificationService.class);UUID device=inbox.register(jwt,"p6-device","ExpoPushToken[expo-worker-"+user+"]");notice(UUID.randomUUID(),user,tenant);
        var config=context.getBean(RuntimeSettings.class).get(tenant);
        var worker=new ExpoPushWorker(jdbc,json,""){@Override protected com.fasterxml.jackson.databind.JsonNode exchange(String endpoint,Object payload)throws Exception{return json.readTree(endpoint.equals("send")?"{\"data\":{\"status\":\"ok\",\"id\":\"ticket-1\"}}":"{\"data\":{\"ticket-1\":{\"status\":\"ok\"}}}");}};
        assertThat(worker.deliver(tenant,config)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT status FROM push_delivery WHERE tenant_id=?",String.class,tenant)).isEqualTo("WAITING_RECEIPT");
        jdbc.update("UPDATE push_delivery SET next_attempt_at=CURRENT_TIMESTAMP WHERE tenant_id=?",tenant);worker.deliver(tenant,config);
        assertThat(jdbc.queryForObject("SELECT status FROM push_delivery WHERE tenant_id=?",String.class,tenant)).isEqualTo("DELIVERED");
        notice(UUID.randomUUID(),user,tenant);jdbc.update("UPDATE app_users SET status='DISABLED' WHERE id=?",user);
        assertThat(worker.deliver(tenant,config)).isZero();assertThat(count("SELECT count(*) FROM push_delivery WHERE tenant_id=? AND status='CANCELLED'",tenant)).isEqualTo(1);
        jdbc.update("UPDATE app_users SET status='ACTIVE' WHERE id=?",user);
        notice(UUID.randomUUID(),user,tenant);
        var unavailable=new ExpoPushWorker(jdbc,json,""){@Override protected com.fasterxml.jackson.databind.JsonNode exchange(String endpoint,Object payload){throw new IllegalStateException("temporary");}};unavailable.deliver(tenant,config);
        assertThat(count("SELECT count(*) FROM push_delivery WHERE tenant_id=? AND status='PENDING' AND attempts=1",tenant)).isEqualTo(1);
        jdbc.update("UPDATE push_delivery SET next_attempt_at=CURRENT_TIMESTAMP WHERE tenant_id=?",tenant);
        var invalid=new ExpoPushWorker(jdbc,json,""){@Override protected com.fasterxml.jackson.databind.JsonNode exchange(String endpoint,Object payload)throws Exception{return json.readTree("{\"data\":{\"status\":\"error\",\"details\":{\"error\":\"DeviceNotRegistered\"}}}");}};invalid.deliver(tenant,config);
        assertThat(jdbc.queryForObject("SELECT active FROM push_device WHERE id=?",Boolean.class,device)).isFalse();
    }
    @Test void tPlusOneNeverClosesAnUnfinishedOrderEvenWhenReconciliationWasConfirmed(){
        var reconciliation=context.getBean(ReconciliationService.class);LocalDate day=LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1);UUID order=UUID.randomUUID();
        jdbc.update("INSERT INTO order_si(id,tenant_id,npp_tenant_id,order_code,status,idempotency_key,created_by,created_at) VALUES(?,?,?,?,'SHIPPED',?,?,?)",order,tenant,npp,"SI-"+order,order.toString(),user,day.atTime(12,0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toOffsetDateTime());
        reconciliation.snapshotDay(tenant,day);jdbc.update("UPDATE reconciliation SET status='CONFIRMED' WHERE tenant_id=?",tenant);
        assertThat(reconciliation.closeReadyDays(tenant,day)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM order_si WHERE id=?",String.class,order)).isEqualTo("SHIPPED");
    }
    @Test void nightlyCleanupOnlyExpiresStaleUploadsAndOldExpiredSessions(){
        UUID stale=UUID.randomUUID(),fresh=UUID.randomUUID();
        for(UUID id:List.of(stale,fresh))jdbc.update("INSERT INTO attachment(id,tenant_id,owner_type,owner_id,storage_key,file_name,content_type,size_bytes,uploaded_by,upload_status,expires_at) VALUES(?,?,'TICKET',?,'quarantine/test','a.png','image/png',1,?,'PENDING',?)",id,tenant,ticket(),user,OffsetDateTime.now().plusMinutes(id.equals(stale)?-5:5));
        UUID old=UUID.randomUUID(),valid=UUID.randomUUID();for(UUID id:List.of(old,valid))jdbc.update("INSERT INTO refresh_token(id,tenant_id,user_id,token_hash,expires_at,family_id,device_id) VALUES(?,?,?,?,?,?,'test')",id,tenant,user,id.toString(),OffsetDateTime.now().plusDays(id.equals(old)?-30:1),UUID.randomUUID());
        UUID oldChain=UUID.randomUUID();jdbc.update("INSERT INTO refresh_token(id,tenant_id,user_id,token_hash,expires_at,family_id,device_id,rotated_from) VALUES(?,?,?,?,CURRENT_TIMESTAMP-INTERVAL '30 days',?,'test',?)",oldChain,tenant,user,oldChain.toString(),UUID.randomUUID(),old);
        jdbc.update("UPDATE refresh_token SET replaced_by=? WHERE id=?",oldChain,old);
        var scheduler=new BackgroundScheduler(jdbc,context.getBean(RuntimeSettings.class),context.getBean(DistributedJobRunner.class),context.getBean(ReconciliationService.class),context.getBean(ReminderJobs.class),org.mockito.Mockito.mock(ExpoPushWorker.class));
        assertThat(context.getBean(DistributedJobRunner.class).run(tenant,"CLEANUP","one",()->scheduler.nightly(tenant,false,LocalDate.now().minusDays(1),context.getBean(RuntimeSettings.class).get(tenant)))).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT upload_status FROM attachment WHERE id=?",String.class,stale)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT upload_status FROM attachment WHERE id=?",String.class,fresh)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM refresh_token WHERE id=?",old)).isZero();assertThat(count("SELECT count(*) FROM refresh_token WHERE id=?",valid)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM refresh_token WHERE id=?",oldChain)).isZero();
    }
    @Test @EnabledIfEnvironmentVariable(named="P6_S3_ENDPOINT",matches="http://127\\.0\\.0\\.1:[0-9]+")
    void signedS3UploadCanonicalImageThumbnailReplayAndTenantScope()throws Exception{
        var files=context.getBean(AttachmentService.class);UUID ticket=ticket();byte[] image=png();
        var upload=files.prepare(jwt,"TICKET",ticket,"photo.png","image/png",image.length);UUID id=(UUID)upload.get("id");
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create((String)upload.get("url"))).header("Content-Type","image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(image)).build(),HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isBetween(200,299);
        assertThatThrownBy(()->files.download(jwt,id,false)).isInstanceOf(com.synera.api.auth.ApiProblem.class);
        files.complete(jwt,id);files.complete(jwt,id);
        assertThat(files.list(jwt,"TICKET",ticket)).hasSize(1);
        String url=(String)files.download(jwt,id,true).get("url");var thumb=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(thumb.statusCode()).isEqualTo(200);var dimensions=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(thumb.body()));assertThat(dimensions.getWidth()).isLessThanOrEqualTo(256);
        assertThat(jdbc.queryForObject("SELECT storage_key FROM attachment WHERE id=?",String.class,id)).startsWith("images/");
        assertThatThrownBy(()->files.download(ownerJwt,id,false)).isInstanceOf(AccessDeniedException.class);
        var bad=files.prepare(jwt,"TICKET",ticket,"fake.png","image/png",4);UUID badId=(UUID)bad.get("id");
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create((String)bad.get("url"))).header("Content-Type","image/png").PUT(HttpRequest.BodyPublishers.ofString("fake")).build(),HttpResponse.BodyHandlers.discarding());
        assertThatThrownBy(()->files.complete(jwt,badId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT upload_status FROM attachment WHERE id=?",String.class,badId)).isEqualTo("PENDING");
        jdbc.update("UPDATE attachment SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=?",badId);
        assertThatThrownBy(()->files.complete(jwt,badId)).isInstanceOf(com.synera.api.auth.ApiProblem.class);
    }
    private UUID ticket(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO ticket(id,tenant_id,ticket_code,title,created_by) VALUES(?,?,?,'Ticket',?)",id,tenant,"T-"+id,user);return id;}
    private UUID orderSo(String status){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO order_so(id,tenant_id,npp_tenant_id,outlet_id,order_code,status,idempotency_key,created_by,confirmation_due_at,expected_delivery_at) VALUES(?,?,?,?,?,?,?, ?,CURRENT_TIMESTAMP-INTERVAL '1 day',CURRENT_TIMESTAMP-INTERVAL '1 day')",id,tenant,npp,outlet,"O-"+id,status,id.toString(),user);return id;}
    static byte[] png()throws Exception{var image=new BufferedImage(1200,600,BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);return out.toByteArray();}
    private static void createTenant(UUID id,String type){jdbc.update("INSERT INTO tenant(id,tenant_type,code,name) VALUES(?,?,?,'P6 test tenant') ON CONFLICT DO NOTHING",id,type,"P6-"+id);}
    private static void createUser(UUID id,UUID tenant,String... roles){jdbc.update("INSERT INTO app_users(id,tenant_id,full_name,email,password_hash) VALUES(?,?,'P6 tester',?,'test-only-unused')",id,tenant,id+"@example.invalid");for(String role:roles)jdbc.update("INSERT INTO user_role(id,tenant_id,user_id,role_code) VALUES(?,?,?,?)",UUID.randomUUID(),tenant,id,role);}
    private static Jwt jwt(UUID user,UUID tenant){return Jwt.withTokenValue("test").header("alg","none").subject(user.toString()).claim("tenant_id",tenant.toString()).claim("device_id","p6-device").build();}
    private static void notice(UUID id,UUID recipient,UUID tenant){jdbc.update("INSERT INTO notification(id,tenant_id,recipient_id,notification_type) VALUES(?,?,?,'TASK_DUE')",id,tenant,recipient);}
    private static long count(String sql,Object... args){return jdbc.queryForObject(sql,Long.class,args);}
    static UUID stable(String key){return UUID.nameUUIDFromBytes(("p6-"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @Configuration @EnableTransactionManagement
    @Import({RuntimeSettings.class,ActiveIdentity.class,DistributedJobRunner.class,ReminderJobs.class,NotificationService.class,TaskService.class,OperationsController.class,AttachmentService.class,ReconciliationService.class,OrderFlowService.class,PartnerAccessService.class,com.synera.api.organization.VisitService.class})
    static class Config {
        @Bean static PropertySourcesPlaceholderConfigurer testProperties(){
            var properties=new java.util.Properties();
            properties.setProperty("app.files.photos-enabled",System.getenv().getOrDefault("FEATURE_PHOTOS","false"));
            var configurer=new PropertySourcesPlaceholderConfigurer();configurer.setProperties(properties);return configurer;
        }
        @Bean(destroyMethod="close") DataSource source(){var s=new com.zaxxer.hikari.HikariDataSource();s.setJdbcUrl(System.getenv("P6_TEST_DB_URL"));s.setUsername(System.getenv("P6_TEST_DB_USER"));s.setPassword(System.getenv("P6_TEST_DB_PASSWORD"));s.setMaximumPoolSize(8);return s;}
        @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
        @Bean PlatformTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
        @Bean AuditService audit(JdbcTemplate jdbc,ObjectMapper json){return new AuditService(jdbc,json);}
        @Bean BusinessAccessService access(JdbcTemplate jdbc){return new BusinessAccessService(jdbc,new PermissionScopeResolver(jdbc));}
        @Bean SellOutOrderService sellOut(){return org.mockito.Mockito.mock(SellOutOrderService.class);}
        @Bean(destroyMethod="close") S3ObjectStorage storage(){var env=new MockEnvironment().withProperty("S3_ENDPOINT",System.getenv().getOrDefault("P6_S3_ENDPOINT","http://127.0.0.1:1")).withProperty("S3_BUCKET","synera-p6").withProperty("S3_REGION","us-east-1").withProperty("S3_PATH_STYLE","true").withProperty("S3_ACCESS_KEY_ID","p6-local").withProperty("S3_SECRET_ACCESS_KEY","p6-local-test-only");return new S3ObjectStorage(env);}
    }
}
