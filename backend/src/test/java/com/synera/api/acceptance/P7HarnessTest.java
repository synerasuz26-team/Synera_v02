package com.synera.api.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synera.api.operations.*;
import com.synera.api.organization.BusinessAccessService;
import com.synera.api.order.*;
import com.synera.api.partner.PartnerAccessService;
import com.synera.api.security.*;
import com.synera.api.seed.SystemSeedService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Test-only CLI harness. Never packaged in the application jar; only a guarded disposable DB. */
@EnabledIfEnvironmentVariable(named="P7_HARNESS_ACTION",matches="init|nightly|perf")
class P7HarnessTest {
    static UUID id(String name){return UUID.nameUUIDFromBytes(("p7:"+name).getBytes(StandardCharsets.UTF_8));}
    static final Path OUT=Path.of("target","p7");
    @Test void execute()throws Exception{
        validateTarget(System.getenv());
        var inspection=new ProcessBuilder("docker","inspect",System.getenv("P7_DB_CONTAINER")).start();
        String raw=new String(inspection.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if(inspection.waitFor()!=0)throw new IllegalStateException("P7 container not available");
        var inspected=new ObjectMapper().readTree(raw).get(0);
        String port=inspected.path("NetworkSettings").path("Ports").path("5432/tcp").get(0).path("HostPort").asText();
        if(!System.getenv("P7_RUN_ID").equals(inspected.path("Config").path("Labels").path("synera.p7.run").asText())||!inspected.path("HostConfig").path("Tmpfs").has("/var/lib/postgresql/data")||!System.getenv("P7_TEST_DB_URL").equals("jdbc:postgresql://127.0.0.1:"+port+"/synera_p7"))throw new IllegalStateException("P7 refuses an unowned/non-ephemeral database target");
        Files.createDirectories(OUT);
        try(var context=new AnnotationConfigApplicationContext(Config.class)){
            var jdbc=context.getBean(JdbcTemplate.class);var json=context.getBean(ObjectMapper.class);
            String action=System.getenv("P7_HARNESS_ACTION");
            if(action.equals("init")){
                Flyway.configure().dataSource(context.getBean(DataSource.class)).load().migrate();
                new SystemSeedService(jdbc,json).seed();jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
                fixture(jdbc,json);
            }else{
                String marker=jdbc.queryForObject("SELECT name FROM tenant WHERE id=?",String.class,id("nsx"));
                if(!"P7 disposable test NSX".equals(marker))throw new IllegalStateException("P7 fixture marker missing");
                if(action.equals("perf")){performanceFixture(jdbc);return;}
                var settings=context.getBean(RuntimeSettings.class).get(id("nsx"));
                LocalDate day=LocalDate.parse(System.getenv("P7_BUSINESS_DAY"));
                String key=System.getenv("P7_JOB_KEY");if(key==null||!key.matches("[A-Za-z0-9_-]{1,60}"))throw new IllegalArgumentException("Invalid test job key");
                var scheduler=new BackgroundScheduler(jdbc,context.getBean(RuntimeSettings.class),context.getBean(DistributedJobRunner.class),context.getBean(ReconciliationService.class),context.getBean(ReminderJobs.class),new ExpoPushWorker(jdbc,json,""));
                String result=context.getBean(DistributedJobRunner.class).run(id("nsx"),"P7_NIGHTLY",key,()->scheduler.nightly(id("nsx"),true,day,settings));
                if(!result.equals("SUCCEEDED")&&!result.equals("SKIPPED"))throw new IllegalStateException("Nightly failed: "+result);
            }
        }
    }
    static void validateTarget(Map<String,String> env){
        String run=env.getOrDefault("P7_RUN_ID","");
        if(!env.getOrDefault("P7_TEST_DB_URL","").matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/synera_p7")||!"1".equals(env.get("P7_TEST_RUN"))||!run.matches("[a-f0-9]{12}")||!env.getOrDefault("P7_DB_CONTAINER","").equals("synera-p7-"+run))throw new IllegalStateException("P7 only accepts its own disposable localhost synera_p7 test database.");
    }
    static void tenant(JdbcTemplate jdbc,String key,String type){jdbc.update("INSERT INTO tenant(id,tenant_type,code,name) VALUES(?,?,?,?)",id(key),type,"P7-"+key,key.equals("nsx")?"P7 disposable test NSX":"P7 "+key);}
    static void user(JdbcTemplate jdbc,String key,String tenant,String role,String hash){
        jdbc.update("INSERT INTO app_users(id,tenant_id,username,full_name,email,password_hash) VALUES(?,?,?,?,?,?)",id(key),id(tenant),key,"P7 "+key,key+"@p7.example.invalid",hash);
        jdbc.update("INSERT INTO user_role(id,tenant_id,user_id,role_code) VALUES(?,?,?,?)",UUID.randomUUID(),id(tenant),id(key),role);
    }
    static void fixture(JdbcTemplate jdbc,ObjectMapper json)throws Exception{
        if(jdbc.queryForObject("SELECT count(*) FROM tenant",Long.class)!=0)throw new IllegalStateException("P7 init requires an empty disposable DB; never overwrite fixtures.");
        String password=System.getenv("P7_TEST_PASSWORD");if(password==null||password.length()<20)throw new IllegalArgumentException("Missing ephemeral fixture password");
        String hash=new com.synera.api.auth.MigratingPasswordEncoder().encode(password);
        for(String key:List.of("nsx","foreign-nsx","perf"))tenant(jdbc,key,"NSX");
        for(String key:List.of("npp","over-npp","foreign-npp"))tenant(jdbc,key,"NPP");
        tenant(jdbc,"shop","OUTLET");tenant(jdbc,"foreign-shop","OUTLET");tenant(jdbc,"platform","PLATFORM");
        Map<String,String> actors=new LinkedHashMap<>();
        for(var actor:Map.ofEntries(Map.entry("leader","NSX_LEADER"),Map.entry("leader2","NSX_LEADER"),Map.entry("asm","NSX_ASM"),Map.entry("rsm","NSX_RSM"),Map.entry("reviewer","NSX_ORDER"),Map.entry("sales","NSX_SALES"),Map.entry("accountant","NSX_ACCT"),Map.entry("admin","NSX_ADMIN")).entrySet()){
            user(jdbc,actor.getKey(),"nsx",actor.getValue(),hash);actors.put(actor.getKey(),actor.getKey()+"@p7.example.invalid");
        }
        for(var actor:Map.ofEntries(Map.entry("buyer",List.of("npp","NPP_OWNER")),Map.entry("over-buyer",List.of("over-npp","NPP_OWNER")),Map.entry("shop-owner",List.of("shop","OUTLET_OWNER")),Map.entry("foreign-buyer",List.of("foreign-npp","NPP_OWNER")),Map.entry("perf-reader",List.of("perf","NSX_LEADER"))).entrySet()){
            user(jdbc,actor.getKey(),actor.getValue().get(0),actor.getValue().get(1),hash);actors.put(actor.getKey(),actor.getKey()+"@p7.example.invalid");
        }
        jdbc.update("INSERT INTO user_role(id,tenant_id,user_id,role_code) VALUES(?,?,?,'NSX_LEADER')",UUID.randomUUID(),id("nsx"),id("accountant"));
        var seed=json.readTree(new org.springframework.core.io.ClassPathResource("seed/system-seed.json").getInputStream());
        Map<String,String> matrix=new LinkedHashMap<>();
        for(var role:seed.path("roles")){
            String code=role.path("code").asText(),type=role.path("tenantType").asText();
            // The seed's actual tenant type is read from DB, not guessed from role prefixes.
            type=jdbc.queryForObject("SELECT tenant_type FROM role WHERE code=?",String.class,code);
            String owner=switch(type){case "NSX"->"nsx";case "NPP"->"npp";case "OUTLET"->"shop";default->"platform";};
            String key="matrix-"+code.toLowerCase(Locale.ROOT);user(jdbc,key,owner,code,hash);matrix.put(code,key+"@p7.example.invalid");
        }
        for(String nsx:List.of("nsx","foreign-nsx","perf"))jdbc.update("INSERT INTO org_unit(id,tenant_id,code,name,unit_type,path) VALUES(?,?,?,'Root','REGION','/ROOT')",id(nsx+"-root"),id(nsx),"ROOT");
        for(String npp:List.of("npp","over-npp","foreign-npp")){
            String nsx=npp.equals("foreign-npp")?"foreign-nsx":"nsx";
            jdbc.update("INSERT INTO tenant_relation(id,tenant_id,nsx_tenant_id,npp_tenant_id,effective_from) VALUES(?,?,?,?,CURRENT_DATE-60)",id(npp+"-relation"),id(nsx),id(nsx),id(npp));
            jdbc.update("INSERT INTO npp_profile(tenant_id,npp_tenant_id,org_unit_id,address) VALUES(?,?,?,'P7')",id(nsx),id(npp),id(nsx+"-root"));
            jdbc.update("INSERT INTO credit_limit(id,tenant_id,npp_tenant_id,limit_amount_vnd,status,created_by,approved_by,effective_from) VALUES(?,?,?,?,'EFFECTIVE',?,?,CURRENT_TIMESTAMP-INTERVAL '30 days')",id(npp+"-credit"),id(nsx),id(npp),npp.equals("over-npp")?100L:1_000_000_000L,id("leader"),id("leader2"));
        }
        jdbc.update("INSERT INTO route(id,tenant_id,org_unit_id,code,name) VALUES(?,?,?,'P7-ROUTE','P7 route')",id("route"),id("nsx"),id("nsx-root"));
        for(String actor:List.of("leader","leader2","asm","rsm","reviewer","sales","accountant")){
            jdbc.update("INSERT INTO user_org_unit(tenant_id,user_id,org_unit_id) VALUES(?,?,?)",id("nsx"),id(actor),id("nsx-root"));
            jdbc.update("INSERT INTO user_route(tenant_id,user_id,route_id) VALUES(?,?,?)",id("nsx"),id(actor),id("route"));
        }
        for(String shop:List.of("shop","foreign-shop")){
            String nsx=shop.equals("shop")?"nsx":"foreign-nsx",npp=shop.equals("shop")?"npp":"foreign-npp";
            jdbc.update("INSERT INTO outlet(id,tenant_id,code,name,created_by_tenant_id) VALUES(?,?,?, ?,?)",id(shop+"-outlet"),id(shop),"P7-"+shop,"P7 "+shop,id(nsx));
            jdbc.update("INSERT INTO outlet_npp(id,tenant_id,outlet_id,npp_tenant_id,effective_from) VALUES(?,?,?,?,CURRENT_DATE-60)",id(shop+"-link"),id(nsx),id(shop+"-outlet"),id(npp));
        }
        jdbc.update("INSERT INTO outlet_npp(id,tenant_id,outlet_id,npp_tenant_id,effective_from) VALUES(?,?,?,?,CURRENT_DATE-60)",UUID.randomUUID(),id("nsx"),id("shop-outlet"),id("over-npp"));
        jdbc.update("INSERT INTO route_outlet(id,tenant_id,route_id,outlet_id,effective_from) VALUES(?,?,?,?,CURRENT_DATE-60)",id("route-shop"),id("nsx"),id("route"),id("shop-outlet"));
        jdbc.update("INSERT INTO visit(id,tenant_id,outlet_id,sales_user_id,route_id,planned_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",id("visit"),id("nsx"),id("shop-outlet"),id("sales"),id("route"));
        jdbc.update("INSERT INTO product(id,tenant_id,sku,name,base_unit_code) VALUES(?,?,'P7-SKU','P7 acceptance product','EA')",id("product"),id("nsx"));
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,product_id,unit_code,unit_name,base_units,is_base_unit) VALUES(?,?,?,'EA','Each',1,true)",id("unit"),id("nsx"),id("product"));
        jdbc.update("INSERT INTO price_list(id,tenant_id,code,name,status,created_by,approved_by,effective_from) VALUES(?,?,'P7-PRICE','P7 price','EFFECTIVE',?,?,CURRENT_TIMESTAMP-INTERVAL '30 days')",id("price"),id("nsx"),id("leader"),id("leader2"));
        jdbc.update("INSERT INTO price_list_line(id,tenant_id,price_list_id,product_id,unit_code,sellin_price_vnd,sellout_price_vnd) VALUES(?,?,?,?,'EA',10000,12000)",id("price-line"),id("nsx"),id("price"),id("product"));
        Map<String,Object> fixture=new LinkedHashMap<>();fixture.put("actors",actors);fixture.put("matrix",matrix);
        Map<String,String> ids=new LinkedHashMap<>();for(String key:List.of("nsx","foreign-nsx","npp","over-npp","foreign-npp","shop","shop-outlet","foreign-shop-outlet","product","route","leader","sales","buyer","over-buyer","perf","perf-reader"))ids.put(key,id(key).toString());
        fixture.put("ids",ids);fixture.put("day",LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1).toString());
        json.writerWithDefaultPrettyPrinter().writeValue(OUT.resolve("fixture.json").toFile(),fixture);
    }
    static void performanceFixture(JdbcTemplate jdbc){
        if(jdbc.queryForObject("SELECT count(*) FROM order_si WHERE tenant_id=?",Long.class,id("perf"))>0)throw new IllegalStateException("Performance fixture already exists");
        for(int i=1;i<=100;i++){
            UUID npp=id("perf-npp-"+i);jdbc.update("INSERT INTO tenant(id,tenant_type,code,name) VALUES(?,'NPP',?,?)",npp,"P7-PERF-NPP-"+i,"P7 perf NPP "+i);
            jdbc.update("INSERT INTO tenant_relation(id,tenant_id,nsx_tenant_id,npp_tenant_id,effective_from) VALUES(?,?,?,?,CURRENT_DATE-60)",UUID.randomUUID(),id("perf"),id("perf"),npp);
        }
        jdbc.update("INSERT INTO outlet(id,tenant_id,created_by_tenant_id,code,name,address) SELECT gen_random_uuid(),?,?, 'P7-PERF-'||n,'P7 outlet '||n,'District '||(n%20) FROM generate_series(1,5000) n",id("perf"),id("perf"));
        jdbc.update("INSERT INTO outlet_npp(id,tenant_id,outlet_id,npp_tenant_id,effective_from) SELECT gen_random_uuid(),?,o.id,t.id,CURRENT_DATE-60 FROM (SELECT id,row_number() OVER(ORDER BY code) AS position FROM outlet WHERE tenant_id=?) o JOIN (SELECT id,row_number() OVER(ORDER BY code) AS position FROM tenant WHERE code LIKE 'P7-PERF-NPP-%') t ON t.position=((o.position-1)%100)+1",id("perf"),id("perf"));
        for(String table:List.of("order_si","order_so")){
            if(table.equals("order_si"))jdbc.update("INSERT INTO order_si(id,tenant_id,npp_tenant_id,order_code,status,idempotency_key,created_by,total_vnd,created_at) SELECT gen_random_uuid(),?,t.id,'P7-PERF-SI-'||n,'RECEIVED','p7-perf-si-'||n,?,10000,CURRENT_TIMESTAMP-INTERVAL '2 days' FROM generate_series(1,25000) n JOIN (SELECT id,row_number() OVER(ORDER BY code) AS position FROM tenant WHERE code LIKE 'P7-PERF-NPP-%') t ON t.position=(n%100)+1",id("perf"),id("perf-reader"));
            else jdbc.update("INSERT INTO order_so(id,tenant_id,npp_tenant_id,outlet_id,order_code,status,idempotency_key,created_by,total_vnd) SELECT gen_random_uuid(),t.id,t.id,o.id,'P7-PERF-SO-'||n,'CLOSED','p7-perf-so-'||n,?,12000 FROM generate_series(1,25000) n JOIN (SELECT id,row_number() OVER(ORDER BY code) AS position FROM tenant WHERE code LIKE 'P7-PERF-NPP-%') t ON t.position=(n%100)+1 JOIN (SELECT id,row_number() OVER(ORDER BY code) AS position FROM outlet WHERE tenant_id=?) o ON o.position=(n%5000)+1",id("perf-reader"),id("perf"));
        }
        jdbc.execute("ANALYZE order_si");jdbc.execute("ANALYZE order_so");
        System.out.println("P7 performance fixture: 50000 orders and 5000 outlets ready; creating dependent lines.");
        jdbc.update("INSERT INTO product(id,tenant_id,sku,name,base_unit_code) VALUES(?,?,'P7-PERF-SKU','P7 performance product','EA')",id("perf-product"),id("perf"));
        for(String table:List.of("order_si","order_so"))jdbc.update("INSERT INTO order_line(id,tenant_id,"+table+"_id,product_id,unit_code,entered_unit_code,entered_qty,ordered_qty,confirmed_qty,shipped_qty,received_qty,unit_price_vnd,line_amount_vnd) SELECT gen_random_uuid(),o.tenant_id,o.id,?,'EA','EA',1,1,1,1,1,o.total_vnd,o.total_vnd FROM "+table+" o WHERE o.created_by=?",id("perf-product"),id("perf-reader"));
        UUID period=id("perf-period");jdbc.update("INSERT INTO period(id,tenant_id,period_type,starts_on,ends_on) VALUES(?,?,'DAY',CURRENT_DATE-2,CURRENT_DATE-2)",period,id("perf"));
        jdbc.update("INSERT INTO reconciliation(id,tenant_id,npp_tenant_id,period_id,ordered_amount_vnd,shipped_amount_vnd,received_amount_vnd) SELECT gen_random_uuid(),?,id,?,2500000,2500000,2500000 FROM tenant WHERE code LIKE 'P7-PERF-NPP-%'",id("perf"),period);
        jdbc.execute("ANALYZE");
    }
    @Configuration @EnableTransactionManagement
    @Import({RuntimeSettings.class,DistributedJobRunner.class,ReminderJobs.class,ReconciliationService.class,OrderFlowService.class,PartnerAccessService.class})
    static class Config {
        @Bean(destroyMethod="close") DataSource source(){var ds=new com.zaxxer.hikari.HikariDataSource();ds.setJdbcUrl(System.getenv("P7_TEST_DB_URL"));ds.setUsername(System.getenv("P7_TEST_DB_USER"));ds.setPassword(System.getenv("P7_TEST_DB_PASSWORD"));ds.setMaximumPoolSize(4);return ds;}
        @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
        @Bean PlatformTransactionManager transactions(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
        @Bean AuditService audit(JdbcTemplate jdbc,ObjectMapper json){return new AuditService(jdbc,json);}
        @Bean BusinessAccessService access(JdbcTemplate jdbc){return new BusinessAccessService(jdbc,new PermissionScopeResolver(jdbc));}
    }
}
