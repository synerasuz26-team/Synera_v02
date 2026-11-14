package com.synera.api.catalog;

import com.synera.api.auth.ApiProblem;
import com.synera.api.security.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductService {
    private final JdbcTemplate jdbc;
    private final CatalogAccessService access;
    private final AuditService audit;

    public ProductService(JdbcTemplate jdbc, CatalogAccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    public ApiPage<ProductSummary> list(
            Jwt jwt, String query, String status, int page, int size) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        StringBuilder where = new StringBuilder(access.productVisibility(actor));
        List<Object> arguments = new ArrayList<>(
                java.util.Arrays.asList(access.productVisibilityArguments(actor)));
        if (query != null && !query.isBlank()) {
            where.append(" AND (LOWER(p.sku) LIKE ? OR LOWER(p.name) LIKE ?)");
            String pattern = "%" + query.strip().toLowerCase() + "%";
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (status != null) {
            where.append(" AND p.status = ?");
            arguments.add(status);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM product p WHERE " + where,
                Long.class, arguments.toArray());
        arguments.add(size);
        arguments.add((long) page * size);
        List<ProductSummary> rows = jdbc.query("""
                SELECT p.id, p.sku, p.name, p.base_unit_code, p.status, p.version
                FROM product p
                WHERE """ + " " + where + " " + """
                ORDER BY p.sku
                LIMIT ? OFFSET ?
                """, productSummaryMapper(), arguments.toArray());
        return ApiPage.of(rows, page, size, total);
    }

    public ProductDetail get(Jwt jwt, UUID productId) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        ProductSummary product = jdbc.query("""
                SELECT p.id, p.sku, p.name, p.base_unit_code, p.status, p.version
                FROM product p
                WHERE p.id = ? AND """ + " " + access.productVisibility(actor),
                productSummaryMapper(), prependProductId(productId, access.productVisibilityArguments(actor)))
                .stream().findFirst()
                .orElseThrow(() -> new ApiProblem("PRODUCT_NOT_FOUND", "Không tìm thấy sản phẩm.",
                        HttpStatus.NOT_FOUND));
        return new ProductDetail(product, units(product.id()));
    }

    @Transactional
    public ProductDetail create(
            Jwt jwt, ProductRequest request, HttpServletRequest servletRequest) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        validateUnits(request.baseUnitCode(), request.units());
        UUID productId = UUID.randomUUID();
        String sku = request.sku().strip().toUpperCase();
        jdbc.update("""
                INSERT INTO product (id, tenant_id, sku, name, base_unit_code, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """, productId, actor.tenantId(), sku, request.name().strip(),
                request.baseUnitCode().strip().toUpperCase());
        saveUnits(actor.tenantId(), productId, request.units());
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "product:manage", "PRODUCT_CREATED",
                "product", productId, null, request, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return getOwn(actor.tenantId(), productId);
    }

    @Transactional
    public ProductDetail update(
            Jwt jwt, UUID productId, ProductUpdateRequest request, HttpServletRequest servletRequest) {
        CatalogAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        validateUnits(request.baseUnitCode(), request.units());
        ProductSummary old = getOwnSummary(actor.tenantId(), productId);
        int updated = jdbc.update("""
                UPDATE product
                SET name = ?, base_unit_code = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE tenant_id = ? AND id = ? AND version = ?
                """, request.name().strip(), request.baseUnitCode().strip().toUpperCase(),
                actor.tenantId(), productId, request.version());
        if (updated == 0) {
            throw versionConflict("product", productId);
        }
        saveUnits(actor.tenantId(), productId, request.units());
        ProductDetail result = getOwn(actor.tenantId(), productId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "product:manage", "PRODUCT_UPDATED",
                "product", productId, old, result, request.reason(), servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return result;
    }

    private ProductDetail getOwn(UUID tenantId, UUID productId) {
        ProductSummary product = getOwnSummary(tenantId, productId);
        return new ProductDetail(product, units(productId));
    }

    private ProductSummary getOwnSummary(UUID tenantId, UUID productId) {
        return jdbc.query("""
                SELECT id, sku, name, base_unit_code, status, version
                FROM product WHERE tenant_id = ? AND id = ?
                """, productSummaryMapper(), tenantId, productId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("PRODUCT_NOT_FOUND", "Không tìm thấy sản phẩm.",
                        HttpStatus.NOT_FOUND));
    }

    private List<UnitConversion> units(UUID productId) {
        return jdbc.query("""
                SELECT unit_code, unit_name, base_units, is_base_unit
                FROM unit_conversion
                WHERE product_id = ?
                ORDER BY is_base_unit DESC, unit_code
                """, (rs, row) -> new UnitConversion(
                rs.getString("unit_code"), rs.getString("unit_name"),
                rs.getLong("base_units"), rs.getBoolean("is_base_unit")), productId);
    }

    private void saveUnits(UUID tenantId, UUID productId, List<UnitConversion> units) {
        jdbc.update("""
                UPDATE unit_conversion SET is_base_unit = FALSE
                WHERE tenant_id = ? AND product_id = ? AND is_base_unit
                """, tenantId, productId);
        for (UnitConversion unit : units) {
            jdbc.update("""
                    INSERT INTO unit_conversion (
                        id, tenant_id, product_id, unit_code, unit_name, base_units, is_base_unit
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, product_id, unit_code) DO UPDATE SET
                        unit_name = EXCLUDED.unit_name,
                        base_units = EXCLUDED.base_units,
                        is_base_unit = EXCLUDED.is_base_unit
                    """, UUID.randomUUID(), tenantId, productId, unit.unitCode().strip().toUpperCase(),
                    unit.unitName().strip(), unit.baseUnits(), unit.isBaseUnit());
        }
    }

    private void validateUnits(String baseUnitCode, List<UnitConversion> units) {
        String normalizedBase = baseUnitCode.strip().toUpperCase();
        long baseCount = units.stream().filter(UnitConversion::isBaseUnit).count();
        boolean validBase = units.stream().anyMatch(unit -> unit.isBaseUnit()
                && unit.unitCode().strip().equalsIgnoreCase(normalizedBase)
                && unit.baseUnits() == 1);
        if (baseCount != 1 || !validBase) {
            throw new ApiProblem("INVALID_UNIT_CONVERSION",
                    "Cần đúng một đơn vị cơ sở có hệ số quy đổi bằng 1.", HttpStatus.BAD_REQUEST,
                    Map.of("baseUnitCode", normalizedBase));
        }
        long distinctCodes = units.stream()
                .map(unit -> unit.unitCode().strip().toUpperCase()).distinct().count();
        if (distinctCodes != units.size()) {
            throw new ApiProblem("DUPLICATE_UNIT_CODE", "Mã đơn vị quy đổi bị trùng.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private ApiProblem versionConflict(String resource, UUID id) {
        return new ApiProblem("VERSION_CONFLICT", "Dữ liệu đã được người khác cập nhật.",
                HttpStatus.CONFLICT, Map.of("resource", resource, "id", id.toString()));
    }

    private RowMapper<ProductSummary> productSummaryMapper() {
        return (rs, row) -> new ProductSummary(
                rs.getObject("id", UUID.class),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("base_unit_code"),
                rs.getString("status"),
                rs.getLong("version"));
    }

    private Object[] prependProductId(UUID productId, Object[] visibilityArguments) {
        Object[] arguments = new Object[visibilityArguments.length + 1];
        arguments[0] = productId;
        System.arraycopy(visibilityArguments, 0, arguments, 1, visibilityArguments.length);
        return arguments;
    }

    public record ProductRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80)
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]+") String sku,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String name,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 32)
            String baseUnitCode,
            @jakarta.validation.constraints.NotEmpty
            List<@jakarta.validation.constraints.NotNull @jakarta.validation.Valid UnitConversion> units,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record ProductUpdateRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String name,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 32)
            String baseUnitCode,
            @jakarta.validation.constraints.NotEmpty
            List<@jakarta.validation.constraints.NotNull @jakarta.validation.Valid UnitConversion> units,
            @jakarta.validation.constraints.PositiveOrZero long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    public record UnitConversion(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 32) String unitCode,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80) String unitName,
            @jakarta.validation.constraints.Positive long baseUnits,
            boolean isBaseUnit) {
    }

    public record ProductSummary(UUID id, String sku, String name, String baseUnitCode, String status, long version) {
    }

    public record ProductDetail(ProductSummary product, List<UnitConversion> units) {
    }
}
