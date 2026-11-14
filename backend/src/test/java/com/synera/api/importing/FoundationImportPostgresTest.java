package com.synera.api.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synera.api.auth.ApiProblem;
import com.synera.api.auth.AuthIdentityRepository;
import com.synera.api.security.AuditService;
import com.synera.api.seed.DemoTenantCleanup;
import com.synera.api.seed.SystemSeedService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="P5_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/synera_p5")
class FoundationImportPostgresTest {
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate jdbc;
    static FoundationImportService imports;
    Jwt jwt;UUID tenant;UUID user;String code;UUID root;UUID npp;String nppCode;
    @BeforeAll static void database() {
        context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=context.getBean(JdbcTemplate.class);imports=context.getBean(FoundationImportService.class);
        Flyway.configure().dataSource(context.getBean(DataSource.class)).locations("classpath:db/migration").load().migrate();
        new SystemSeedService(jdbc,context.getBean(ObjectMapper.class)).seed();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION p5_reject_test_product() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN IF NEW.sku LIKE 'FAIL-%' THEN RAISE EXCEPTION 'injected validation failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$
                """);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM pg_trigger WHERE tgname='p5_reject_test_product'",Long.class)==0)
            jdbc.execute("CREATE TRIGGER p5_reject_test_product BEFORE INSERT ON product FOR EACH ROW EXECUTE FUNCTION p5_reject_test_product()");
    }
    @AfterAll static void close(){
        if(context!=null) {
            String password=System.getenv("P5_E2E_PASSWORD");
            if(password!=null&&!password.isBlank()) {
                UUID uiTenant=UUID.nameUUIDFromBytes("p5-ui-tenant".getBytes(StandardCharsets.UTF_8)),uiUser=UUID.nameUUIDFromBytes("p5-ui-user".getBytes(StandardCharsets.UTF_8));
                UUID staffTenant=UUID.nameUUIDFromBytes("p5-ui-staff-tenant".getBytes(StandardCharsets.UTF_8)),staffUser=UUID.nameUUIDFromBytes("p5-ui-staff-user".getBytes(StandardCharsets.UTF_8));
                jdbc.update("INSERT INTO tenant(id,tenant_type,code,name) VALUES (?,'NSX','P5-UI-NSX','Doanh nghiệp kiểm thử P5') ON CONFLICT(id) DO NOTHING",uiTenant);
                jdbc.update("INSERT INTO tenant(id,tenant_type,code,name) VALUES (?,'NPP','P5-UI-NPP','NPP kiểm thử P5') ON CONFLICT(id) DO NOTHING",staffTenant);
                jdbc.update("INSERT INTO org_unit(id,tenant_id,code,name,unit_type,path) VALUES (?,?,'ROOT','Root','LEVEL_0','/ROOT') ON CONFLICT(tenant_id,code) DO NOTHING",UUID.randomUUID(),uiTenant);
                for(var fixture:List.of(new Object[]{uiTenant,uiUser,"p5-admin@example.invalid","NSX_ADMIN"},new Object[]{staffTenant,staffUser,"p5-staff@example.invalid","NPP_STAFF"})) {
                    jdbc.update("INSERT INTO app_users(id,tenant_id,full_name,email,password_hash) VALUES (?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET password_hash=EXCLUDED.password_hash",fixture[1],fixture[0],"Tài khoản kiểm thử P5",fixture[2],context.getBean(PasswordEncoder.class).encode(password));
                    jdbc.update("INSERT INTO user_role(id,tenant_id,user_id,role_code) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",UUID.randomUUID(),fixture[0],fixture[1],fixture[3]);
                }
            }
            context.close();
        }
    }
    @BeforeEach void actor() {
        code="QA-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        tenant=UUID.randomUUID();user=UUID.randomUUID();root=UUID.randomUUID();npp=UUID.randomUUID();nppCode="NPP-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        createTenant(tenant,"NSX",code,false);createUser(user,tenant,"NSX_ADMIN");
        jdbc.update("INSERT INTO org_unit(id,tenant_id,code,name,unit_type,path) VALUES (?,?,'ROOT','Root','LEVEL_0','/ROOT')",root,tenant);
        createTenant(npp,"NPP",nppCode,false);
        jdbc.update("INSERT INTO tenant_relation(id,tenant_id,nsx_tenant_id,npp_tenant_id) VALUES (?,?,?,?)",UUID.randomUUID(),tenant,tenant,npp);
        jwt=jwt(user,tenant);
    }
    @Test void previewDoesNotWriteThenCommitAndReplayAreIdempotent() {
        String content="sku_code,name,base_unit,case_unit,units_per_case\n sku 01 ,  Sản phẩm thật  ,hộp,thùng,48\n";
        var preview=preview(ImportKind.PRODUCTS,content);assertThat(preview.errorCount()).isZero();
        assertThat(count("SELECT COUNT(*) FROM product WHERE tenant_id=?",tenant)).isZero();
        var rows=imports.rows(jwt,preview.id(),false,0,25);
        assertThat(rows.items().getFirst().data().get("sku_code")).isEqualTo("SKU01");
        assertThat(imports.commit(jwt,preview.id(),request()).createdCount()).isEqualTo(1);
        imports.commit(jwt,preview.id(),request());
        var replay=preview(ImportKind.PRODUCTS,content);assertThat(imports.commit(jwt,replay.id(),request()).unchangedCount()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM product WHERE tenant_id=?",tenant)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM unit_conversion WHERE tenant_id=?",tenant)).isEqualTo(2);
        var update=preview(ImportKind.PRODUCTS,content.replace("Sản phẩm thật","Tên đã sửa"));
        assertThat(imports.commit(jwt,update.id(),request()).updatedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE tenant_id=?",String.class,tenant)).isEqualTo("Tên đã sửa");
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='FOUNDATION_IMPORT_COMMITTED'",tenant)).isEqualTo(3);
    }
    @Test void duplicateCodesAndMissingFieldsBlockTheEntireBatch() {
        var preview=preview(ImportKind.PRODUCTS,"sku_code,name,base_unit,case_unit,units_per_case\n sku 1 ,A,hộp,thùng,48\nSKU1,,hộp,thùng,48\n");
        assertThat(preview.errorCount()).isEqualTo(2);
        assertThatThrownBy(()->imports.commit(jwt,preview.id(),request())).isInstanceOf(ApiProblem.class);
        assertThat(count("SELECT COUNT(*) FROM product WHERE tenant_id=?",tenant)).isZero();
    }
    @Test void importedProductsCanBeReadThroughTheExistingCatalogServices() {
        var preview=preview(ImportKind.PRODUCTS,"sku_code,name,base_unit,case_unit,units_per_case\nLOOKUP-1,Imported Product,hộp,thùng,48\n");
        imports.commit(jwt,preview.id(),request());
        var catalog=context.getBean(com.synera.api.catalog.ProductService.class);
        var actorJwt=Jwt.withTokenValue("test").header("alg","none").subject(user.toString()).claim("tenant_id",tenant.toString()).claim("tenant_type","NSX").build();
        var products=catalog.list(actorJwt,"LOOKUP-1","ACTIVE",0,25);
        assertThat(products.totalElements()).isEqualTo(1);
        assertThat(catalog.get(actorJwt,products.items().getFirst().id()).units()).hasSize(2);
    }
    @Test void databaseFailureOnSecondRowRollsBackFirstRowAndBatchStatus() {
        var preview=preview(ImportKind.PRODUCTS,"sku_code,name,base_unit,case_unit,units_per_case\nGOOD-1,A,hộp,thùng,48\nFAIL-1,B,hộp,thùng,48\n");
        assertThat(preview.errorCount()).isZero();
        assertThatThrownBy(()->imports.commit(jwt,preview.id(),request())).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT COUNT(*) FROM product WHERE tenant_id=?",tenant)).isZero();
        assertThat(count("SELECT COUNT(*) FROM foundation_import_identity WHERE tenant_id=?",tenant)).isZero();
        assertThat(imports.get(jwt,preview.id()).status()).isEqualTo("PREVIEWED");
    }
    @Test void stalePreviewCannotOverwriteConcurrentChanges() {
        imports.commit(jwt,preview(ImportKind.PRODUCTS,"sku_code,name,base_unit,case_unit,units_per_case\nSKU-1,A,hộp,thùng,48\n").id(),request());
        var preview=preview(ImportKind.PRODUCTS,"sku_code,name,base_unit,case_unit,units_per_case\nSKU-1,B,hộp,thùng,48\n");
        jdbc.update("UPDATE product SET name='Concurrent',version=version+1 WHERE tenant_id=?",tenant);
        assertThatThrownBy(()->imports.commit(jwt,preview.id(),request())).isInstanceOf(ApiProblem.class);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE tenant_id=?",String.class,tenant)).isEqualTo("Concurrent");
    }
    @Test void tenantSpoofingAndReadingAnotherUsersBatchAreRejected() {
        var preview=preview(ImportKind.PRODUCTS,"sku_code,name,base_unit,case_unit,units_per_case\nSKU-1,A,hộp,thùng,48\n");
        UUID other=UUID.randomUUID(),otherUser=UUID.randomUUID();createTenant(other,"NSX","OTHER-"+other,false);createUser(otherUser,other,"NSX_ADMIN");
        assertThatThrownBy(()->imports.get(jwt(otherUser,other),preview.id())).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(()->imports.get(jwt(user,other),preview.id())).isInstanceOf(AccessDeniedException.class);
        UUID staff=UUID.randomUUID();createUser(staff,npp,"NPP_STAFF");
        assertThatThrownBy(()->imports.preview(jwt(staff,npp),ImportKind.PRODUCTS,csv("sku_code,name\nA,B"),"test",request())).isInstanceOf(AccessDeniedException.class);
        var bad=imports.preview(jwt(staff,npp),ImportKind.INVENTORY,csv("npp_code,sku_code,stock_case,updated_at\nFOREIGN,SKU-1,1,2026-11-13\n"),"test",request());
        assertThat(bad.errorCount()).isEqualTo(1);
    }
    @Test void newUserPasswordsAreHashedRedactedAndRoleEscalationIsRejected() {
        String source="username,full_name,role_code,tenant_code,password,email\nworker,Worker,NSX_SALES,"+code+",Test-only-password-2026,worker."+user+"@example.invalid\n";
        var preview=preview(ImportKind.USERS,source);assertThat(preview.errorCount()).isZero();
        var row=imports.rows(jwt,preview.id(),false,0,25).items().getFirst();
        assertThat(row.data()).doesNotContainKey("password").doesNotContainKey("_password_hash");
        assertThat(jdbc.queryForObject("SELECT row_data::text FROM foundation_import_row WHERE batch_id=?",String.class,preview.id())).doesNotContain("Test-only-password-2026").contains("$2a$");
        imports.commit(jwt,preview.id(),request());
        String passwordHash=jdbc.queryForObject("SELECT password_hash FROM app_users WHERE tenant_id=? AND username='worker'",String.class,tenant);
        assertThat(context.getBean(PasswordEncoder.class).matches("Test-only-password-2026",passwordHash)).isTrue();
        assertThat(preview(ImportKind.USERS,source.replace("NSX_SALES","NSX_LEADER")).errorCount()).isEqualTo(1);
        String self="username,full_name,role_code,tenant_code,password,email\nself,Me,NSX_SALES,"+code+",Test-only-password-2026,"+user+"@example.invalid\n";
        assertThat(preview(ImportKind.USERS,self).errorCount()).isEqualTo(1);
    }
    @Test void missingReferencesCyclesAndMalformedIntegersAreReportedPerRow() {
        assertThat(preview(ImportKind.NPP,"npp_code,name,org_unit_code\nNEW,NPP,MISSING\n").errorCount()).isEqualTo(1);
        assertThat(preview(ImportKind.ORG_UNITS,"org_code,parent_code,name,level\nA,B,First,1\nB,A,Second,1\n").errorCount()).isEqualTo(2);
        assertThat(preview(ImportKind.PRODUCTS,"sku_code,name,base_unit,case_unit,units_per_case\nSKU-1,A,hộp,thùng,1.5\n").errorCount()).isEqualTo(1);
        var valid=preview(ImportKind.ORG_UNITS,"org_code,parent_code,name,level\nCHILD,PARENT,Child,1\nPARENT,,Parent,0\n");
        assertThat(valid.errorCount()).isZero();imports.commit(jwt,valid.id(),request());
        assertThat(jdbc.queryForObject("SELECT path FROM org_unit WHERE tenant_id=? AND code='CHILD'",String.class,tenant)).isEqualTo("/PARENT/CHILD");
    }
    @Test void importsNppMetadataAndRouteMcpUsingWorkbookSheets()throws Exception {
        String newCode="NEW-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        String source="npp_code,name,org_unit_code,address\n"+newCode+", New NPP ,ROOT,  Địa chỉ thật  \n";
        var distributor=preview(ImportKind.NPP,source);assertThat(distributor.errorCount()).isZero();imports.commit(jwt,distributor.id(),request());
        assertThat(jdbc.queryForObject("SELECT address FROM npp_profile WHERE tenant_id=?",String.class,tenant)).isEqualTo("Địa chỉ thật");
        assertThat(imports.commit(jwt,preview(ImportKind.NPP,source).id(),request()).unchangedCount()).isEqualTo(1);
        var outletBatch=preview(ImportKind.OUTLETS,"outlet_code,name,address,npp_code\nOL-1,Store,Address,"+nppCode+"\n");imports.commit(jwt,outletBatch.id(),request());
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var routes=workbook.createSheet("routes");
            var routeHeaders=routes.createRow(0);String[] headers={"route_code","name","org_unit_code"};
            for(int i=0;i<headers.length;i++)routeHeaders.createCell(i).setCellValue(headers[i]);
            var routeRow=routes.createRow(1);String[] values={"ROUTE-1","Route","ROOT"};for(int i=0;i<values.length;i++)routeRow.createCell(i).setCellValue(values[i]);
            var schedule=workbook.createSheet("route_outlets");var scheduleHeaders=schedule.createRow(0);String[] sh={"route_code","outlet_code","visit_order","mcp_weekday","mcp_weeks"};for(int i=0;i<sh.length;i++)scheduleHeaders.createCell(i).setCellValue(sh[i]);
            var scheduleRow=schedule.createRow(1);String[] sv={"ROUTE-1","OL-1","1","Thứ 5","1,2"};for(int i=0;i<sv.length;i++)scheduleRow.createCell(i).setCellValue(sv[i]);
            workbook.write(output);
            var file=new MockMultipartFile("file","routes.xlsx","application/octet-stream",output.toByteArray());
            var preview=imports.preview(jwt,ImportKind.ROUTES,file,"test",request());assertThat(preview.errorCount()).isZero();imports.commit(jwt,preview.id(),request());
            assertThat(count("SELECT COUNT(*) FROM mcp_schedule WHERE tenant_id=? AND day_of_week=4",tenant)).isEqualTo(2);
            assertThat(imports.commit(jwt,imports.preview(jwt,ImportKind.ROUTES,file,"test",request()).id(),request()).unchangedCount()).isEqualTo(2);
        }
    }
    @Test void outletsNormalizeContactsWarnAboutDuplicatesAndRemainPending() {
        String content="outlet_code,name,address,contact_name,contact_phone,npp_code\nOL-1, Cửa hàng ABC ,12 Đường A,Owner,0901234567,"+nppCode+"\nOL-2,Cửa hàng ABC,12 Duong A,Owner,+84 901 234 567,"+nppCode+"\n";
        var preview=preview(ImportKind.OUTLETS,content);assertThat(preview.errorCount()).isZero();assertThat(preview.warningCount()).isEqualTo(2);
        imports.commit(jwt,preview.id(),request());
        assertThat(count("SELECT COUNT(*) FROM outlet WHERE created_by_tenant_id=? AND status='PENDING'",tenant)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT phone FROM contact WHERE tenant_id=?",String.class,tenant)).containsOnly("+84901234567");
        assertThat(imports.commit(jwt,preview(ImportKind.OUTLETS,content).id(),request()).unchangedCount()).isEqualTo(2);
    }
    @Test void openingInventoryConvertsToBaseUnitsWithoutDuplicateSnapshots() {
        imports.commit(jwt,preview(ImportKind.PRODUCTS,"sku_code,name,base_unit,case_unit,units_per_case\nSKU-1,A,hộp,thùng,48\n").id(),request());
        String source="npp_code,sku_code,stock_case,updated_at\n"+nppCode+",SKU-1,10,2026-11-13T08:00:00+07:00\n";
        var preview=preview(ImportKind.INVENTORY,source);assertThat(preview.errorCount()).isZero();imports.commit(jwt,preview.id(),request());
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_npp WHERE npp_tenant_id=?",Long.class,npp)).isEqualTo(480);
        assertThat(imports.commit(jwt,preview(ImportKind.INVENTORY,source).id(),request()).unchangedCount()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM inventory_npp WHERE npp_tenant_id=?",npp)).isEqualTo(1);
        jdbc.update("INSERT INTO period(id,tenant_id,period_type,starts_on,ends_on,status) VALUES (?,?,'DAY','2026-11-13','2026-11-13','CLOSED')",UUID.randomUUID(),tenant);
        assertThat(preview(ImportKind.INVENTORY,source.replace("10,2026-11-13T08:00:00+07:00","11,2026-11-13T01:00:00+07:00")).errorCount()).isEqualTo(1);
    }
    @Test void openingCreditStaysDraftAndCannotOverwriteApprovedLimit() {
        String source="npp_code,credit_limit_vnd,debt_current_vnd,credit_remaining_vnd\n"+nppCode+",1000000,200000,800000\n";
        var preview=preview(ImportKind.CREDIT,source);assertThat(preview.errorCount()).isZero();imports.commit(jwt,preview.id(),request());
        assertThat(jdbc.queryForObject("SELECT status FROM credit_limit WHERE tenant_id=?",String.class,tenant)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT amount_vnd FROM invoice WHERE tenant_id=?",Long.class,tenant)).isEqualTo(200000);
        assertThat(imports.commit(jwt,preview(ImportKind.CREDIT,source).id(),request()).unchangedCount()).isEqualTo(1);
        assertThat(imports.submitCredit(jwt,preview.id(),request()).get("submittedCount")).isEqualTo(1);
        assertThat(imports.submitCredit(jwt,preview.id(),request()).get("submittedCount")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM credit_limit WHERE tenant_id=?",String.class,tenant)).isEqualTo("PENDING_APPROVAL");
        jdbc.update("UPDATE credit_limit SET status='EFFECTIVE',version=version+1 WHERE tenant_id=?",tenant);
        var changed=preview(ImportKind.CREDIT,source.replace("1000000,200000,800000","1100000,200000,900000"));
        assertThatThrownBy(()->imports.commit(jwt,changed.id(),request())).isInstanceOf(ApiProblem.class);
        assertThat(jdbc.queryForObject("SELECT limit_amount_vnd FROM credit_limit WHERE tenant_id=?",Long.class,tenant)).isEqualTo(1000000);
    }
    @Test void refreshTokensCanBeCreatedRotatedAndRevokedUsingRealPostgresTimestamps() {
        var refresh=context.getBean(com.synera.api.auth.RefreshTokenService.class);
        var identity=context.getBean(AuthIdentityRepository.class).findById(user).orElseThrow();
        var session=refresh.createSession(identity,"p5-device");
        assertThat(count("SELECT COUNT(*) FROM refresh_token WHERE user_id=?",user)).isEqualTo(1);
        var replacement=refresh.rotate(session.refreshToken(),"p5-device");
        assertThat(replacement.refreshToken()).isNotEqualTo(session.refreshToken());
        assertThat(count("SELECT COUNT(*) FROM refresh_token WHERE user_id=? AND revoked_at IS NOT NULL",user)).isEqualTo(1);
        refresh.logout(replacement.refreshToken(),user,"p5-device");
        assertThat(count("SELECT COUNT(*) FROM refresh_token WHERE user_id=? AND revoked_at IS NOT NULL",user)).isEqualTo(2);
    }
    @Test void demoSourceCannotEnterRealTenantAndCleanupPreservesRealData()throws Exception {
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var marker=workbook.createSheet("tenants");marker.createRow(0).createCell(0).setCellValue("is_demo");marker.createRow(1).createCell(0).setCellValue(1);
            var sheet=workbook.createSheet("products");var header=sheet.createRow(0);var data=sheet.createRow(1);
            String[] keys={"sku_code","name","base_unit","case_unit","units_per_case"},values={"DEMO-1","Demo","hộp","thùng","48"};
            for(int i=0;i<keys.length;i++){header.createCell(i).setCellValue(keys[i]);data.createCell(i).setCellValue(values[i]);}
            workbook.write(output);
            assertThatThrownBy(()->imports.preview(jwt,ImportKind.PRODUCTS,new MockMultipartFile("file","demo.xlsx","application/octet-stream",output.toByteArray()),"test",request())).isInstanceOf(ApiProblem.class);
        }
        UUID demo=UUID.randomUUID(),demoUser=UUID.randomUUID();createTenant(demo,"NSX","DEMO-"+demo,true);createUser(demoUser,demo,"NSX_ADMIN");
        imports.commit(jwt,preview(ImportKind.PRODUCTS,"sku_code,name,base_unit,case_unit,units_per_case\nREAL-1,Real,hộp,thùng,48\n").id(),request());
        var demoJwt=jwt(demoUser,demo);
        var demoBatch=imports.preview(demoJwt,ImportKind.PRODUCTS,csv("sku_code,name,base_unit,case_unit,units_per_case\nDEMO-1,Demo,hộp,thùng,48\n"),"test",request());
        imports.commit(demoJwt,demoBatch.id(),request());
        var cleaner=context.getBean(DemoTenantCleanup.class);
        Map<String,String> env=Map.of("APP_ENV","dev","DB_ENVIRONMENT","dev","DB_URL",System.getenv("P5_TEST_DB_URL"),"PURGE_DEMO_CONFIRM","DELETE_DEMO_ONLY");
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->{
            jdbc.update("INSERT INTO tenant_relation(id,tenant_id,nsx_tenant_id,npp_tenant_id) VALUES (?,?,?,?)",UUID.randomUUID(),tenant,tenant,demo);
            assertThatThrownBy(()->cleaner.purge(env)).isInstanceOf(IllegalStateException.class).hasMessageContaining("tham chiếu chéo");status.setRollbackOnly();return null;
        });
        assertThat(count("SELECT COUNT(*) FROM tenant WHERE id=?",demo)).isEqualTo(1);
        assertThat(cleaner.purge(env).get("tenant")).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tenant WHERE id=?",tenant)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM product WHERE tenant_id=?",tenant)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM product WHERE tenant_id=?",demo)).isZero();
    }
    private FoundationImportService.BatchView preview(ImportKind kind,String content){return imports.preview(jwt,kind,csv(content),"Kiểm thử P5",request());}
    private static MockMultipartFile csv(String content){return new MockMultipartFile("file","source.csv","text/csv",content.getBytes(StandardCharsets.UTF_8));}
    private static MockHttpServletRequest request(){var request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");return request;}
    private static Jwt jwt(UUID user,UUID tenant){return Jwt.withTokenValue("test").header("alg","none").subject(user.toString()).claim("tenant_id",tenant.toString()).build();}
    private static long count(String sql,Object... args){return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args));}
    private static void createTenant(UUID id,String type,String code,boolean demo){jdbc.update("INSERT INTO tenant(id,tenant_type,code,name,is_demo) VALUES (?,?,?,?,?)",id,type,code,code,demo);}
    private static void createUser(UUID id,UUID tenant,String role){jdbc.update("INSERT INTO app_users(id,tenant_id,full_name,email,password_hash) VALUES (?,?,?,?,'test-only-unused')",id,tenant,"QA",id+"@example.invalid");jdbc.update("INSERT INTO user_role(id,tenant_id,user_id,role_code) VALUES (?,?,?,?)",UUID.randomUUID(),tenant,id,role);}

    @Configuration @EnableTransactionManagement static class Config {
        @Bean(destroyMethod="close") DataSource dataSource(){var source=new com.zaxxer.hikari.HikariDataSource();source.setJdbcUrl(System.getenv("P5_TEST_DB_URL"));source.setUsername(System.getenv("P5_TEST_DB_USER"));source.setPassword(System.getenv("P5_TEST_DB_PASSWORD"));source.setMaximumPoolSize(4);source.setPoolName("p5-validation");return source;}
        @Bean JdbcTemplate jdbc(DataSource dataSource){return new JdbcTemplate(dataSource);}
        @Bean PlatformTransactionManager transactions(DataSource dataSource){return new DataSourceTransactionManager(dataSource);}
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
        @Bean PasswordEncoder passwords(){return new com.synera.api.auth.MigratingPasswordEncoder();}
        @Bean AuthIdentityRepository identities(JdbcTemplate jdbc){return new AuthIdentityRepository(jdbc);}
        @Bean AuditService audit(JdbcTemplate jdbc,ObjectMapper json){return new AuditService(jdbc,json);}
        @Bean FoundationImportPolicy policy(AuthIdentityRepository identities,JdbcTemplate jdbc){return new FoundationImportPolicy(identities,jdbc);}
        @Bean FoundationWorkbookReader reader(){return new FoundationWorkbookReader();}
        @Bean FoundationImportEngine engine(JdbcTemplate jdbc,PasswordEncoder passwords){return new FoundationImportEngine(jdbc,passwords);}
        @Bean FoundationImportWriter writer(JdbcTemplate jdbc){return new FoundationImportWriter(jdbc);}
        @Bean FoundationImportService service(JdbcTemplate jdbc,ObjectMapper json,FoundationImportPolicy policy,FoundationWorkbookReader reader,FoundationImportEngine engine,FoundationImportWriter writer,AuditService audit){return new FoundationImportService(jdbc,json,policy,reader,engine,writer,audit);}
        @Bean DemoTenantCleanup cleanup(JdbcTemplate jdbc,ObjectMapper json){return new DemoTenantCleanup(jdbc,json);}
        @Bean com.synera.api.catalog.CatalogAccessService catalogAccess(JdbcTemplate jdbc){return new com.synera.api.catalog.CatalogAccessService(jdbc,new com.synera.api.security.PermissionScopeResolver(jdbc));}
        @Bean com.synera.api.catalog.ProductService products(JdbcTemplate jdbc,com.synera.api.catalog.CatalogAccessService access,AuditService audit){return new com.synera.api.catalog.ProductService(jdbc,access,audit);}
        @Bean java.time.Clock clock(){return java.time.Clock.systemUTC();}
        @Bean com.synera.api.auth.AccessTokenService accessTokens(java.time.Clock clock){
            var key=new javax.crypto.spec.SecretKeySpec((UUID.randomUUID().toString()+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),"HmacSHA256");
            return new com.synera.api.auth.AccessTokenService(new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key)),clock);
        }
        @Bean com.synera.api.auth.RefreshTokenService refreshTokens(JdbcTemplate jdbc,AuthIdentityRepository identities,com.synera.api.auth.AccessTokenService accessTokens,java.time.Clock clock){return new com.synera.api.auth.RefreshTokenService(jdbc,identities,accessTokens,30,clock);}
    }
}
