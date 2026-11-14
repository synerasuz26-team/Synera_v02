package com.synera.api.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.env.Environment;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DemoSeedService {
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(DemoSeedService.class);
    private static final String NSX = "SUOI_VIET";
    private static final String MINH_PHAT = "MINH_PHAT";
    private static final String DEMO_SEED_PASSWORD_KEY = "SEED_DEMO_PASSWORD";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final Map<String, UUID> tenantIds = new HashMap<>();
    private final Map<String, UUID> userIds = new HashMap<>();
    private final Map<String, UUID> orgUnitIds = new HashMap<>();
    private final Map<String, UUID> routeIds = new HashMap<>();
    private final Map<String, UUID> outletIds = new HashMap<>();
    private final Map<String, UUID> productIds = new HashMap<>();
    private final Map<String, UUID> orderIds = new HashMap<>();
    private final Map<String, UUID> ticketIds = new HashMap<>();
    private final Map<String, Integer> caseUnits = new HashMap<>();

    public DemoSeedService(JdbcTemplate jdbc, ObjectMapper objectMapper, PasswordEncoder passwordEncoder, Environment environment) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Transactional
    public void seed(String demoPassword) {
        Map<String,String> target=new HashMap<>();
        for(String key:Set.of("APP_ENV","DB_ENVIRONMENT","DB_URL"))target.put(key,environment.getProperty(key,""));
        DatabaseTaskGuard.requireDemoTarget(target);
        if (demoPassword == null || demoPassword.isBlank()) {
            throw new IllegalStateException("Thiếu biến môi trường " + DEMO_SEED_PASSWORD_KEY + ".");
        }
        tenantIds.clear();
        userIds.clear();
        orgUnitIds.clear();
        routeIds.clear();
        outletIds.clear();
        productIds.clear();
        orderIds.clear();
        ticketIds.clear();
        caseUnits.clear();
        JsonNode seed = readSeed();
        runStage("tenants", () -> seedTenants(seed.path("tenants")));
        runStage("users", () -> seedUsers(seed.path("users"), demoPassword));
        runStage("org_units", () -> seedOrgUnits(seed.path("orgUnits")));
        runStage("outlets", () -> seedOutlets(seed.path("outlets")));
        runStage("routes", () -> seedRoutes(seed.path("routes")));
        runStage("relations", () -> seedRelations(seed.path("tenantRelations"), seed.path("outletNpp")));
        runStage("products", () -> seedProducts(seed.path("products")));
        runStage("price_list", () -> seedPriceList(seed.path("products")));
        runStage("promotions", () -> seedPromotions(seed.path("promotions")));
        runStage("periods", () -> seedPeriods(seed.path("closeSchedule")));
        runStage("orders", () -> seedOrders(seed.path("orders")));
        runStage("daily_series", () -> seedDailySeries(seed.path("dailySellIn30d"), seed.path("dailySellOut30d")));
        runStage("inventory", () -> seedInventory(seed.path("products")));
        runStage("demand_plans", () -> seedDemandPlans(seed.path("demandPlan")));
        runStage("visits", () -> seedVisits(seed.path("visits")));
        runStage("tickets", () -> seedTickets(seed.path("tickets")));
        runStage("appointments", () -> seedAppointments(seed.path("appointments")));
        runStage("finance", () -> seedFinance(seed.path("financeRows")));
        runStage("targets", () -> seedTarget(seed.path("dashboard")));
        runStage("audit_log", () -> seedAuditLog(seed.path("actionLog")));
        runStage("tenant_config", () -> seedTenantConfig(seed));
    }

    private void runStage(String name, Runnable stage) {
        try {
            stage.run();
        } catch (RuntimeException exception) {
            LOGGER.error("demo_seed_stage_failure stage={} exceptionType={}",
                    name, exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private JsonNode readSeed() {
        try (InputStream input = new ClassPathResource("seed/demo-seed.json").getInputStream()) {
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Không đọc được dữ liệu seed demo.", exception);
        }
    }

    private void seedTenants(JsonNode rows) {
        rows.forEach(row -> {
            String code = text(row, "code");
            UUID id = id("tenant:" + code);
            UUID existing=jdbc.query("SELECT id FROM tenant WHERE code=?",rs->rs.next()?rs.getObject("id",UUID.class):null,code);
            if(existing!=null&&!existing.equals(id))throw new IllegalStateException("Không ghi đè tenant thật trùng mã với demo: "+code);
            tenantIds.put(code, id);
            jdbc.update("""
                    INSERT INTO tenant (id, tenant_type, code, name, status, is_demo)
                    VALUES (?, ?, ?, ?, 'ACTIVE', TRUE)
                    ON CONFLICT (code) DO UPDATE SET
                        tenant_type = EXCLUDED.tenant_type,
                        name = EXCLUDED.name,
                        status = EXCLUDED.status,
                        is_demo = TRUE
                    """, id, text(row, "type"), code, text(row, "name"));
        });
    }

    private void seedUsers(JsonNode rows, String demoPassword) {
        String passwordHash = passwordEncoder.encode(demoPassword);
        rows.forEach(row -> {
            String email = text(row, "email");
            String tenantCode = text(row, "tenant");
            UUID preferredUserId = id("user:" + email);
            UUID tenantId = require(tenantIds, tenantCode, "tenant");
            UserCredential account = jdbc.query(
                    "SELECT id, tenant_id, password_hash FROM app_users WHERE email = ?",
                    resultSet -> resultSet.next() ? mapUserCredential(resultSet) : null,
                    email);
            if (account == null) {
                jdbc.update("""
                    INSERT INTO app_users (id, tenant_id, full_name, email, password_hash, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    ON CONFLICT (email) DO NOTHING
                    """, preferredUserId, tenantId, text(row, "name"), email, passwordHash);
                account = jdbc.query(
                        "SELECT id, tenant_id, password_hash FROM app_users WHERE email = ?",
                        resultSet -> resultSet.next() ? mapUserCredential(resultSet) : null,
                        email);
            }
            if (account == null) {
                throw new IllegalStateException("Không thể tạo tài khoản demo: " + email);
            }
            UUID userId = account.id();
            if(!tenantId.equals(account.tenantId())||!preferredUserId.equals(account.id()))
                throw new IllegalStateException("Không ghi đè tài khoản thật trùng email với dữ liệu demo.");
            userIds.put(email, userId);
            if (!passwordEncoder.matches(demoPassword, account.passwordHash())) {
                jdbc.update("""
                        UPDATE app_users
                        SET tenant_id = ?, full_name = ?, password_hash = ?, status = 'ACTIVE',
                            updated_at = CURRENT_TIMESTAMP, version = version + 1
                        WHERE id = ?
                        """, tenantId, text(row, "name"), passwordHash, userId);
            } else {
                jdbc.update("""
                        UPDATE app_users
                        SET tenant_id = ?, full_name = ?, status = 'ACTIVE',
                            updated_at = CURRENT_TIMESTAMP, version = version + 1
                        WHERE id = ?
                          AND (tenant_id IS DISTINCT FROM ? OR full_name IS DISTINCT FROM ? OR status <> 'ACTIVE')
                        """, tenantId, text(row, "name"), userId, tenantId, text(row, "name"));
            }
            jdbc.update("""
                    INSERT INTO user_role (id, tenant_id, user_id, role_code)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (tenant_id, user_id, role_code) DO NOTHING
                    """,
                    id("user-role:" + email + ":" + text(row, "role")),
                    tenantId, userId, text(row, "role"));
        });
    }

    private UserCredential mapUserCredential(ResultSet resultSet) throws SQLException {
        return new UserCredential(resultSet.getObject("id", UUID.class), resultSet.getObject("tenant_id",UUID.class), resultSet.getString("password_hash"));
    }

    private record UserCredential(UUID id, UUID tenantId, String passwordHash) {
    }

    private void seedOrgUnits(JsonNode rows) {
        rows.forEach(row -> {
            String code = text(row, "code");
            String parentCode = nullableText(row, "parent");
            UUID parentId = parentCode == null ? null : require(orgUnitIds, parentCode, "org unit");
            UUID unitId = id("org-unit:" + code);
            orgUnitIds.put(code, unitId);
            jdbc.update("""
                    INSERT INTO org_unit (id, tenant_id, parent_id, code, name, unit_type, path, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    ON CONFLICT (tenant_id, code) DO UPDATE SET
                        parent_id = EXCLUDED.parent_id,
                        name = EXCLUDED.name,
                        unit_type = EXCLUDED.unit_type,
                        path = EXCLUDED.path,
                        status = EXCLUDED.status
                    """,
                    unitId, tenantIds.get(NSX), parentId, code, text(row, "name"),
                    text(row, "type"), text(row, "path"));
        });
        UUID rsmId = require(userIds, "le.quang@demo.synera.local", "user");
        UUID asmId = require(userIds, "tran.my@demo.synera.local", "user");
        UUID salesId = require(userIds, "tran.ha@demo.synera.local", "user");
        assignUserOrgUnit("le.quang@demo.synera.local", rsmId, "MIEN_NAM");
        assignUserOrgUnit("tran.my@demo.synera.local", asmId, "TP_HCM");
        assignUserOrgUnit("tran.ha@demo.synera.local", salesId, "QUAN_3");
        assignUserOrgUnit("nguyen.an@demo.synera.local",
                require(userIds, "nguyen.an@demo.synera.local", "user"), "TP_HCM");
        assignUserOrgUnit("le.phuc@demo.synera.local",
                require(userIds, "le.phuc@demo.synera.local", "user"), "TP_HCM");
    }

    private void assignUserOrgUnit(String email, UUID userId, String unitCode) {
        jdbc.update("""
                INSERT INTO user_org_unit (tenant_id, user_id, org_unit_id)
                VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, user_id, org_unit_id) DO NOTHING
                """, tenantIds.get(NSX), userId, orgUnitIds.get(unitCode));
    }

    private void seedOutlets(JsonNode rows) {
        rows.forEach(row -> {
            String tenantCode = text(row, "tenant");
            String code = text(row, "code");
            UUID outletId = id("outlet:" + tenantCode);
            outletIds.put(tenantCode, outletId);
            jdbc.update("""
                    INSERT INTO outlet (id, tenant_id, code, name, address, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    ON CONFLICT (tenant_id, code) DO UPDATE SET
                        name = EXCLUDED.name,
                        address = EXCLUDED.address,
                        status = EXCLUDED.status
                    """, outletId, tenantIds.get(tenantCode), code, text(row, "name"), text(row, "address"));
            jdbc.update("""
                    INSERT INTO contact (id, tenant_id, outlet_id, full_name, phone, is_primary)
                    VALUES (?, ?, ?, ?, ?, TRUE)
                    ON CONFLICT (id) DO UPDATE SET
                        full_name = EXCLUDED.full_name,
                        phone = EXCLUDED.phone
                    """,
                    id("contact:" + tenantCode), tenantIds.get(tenantCode), outletId,
                    text(row, "contact"), text(row, "phone"));
        });
    }

    private void seedRoutes(JsonNode rows) {
        rows.forEach(row -> {
            String code = text(row, "code");
            UUID routeId = id("route:" + code);
            routeIds.put(code, routeId);
            jdbc.update("""
                    INSERT INTO route (id, tenant_id, org_unit_id, code, name, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    ON CONFLICT (tenant_id, code) DO UPDATE SET
                        org_unit_id = EXCLUDED.org_unit_id,
                        name = EXCLUDED.name,
                        status = EXCLUDED.status
                    """, routeId, tenantIds.get(NSX), orgUnitIds.get(text(row, "orgUnit")), code, text(row, "name"));
        });
        JsonNode outlets = readSeed().path("outlets");
        outlets.forEach(row -> {
            String routeCode = text(row, "route");
            String outletCode = text(row, "tenant");
            UUID relationId = id("route-outlet:" + routeCode + ":" + outletCode);
            jdbc.update("""
                    INSERT INTO route_outlet (id, tenant_id, route_id, outlet_id, position, effective_from)
                    VALUES (?, ?, ?, ?, ?, DATE '2026-11-01')
                    ON CONFLICT (tenant_id, route_id, outlet_id, effective_from) DO UPDATE SET
                        position = EXCLUDED.position
                    """,
                    relationId, tenantIds.get(NSX), routeIds.get(routeCode),
                    outletIds.get(outletCode), row.path("position").asInt());
            jdbc.update("""
                    INSERT INTO mcp_schedule (
                        id, tenant_id, route_id, outlet_id, week_of_month, day_of_week, effective_from
                    )
                    VALUES (?, ?, ?, ?, 2, 4, DATE '2026-11-01')
                    ON CONFLICT (tenant_id, route_id, outlet_id, week_of_month, day_of_week, effective_from)
                    DO NOTHING
                    """,
                    id("mcp:" + routeCode + ":" + outletCode), tenantIds.get(NSX),
                    routeIds.get(routeCode), outletIds.get(outletCode));
        });
        for (JsonNode row : rows) {
            String routeCode = text(row, "code");
            UUID routeId = routeIds.get(routeCode);
            UUID assignedUserId = require(userIds, text(row, "salesUser"), "user");
            jdbc.update("""
                    INSERT INTO user_route (tenant_id, user_id, route_id)
                    VALUES (?, ?, ?)
                    ON CONFLICT (tenant_id, user_id, route_id) DO NOTHING
                    """, tenantIds.get(NSX), assignedUserId, routeId);
        }
    }

    private void seedRelations(JsonNode tenantRelations, JsonNode outletNppRows) {
        tenantRelations.forEach(row -> {
            String nsx = text(row, "nsx");
            String npp = text(row, "npp");
            jdbc.update("""
                    INSERT INTO tenant_relation (
                        id, tenant_id, nsx_tenant_id, npp_tenant_id, status, effective_from
                    )
                    VALUES (?, ?, ?, ?, 'ACTIVE', DATE '2026-11-01')
                    ON CONFLICT (tenant_id, nsx_tenant_id, npp_tenant_id, effective_from)
                    DO UPDATE SET status = EXCLUDED.status
                    """,
                    id("tenant-relation:" + nsx + ":" + npp), tenantIds.get(nsx),
                    tenantIds.get(nsx), tenantIds.get(npp));
        });
        outletNppRows.forEach(row -> {
            String outletCode = text(row, "outlet");
            String nppCode = text(row, "npp");
            UUID outletTenantId = tenantIds.get(outletCode);
            UUID nppTenantId = tenantIds.get(nppCode);
            jdbc.update("""
                    INSERT INTO outlet_npp (
                        id, tenant_id, outlet_id, npp_tenant_id, status, effective_from
                    )
                    VALUES (?, ?, ?, ?, 'ACTIVE', DATE '2026-11-01')
                    ON CONFLICT (tenant_id, outlet_id, npp_tenant_id, effective_from)
                    DO UPDATE SET status = EXCLUDED.status
                    """,
                    id("outlet-npp:" + outletCode + ":" + nppCode), nppTenantId,
                    outletIds.get(outletCode), nppTenantId);
        });
    }

    private void seedProducts(JsonNode rows) {
        rows.forEach(row -> {
            String sku = text(row, "sku");
            String baseUnit = text(row, "baseUnit");
            int caseFactor = row.path("caseUnits").asInt();
            UUID productId = id("product:" + sku);
            productIds.put(sku, productId);
            caseUnits.put(sku, caseFactor);
            jdbc.update("""
                    INSERT INTO product (id, tenant_id, sku, name, base_unit_code, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    ON CONFLICT (tenant_id, sku) DO UPDATE SET
                        name = EXCLUDED.name,
                        base_unit_code = EXCLUDED.base_unit_code,
                        status = EXCLUDED.status
                    """, productId, tenantIds.get(NSX), sku, text(row, "name"), baseUnit);
            upsertUnit(productId, sku, baseUnit, baseUnit, 1, true);
            upsertUnit(productId, sku, "THUNG", "thùng", caseFactor, false);
        });
    }

    private void upsertUnit(UUID productId, String sku, String code, String name, int baseUnits, boolean base) {
        jdbc.update("""
                INSERT INTO unit_conversion (id, tenant_id, product_id, unit_code, unit_name, base_units, is_base_unit)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, product_id, unit_code) DO UPDATE SET
                    unit_name = EXCLUDED.unit_name,
                    base_units = EXCLUDED.base_units,
                    is_base_unit = EXCLUDED.is_base_unit
                """, id("unit:" + sku + ":" + code), tenantIds.get(NSX), productId, code, name, baseUnits, base);
    }

    private void seedPriceList(JsonNode products) {
        UUID priceListId = id("price-list:DEMO-BASE-2026-11");
        jdbc.update("""
                INSERT INTO price_list (
                    id, tenant_id, code, name, status, created_by, approved_by, effective_from, effective_to
                )
                VALUES (?, ?, 'DEMO-BASE-2026-11', 'Bảng giá Suối Việt tháng 11', 'EFFECTIVE',
                    ?, ?, TIMESTAMPTZ '2026-11-01T00:00:00Z', TIMESTAMPTZ '2026-11-30T16:59:59Z')
                ON CONFLICT (tenant_id, code) DO UPDATE SET
                    status = EXCLUDED.status,
                    approved_by = EXCLUDED.approved_by,
                    effective_from = EXCLUDED.effective_from,
                    effective_to = EXCLUDED.effective_to
                """, priceListId, tenantIds.get(NSX),
                userIds.get("nsx-admin@demo.synera.local"),
                userIds.get("nsx-leader@demo.synera.local"));
        for (String nppCode : new String[]{MINH_PHAT, "AN_KHANG"}) {
            jdbc.update("""
                    INSERT INTO price_list_scope (id, tenant_id, price_list_id, scope_type, scope_id)
                    VALUES (?, ?, ?, 'NPP', ?)
                    ON CONFLICT (id) DO NOTHING
                    """, id("price-scope:DEMO-BASE-2026-11:" + nppCode), tenantIds.get(NSX),
                    priceListId, tenantIds.get(nppCode));
        }
        products.forEach(row -> jdbc.update("""
                INSERT INTO price_list_line (
                    id, tenant_id, price_list_id, product_id, unit_code, sellin_price_vnd, sellout_price_vnd
                )
                VALUES (?, ?, ?, ?, 'THUNG', ?, ?)
                ON CONFLICT (tenant_id, price_list_id, product_id, unit_code) DO UPDATE SET
                    sellin_price_vnd = EXCLUDED.sellin_price_vnd,
                    sellout_price_vnd = EXCLUDED.sellout_price_vnd
                """,
                id("price-line:" + text(row, "sku")), tenantIds.get(NSX), priceListId,
                productIds.get(text(row, "sku")), row.path("sellInPrice").asLong(),
                row.path("sellOutPrice").asLong()));
    }

    private void seedPromotions(JsonNode rows) {
        rows.forEach(row -> {
            String code = text(row, "code");
            UUID promotionId = id("promotion:" + code);
            jdbc.update("""
                    INSERT INTO promotion (
                        id, tenant_id, code, name, status, created_by, approved_by, effective_from, effective_to
                    )
                    VALUES (?, ?, ?, ?, 'EFFECTIVE', ?, ?, TIMESTAMPTZ '2026-11-01T00:00:00Z',
                        TIMESTAMPTZ '2026-11-30T16:59:59Z')
                    ON CONFLICT (tenant_id, code) DO UPDATE SET
                        name = EXCLUDED.name,
                        status = EXCLUDED.status,
                        approved_by = EXCLUDED.approved_by,
                        effective_from = EXCLUDED.effective_from,
                        effective_to = EXCLUDED.effective_to
                    """,
                    promotionId, tenantIds.get(NSX), code, text(row, "name"),
                    userIds.get("nsx-trade@demo.synera.local"),
                    userIds.get("nsx-leader@demo.synera.local"));
            String sku = text(row, "sku");
            String giftSku = nullableText(row, "giftSku");
            jdbc.update("""
                    INSERT INTO promotion_rule (
                        id, tenant_id, promotion_id, product_id, unit_code, min_order_qty,
                        discount_basis_points, gift_product_id, gift_qty
                    )
                    VALUES (?, ?, ?, ?, 'THUNG', ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        min_order_qty = EXCLUDED.min_order_qty,
                        discount_basis_points = EXCLUDED.discount_basis_points,
                        gift_product_id = EXCLUDED.gift_product_id,
                        gift_qty = EXCLUDED.gift_qty
                    """,
                    id("promotion-rule:" + code), tenantIds.get(NSX), promotionId,
                    productIds.get(sku), row.path("minCases").asLong(),
                    nullableInteger(row, "discountBasisPoints"),
                    giftSku == null ? null : productIds.get(giftSku),
                    giftSku == null ? null : (long) row.path("giftCases").asInt());
        });
    }

    private void seedPeriods(JsonNode rows) {
        rows.forEach(row -> {
            String type = text(row, "type");
            LocalDate start = LocalDate.parse(text(row, "start"));
            LocalDate end = LocalDate.parse(text(row, "end"));
            UUID periodId = id("period:" + type + ":" + start);
            jdbc.update("""
                    INSERT INTO period (id, tenant_id, period_type, starts_on, ends_on, status)
                    VALUES (?, ?, ?, ?, ?, 'OPEN')
                    ON CONFLICT (tenant_id, period_type, starts_on, ends_on) DO NOTHING
                    """, periodId, tenantIds.get(NSX), type, start, end);
        });
    }

    private void seedOrders(JsonNode rows) {
        rows.forEach(row -> {
            String type = text(row, "type");
            String code = text(row, "code");
            String nppCode = text(row, "npp");
            String creatorEmail = text(row, "createdBy");
            UUID orderId = id("order:" + type + ":" + code);
            orderIds.put(code, orderId);
            UUID tenantId = tenantIds.get(text(row, "tenant"));
            UUID nppId = tenantIds.get(nppCode);
            UUID creatorId = require(userIds, creatorEmail, "user");
            Instant createdAt = Instant.parse(text(row, "createdAt"));
            String status = text(row, "status");
            long sellOutGrossTotal = 0;
            long sellInGrossTotal = 0;
            if (type.equals("SELL_IN")) {
                jdbc.update("""
                        INSERT INTO order_si (
                            id, tenant_id, npp_tenant_id, order_code, status, idempotency_key,
                            created_by, approved_by, note, created_at, updated_at,
                            subtotal_vnd, total_vnd, submitted_at, received_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, order_code) DO UPDATE SET
                            npp_tenant_id = EXCLUDED.npp_tenant_id,
                            idempotency_key = EXCLUDED.idempotency_key,
                            approved_by = EXCLUDED.approved_by,
                            note = EXCLUDED.note,
                            subtotal_vnd = EXCLUDED.subtotal_vnd,
                            total_vnd = EXCLUDED.total_vnd,
                            submitted_at = EXCLUDED.submitted_at,
                            received_at = EXCLUDED.received_at
                        """, orderId, tenantId, nppId, code, status, "seed:" + code,
                        creatorId, Set.of("APPROVED", "SHIPPED", "RECEIVED", "RECONCILED", "PERIOD_CLOSED")
                                .contains(status) ? userIds.get("nsx-leader@demo.synera.local") : null,
                        "Dữ liệu demo", Timestamp.from(createdAt), Timestamp.from(createdAt),
                        0L, 0L, status.equals("DRAFT") ? null : Timestamp.from(createdAt),
                        Set.of("RECEIVED", "RECONCILED", "PERIOD_CLOSED").contains(status)
                                ? Timestamp.from(createdAt) : null);
            } else {
                String outletCode = text(row, "outlet");
                jdbc.update("""
                        INSERT INTO order_so (
                            id, tenant_id, npp_tenant_id, outlet_id, order_code, status,
                            idempotency_key, created_by, note, created_at, updated_at,
                            confirmation_due_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Dữ liệu demo', ?, ?, ?)
                        ON CONFLICT (tenant_id, order_code) DO UPDATE SET
                            npp_tenant_id = EXCLUDED.npp_tenant_id,
                            outlet_id = EXCLUDED.outlet_id,
                            idempotency_key = EXCLUDED.idempotency_key,
                            created_by = EXCLUDED.created_by,
                            status = EXCLUDED.status,
                            note = EXCLUDED.note,
                            confirmation_due_at = EXCLUDED.confirmation_due_at
                        """, orderId, tenantId, nppId, outletIds.get(outletCode), code, status,
                        "seed:" + code, creatorId, Timestamp.from(createdAt), Timestamp.from(createdAt),
                        status.equals("SUBMITTED")
                                ? Timestamp.from(createdAt.plusSeconds(4 * 60 * 60)) : null);
            }
            int lineNumber = 0;
            boolean issueDamageAdded = false;
            for (JsonNode line : row.path("lines")) {
                String sku = text(line, "sku");
                int factor = caseUnits.get(sku);
                boolean gift = line.path("isGift").asBoolean();
                long ordered = line.path("orderedCases").asLong() * factor;
                long confirmed = line.path("confirmedCases").asLong() * factor;
                long shipped = line.path("shippedCases").asLong() * factor;
                long received = line.path("receivedCases").asLong() * factor;
                long price = gift ? 0 : (type.equals("SELL_IN")
                        ? productPrice(sku, "sellin_price_vnd")
                        : productPrice(sku, "sellout_price_vnd"));
                long gross = Math.multiplyExact(price, line.path("orderedCases").asLong());
                if (type.equals("SELL_OUT")) {
                    sellOutGrossTotal = Math.addExact(sellOutGrossTotal, gross);
                } else {
                    sellInGrossTotal = Math.addExact(sellInGrossTotal, gross);
                }
                String baseUnitCode = jdbc.queryForObject(
                        "SELECT base_unit_code FROM product WHERE id = ?", String.class, productIds.get(sku));
                UUID lineId = id("order-line:" + type + ":" + code + ":" + lineNumber++);
                jdbc.update("""
                        INSERT INTO order_line (
                            id, tenant_id, order_si_id, order_so_id, product_id, unit_code,
                            entered_unit_code, entered_qty, ordered_qty, confirmed_qty, shipped_qty,
                            received_qty, unit_price_vnd, is_gift, gross_amount_vnd, discount_vnd,
                            line_amount_vnd
                        )
                        VALUES (?, ?, ?, ?, ?, ?, 'THUNG', ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            unit_code = EXCLUDED.unit_code,
                            entered_qty = EXCLUDED.entered_qty,
                            ordered_qty = EXCLUDED.ordered_qty,
                            confirmed_qty = EXCLUDED.confirmed_qty,
                            shipped_qty = EXCLUDED.shipped_qty,
                            received_qty = EXCLUDED.received_qty,
                            unit_price_vnd = EXCLUDED.unit_price_vnd,
                            is_gift = EXCLUDED.is_gift,
                            gross_amount_vnd = EXCLUDED.gross_amount_vnd,
                            line_amount_vnd = EXCLUDED.line_amount_vnd
                        """, lineId, tenantId, type.equals("SELL_IN") ? orderId : null,
                        type.equals("SELL_OUT") ? orderId : null, productIds.get(sku),
                            baseUnitCode, line.path("orderedCases").asLong(), ordered, confirmed,
                        shipped, received, price, gift, gross, gross);
                if (type.equals("SELL_IN")
                        && Set.of("RECEIVED", "RECONCILED", "PERIOD_CLOSED").contains(status)) {
                    long damaged = line.path("damagedCases").asLong() * factor;
                    jdbc.update("""
                            INSERT INTO order_si_receipt_line (
                                id, tenant_id, order_si_id, order_line_id, received_qty,
                                damaged_qty, note, created_by
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (order_si_id, order_line_id) DO UPDATE SET
                                received_qty = EXCLUDED.received_qty,
                                damaged_qty = EXCLUDED.damaged_qty,
                                note = EXCLUDED.note
                            """, id("order-si-receipt-line:" + code + ":" + lineId),
                            tenantId, orderId, lineId, received, damaged,
                            damaged > 0 ? "Có hàng bị hỏng." : "", creatorId);
                }
                if (type.equals("SELL_OUT") && status.equals("RECEIVED_ISSUE")) {
                    long damaged = !issueDamageAdded && received > 0 ? 1 : 0;
                    if (damaged > 0) {
                        issueDamageAdded = true;
                    }
                    jdbc.update("""
                            INSERT INTO order_receipt_line (
                                id, tenant_id, order_so_id, order_line_id, received_qty,
                                damaged_qty, note, created_by
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (id) DO UPDATE SET
                                received_qty = EXCLUDED.received_qty,
                                damaged_qty = EXCLUDED.damaged_qty,
                                note = EXCLUDED.note
                            """, id("order-receipt-line:" + code + ":" + lineId), tenantId, orderId,
                            lineId, received, damaged, damaged > 0 ? "Có hàng bị hỏng." : "",
                            creatorId);
                }
            }
            if (type.equals("SELL_OUT")) {
                jdbc.update("""
                        UPDATE order_so SET subtotal_vnd = ?, discount_vnd = 0, total_vnd = ?
                        WHERE id = ? AND tenant_id = ?
                        """, sellOutGrossTotal, sellOutGrossTotal, orderId, tenantId);
            } else {
                jdbc.update("""
                        UPDATE order_si SET subtotal_vnd = ?, total_vnd = ?
                        WHERE id = ? AND tenant_id = ?
                        """, sellInGrossTotal, sellInGrossTotal, orderId, tenantId);
            }
            jdbc.update("""
                    INSERT INTO order_status_history (
                        id, tenant_id, order_si_id, order_so_id, from_status, to_status, actor_id, occurred_at, reason
                    )
                    VALUES (?, ?, ?, ?, NULL, ?, ?, ?, 'Trạng thái ban đầu từ seed demo')
                    ON CONFLICT (id) DO NOTHING
                    """, id("order-history:" + type + ":" + code), tenantId,
                    type.equals("SELL_IN") ? orderId : null,
                    type.equals("SELL_OUT") ? orderId : null,
                    status, creatorId, Timestamp.from(createdAt));
        });
    }

    private void seedDailySeries(JsonNode sellIn, JsonNode sellOut) {
        LocalDate firstDay = LocalDate.of(2026, 10, 14);
        UUID nppId = tenantIds.get(MINH_PHAT);
        UUID nsxId = tenantIds.get(NSX);
        UUID creatorId = userIds.get("an-khang-owner@demo.synera.local");
        int factor = caseUnits.get("NS500");
        for (int index = 0; index < 30; index++) {
            LocalDate day = firstDay.plusDays(index);
            String code = "DEMO-SI-" + day.toString().replace("-", "");
            UUID dailyOrderId = id("order:SELL_IN:" + code);
            Instant occurredAt = day.atTime(12, 0).toInstant(ZoneOffset.UTC);
            jdbc.update("""
                    INSERT INTO order_si (
                        id, tenant_id, npp_tenant_id, order_code, status, idempotency_key,
                        created_by, note, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, 'RECONCILED', ?, ?, 'Chuỗi sell-in demo 30 ngày',
                        ?, ?)
                    ON CONFLICT (tenant_id, order_code) DO UPDATE SET status = EXCLUDED.status
                    """, dailyOrderId, nsxId, nppId, code, "seed:" + code, creatorId,
                    Timestamp.from(occurredAt), Timestamp.from(occurredAt));
            long sellInQty = sellIn.get(index).asLong() * factor;
            jdbc.update("""
                    INSERT INTO order_line (
                        id, tenant_id, order_si_id, product_id, unit_code, entered_unit_code,
                        ordered_qty, confirmed_qty, shipped_qty, received_qty, unit_price_vnd, is_gift
                    )
                    VALUES (?, ?, ?, ?, 'THUNG', 'THUNG', ?, ?, ?, ?, ?, FALSE)
                    ON CONFLICT (id) DO UPDATE SET
                        ordered_qty = EXCLUDED.ordered_qty,
                        confirmed_qty = EXCLUDED.confirmed_qty,
                        shipped_qty = EXCLUDED.shipped_qty,
                        received_qty = EXCLUDED.received_qty
                    """,                     id("order-line:SELL_IN:" + code + ":0"), nsxId, dailyOrderId,
                    productIds.get("NS500"), sellInQty, sellInQty, sellInQty, sellInQty,
                    productPrice("NS500", "sellin_price_vnd"));
            long sellOutQty = sellOut.get(index).asLong() * factor;
            jdbc.update("""
                    INSERT INTO sellout_record (
                        id, tenant_id, npp_tenant_id, product_id, unit_code, quantity,
                        occurred_at, source, created_by
                    )
                    VALUES (?, ?, ?, ?, 'CHAI', ?, ?, 'DEMO_SEED', ?)
                    ON CONFLICT (id) DO UPDATE SET
                        quantity = EXCLUDED.quantity,
                        occurred_at = EXCLUDED.occurred_at
                    """, id("sellout:" + day), nppId, nppId, productIds.get("NS500"), sellOutQty,
                    Timestamp.from(occurredAt), userIds.get("tran.ha@demo.synera.local"));
        }
    }

    private void seedInventory(JsonNode rows) {
        rows.forEach(row -> {
            String sku = text(row, "sku");
            long quantity = row.path("stockCases").asLong() * caseUnits.get(sku);
            Timestamp updatedAt = Timestamp.from(Instant.parse("2026-11-12T02:42:00Z"));
            jdbc.update("""
                    INSERT INTO inventory_npp (
                        id, tenant_id, npp_tenant_id, product_id, unit_code, quantity, recorded_at, recorded_by
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        quantity = EXCLUDED.quantity,
                        recorded_at = EXCLUDED.recorded_at
                    """, id("inventory:MINH_PHAT:" + sku), tenantIds.get(MINH_PHAT),
                    tenantIds.get(MINH_PHAT), productIds.get(sku), text(row, "baseUnit"),
                    quantity, updatedAt, userIds.get("npp-staff@demo.synera.local"));
            jdbc.update("""
                    INSERT INTO sellout_record (
                        id, tenant_id, npp_tenant_id, product_id, unit_code, quantity,
                        occurred_at, source, created_by
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'DEMO_WEEKLY_TOTAL', ?)
                    ON CONFLICT (id) DO UPDATE SET quantity = EXCLUDED.quantity
                    """, id("sellout-week:" + sku), tenantIds.get(MINH_PHAT), tenantIds.get(MINH_PHAT),
                    productIds.get(sku), text(row, "baseUnit"),
                    row.path("sales7dCases").asLong() * caseUnits.get(sku),
                    Timestamp.from(Instant.parse("2026-11-12T02:42:00Z")),
                    userIds.get("npp-staff@demo.synera.local"));
        });
    }

    private void seedDemandPlans(JsonNode rows) {
        rows.forEach(row -> {
            String sku = text(row, "sku");
            long quantity = row.path("quantityCases").asLong() * caseUnits.get(sku);
            jdbc.update("""
                    INSERT INTO demand_plan (
                        id, tenant_id, npp_tenant_id, product_id, unit_code, quantity,
                        planned_for, status, created_by
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?)
                    ON CONFLICT (id) DO UPDATE SET
                        quantity = EXCLUDED.quantity,
                        planned_for = EXCLUDED.planned_for,
                        status = EXCLUDED.status
                    """, id("demand-plan:" + sku), tenantIds.get(MINH_PHAT), tenantIds.get(MINH_PHAT),
                    productIds.get(sku), text(productRow(sku), "baseUnit"), quantity,
                    LocalDate.parse(text(row, "plannedFor")),
                    userIds.get("chi.mai@demo.synera.local"));
        });
    }

    private void seedVisits(JsonNode rows) {
        rows.forEach(row -> {
            String outletCode = text(row, "outlet");
            String salesEmail = text(row, "sales");
            UUID visitId = id("visit:" + outletCode + ":2026-11-12");
            Timestamp plannedAt = timestamp(row, "plannedAt");
            Timestamp checkInAt = timestamp(row, "checkInAt");
            Timestamp checkOutAt = timestamp(row, "checkOutAt");
            jdbc.update("""
                    INSERT INTO visit (
                        id, tenant_id, route_id, outlet_id, sales_user_id, status,
                        planned_at, checked_in_at, checked_out_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        status = EXCLUDED.status,
                        planned_at = EXCLUDED.planned_at,
                        checked_in_at = EXCLUDED.checked_in_at,
                        checked_out_at = EXCLUDED.checked_out_at
                    """, visitId, tenantIds.get(NSX), routeIds.get("RS00301"), outletIds.get(outletCode),
                    userIds.get(salesEmail), text(row, "status"), plannedAt, checkInAt, checkOutAt);
        });
    }

    private void seedTickets(JsonNode rows) {
        rows.forEach(row -> {
            String code = text(row, "code");
            UUID ticketId = id("ticket:" + text(row, "tenant") + ":" + code);
            String tenantCode = text(row, "tenant");
            ticketIds.put(code, ticketId);
            String outletCode = nullableText(row, "outlet");
            String orderCode = nullableText(row, "order");
            String orderType = orderCode != null && orderCode.startsWith("SI-") ? "SELL_IN" : "SELL_OUT";
            jdbc.update("""
                    INSERT INTO ticket (
                        id, tenant_id, ticket_code, title, description, status, outlet_id,
                        order_si_id, order_so_id, created_by, assignee_id, due_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, ticket_code) DO UPDATE SET
                        title = EXCLUDED.title,
                        description = EXCLUDED.description,
                        status = EXCLUDED.status,
                        assignee_id = EXCLUDED.assignee_id,
                        due_at = EXCLUDED.due_at
                    """, ticketId, tenantIds.get(tenantCode), code, text(row, "title"),
                    text(row, "description"), text(row, "status"),
                    outletCode == null ? null : outletIds.get(outletCode),
                    orderCode == null || orderType.equals("SELL_OUT") ? null : orderIds.get(orderCode),
                    orderCode == null || orderType.equals("SELL_IN") ? null : orderIds.get(orderCode),
                    userIds.get("tran.ha@demo.synera.local"), userIds.get(text(row, "assignee")),
                    timestamp(row, "dueAt"));
            jdbc.update("""
                    INSERT INTO ticket_event (id, tenant_id, ticket_id, actor_id, event_type, body)
                    VALUES (?, ?, ?, ?, 'CREATED', ?)
                    ON CONFLICT (id) DO NOTHING
                    """, id("ticket-event:" + code), tenantIds.get(tenantCode), ticketId,
                    userIds.get("tran.ha@demo.synera.local"), text(row, "description"));
        });
    }

    private void seedAppointments(JsonNode rows) {
        rows.forEach(row -> {
            String code = text(row, "code");
            String outletCode = text(row, "outlet");
            jdbc.update("""
                    INSERT INTO task (
                        id, tenant_id, title, description, status, outlet_id, created_by, assignee_id, due_at
                    )
                    VALUES (?, ?, ?, '', ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        title = EXCLUDED.title,
                        status = EXCLUDED.status,
                        due_at = EXCLUDED.due_at
                    """, id("task:" + code), tenantIds.get(NSX), text(row, "title"),
                    text(row, "status"), outletIds.get(outletCode),
                    userIds.get("tran.ha@demo.synera.local"), userIds.get(text(row, "assignee")),
                    timestamp(row, "dueAt"));
        });
    }

    private void seedFinance(JsonNode rows) {
        rows.forEach(row -> {
            String nppCode = text(row, "npp");
            Long debt = nullableLong(row, "debtVnd");
            if (debt != null) {
                UUID monthPeriodId = id("period:MONTH:2026-11-01");
                jdbc.update("""
                        INSERT INTO invoice (
                            id, tenant_id, npp_tenant_id, period_id, invoice_code, amount_vnd,
                            issued_at, due_at, status
                        )
                        VALUES (?, ?, ?, ?, ?, ?, TIMESTAMPTZ '2026-11-01T00:00:00Z',
                            TIMESTAMPTZ '2026-11-30T16:59:59Z', 'OPEN')
                        ON CONFLICT (tenant_id, invoice_code) DO UPDATE SET
                            amount_vnd = EXCLUDED.amount_vnd,
                            status = EXCLUDED.status
                        """, id("invoice:DEMO-" + nppCode), tenantIds.get(NSX), tenantIds.get(nppCode),
                        monthPeriodId, "DEMO-" + nppCode, debt);
                long remainingBaseQty = row.path("remainingCases").asLong() * caseUnits.get("NS500");
                jdbc.update("""
                        INSERT INTO credit_limit (
                            id, tenant_id, npp_tenant_id, limit_qty, unit_code, status,
                            created_by, approved_by, effective_from
                        )
                        VALUES (?, ?, ?, ?, 'CHAI', 'EFFECTIVE', ?, ?, TIMESTAMPTZ '2026-11-01T00:00:00Z')
                        ON CONFLICT (id) DO UPDATE SET
                            limit_qty = EXCLUDED.limit_qty,
                            status = EXCLUDED.status
                        """, id("credit-limit:" + nppCode), tenantIds.get(NSX), tenantIds.get(nppCode),
                        remainingBaseQty, userIds.get("nsx-accounting@demo.synera.local"),
                        userIds.get("nsx-leader@demo.synera.local"));
            }
        });
    }

    private void seedTarget(JsonNode dashboard) {
        jdbc.update("""
                INSERT INTO target (
                    id, tenant_id, target_code, target_type, subject_id, period_id, target_qty,
                    created_by, approved_by
                )
                VALUES (?, ?, 'SELLIN-MONTH-2026-11', 'SELL_IN', ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, target_code) DO UPDATE SET
                    target_qty = EXCLUDED.target_qty
                """, id("target:SELLIN-MONTH-2026-11"), tenantIds.get(NSX),
                tenantIds.get(MINH_PHAT), id("period:MONTH:2026-11-01"),
                dashboard.path("sellInTargetTotal").asLong() * caseUnits.get("NS500"),
                userIds.get("nsx-accounting@demo.synera.local"),
                userIds.get("nsx-leader@demo.synera.local"));
    }

    private void seedAuditLog(JsonNode rows) {
        rows.forEach(row -> {
            String actorEmail = text(row, "actor");
            String resourceType = text(row, "resourceType");
            String resourceCode = text(row, "resourceCode");
            String permission = switch (resourceType) {
                case "order_si" -> "order_si:view";
                case "order_so" -> "order_so:create";
                case "visit" -> "visit:checkin";
                default -> null;
            };
            UUID resourceId = switch (resourceType) {
                case "order_si" -> orderIds.get(resourceCode);
                case "order_so" -> orderIds.get(resourceCode);
                case "visit" -> id("visit:" + resourceCode + ":2026-11-12");
                default -> null;
            };
            String tenantCode = actorEmail.startsWith("tran.") ? NSX : "MINH_PHAT";
            jdbc.update("""
                    INSERT INTO audit_log (
                        id, tenant_id, actor_id, permission_code, action, resource_type,
                        resource_id, reason, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'Dòng log mẫu từ dữ liệu demo', ?)
                    ON CONFLICT (id) DO NOTHING
                    """, id("audit:" + actorEmail + ":" + resourceCode), tenantIds.get(tenantCode),
                    userIds.get(actorEmail), permission, text(row, "action"), resourceType,
                    resourceId, timestamp(row, "occurredAt"));
        });
    }

    private void seedTenantConfig(JsonNode seed) {
        writeConfig("DEMO", "demo.dashboard.snapshot", seed.path("dashboard"));
        writeConfig("DEMO", "demo.finance.rows", seed.path("financeRows"));
        writeConfig("DEMO", "demo.dailySellIn30d", seed.path("dailySellIn30d"));
        writeConfig("DEMO", "demo.dailySellOut30d", seed.path("dailySellOut30d"));
        writeConfig(NSX, "feature.aiSuggestionsEnabled", seed.path("featureFlags").path("aiSuggestionsEnabled"));
        writeConfig(NSX, "demo.salesRegions", seed.path("salesRegions"));
        writeConfig(NSX, "demo.routeSummary", seed.path("routes"));
        writeConfig(NSX, "demo.salesRoute", seed.path("salesRoute"));
        writeConfig(NSX, "demo.userDirectory", seed.path("userDirectory"));
        writeConfig(NSX, "demo.closeSchedule", seed.path("closeSchedule"));
    }

    private void writeConfig(String tenantCode, String key, JsonNode value) {
        jdbc.update("""
                INSERT INTO tenant_config (id, tenant_id, config_key, config_value)
                VALUES (?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (tenant_id, config_key) DO UPDATE SET
                    config_value = EXCLUDED.config_value
                """, id("tenant-config:" + tenantCode + ":" + key),
                tenantIds.get(tenantCode.equals("DEMO") ? NSX : tenantCode), key, value.toString());
    }

    private long productPrice(String sku, String column) {
        String tenantCode = NSX;
        Long price = jdbc.queryForObject(
                "SELECT " + column + " FROM price_list_line WHERE tenant_id = ? AND product_id = ?",
                Long.class, tenantIds.get(tenantCode), productIds.get(sku));
        if (price == null) {
            throw new IllegalStateException("Thiếu giá seed cho SKU " + sku + ".");
        }
        return price;
    }

    private JsonNode productRow(String sku) {
        JsonNode rows = readSeed().path("products");
        for (JsonNode row : rows) {
            if (text(row, "sku").equals(sku)) {
                return row;
            }
        }
        throw new IllegalStateException("Không tìm thấy SKU trong seed: " + sku);
    }

    private Timestamp timestamp(JsonNode row, String field) {
        String value = nullableText(row, field);
        return value == null ? null : Timestamp.from(Instant.parse(value));
    }

    private UUID require(Map<String, UUID> values, String key, String kind) {
        UUID value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Thiếu " + kind + " tham chiếu trong seed: " + key);
        }
        return value;
    }

    private static UUID id(String key) {
        return UUID.nameUUIDFromBytes(("synera-demo:" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode row, String field) {
        return row.path(field).asText();
    }

    private static String nullableText(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long nullableLong(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static Integer nullableInteger(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }
}
