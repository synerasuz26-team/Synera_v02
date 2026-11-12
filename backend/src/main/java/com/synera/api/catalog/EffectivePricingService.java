package com.synera.api.catalog;

import com.synera.api.auth.ApiProblem;
import com.synera.api.security.AuditService;
import com.synera.api.security.PermissionScopeResolver;
import com.synera.api.security.ResolvedScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EffectivePricingService {
    private final JdbcTemplate jdbc;
    private final CatalogAccessService access;
    private final CatalogScopeService catalogScopes;
    private final PermissionScopeResolver scopes;
    private final AuditService audit;

    public EffectivePricingService(
            JdbcTemplate jdbc, CatalogAccessService access,
            CatalogScopeService catalogScopes, PermissionScopeResolver scopes, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.catalogScopes = catalogScopes;
        this.scopes = scopes;
        this.audit = audit;
    }

    public EffectivePrice sellIn(
            Jwt jwt, UUID nppId, UUID outletId, UUID productId, String unitCode,
            OffsetDateTime requestedAt, HttpServletRequest servletRequest) {
        OffsetDateTime at = requestedAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : requestedAt;
        CatalogAccessService.Actor actor = access.actor(jwt);
        if ("OUTLET".equals(actor.tenantType())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Tài khoản điểm bán không được tra cứu giá sell-in.");
        }
        access.requireRelatedNpp(actor, nppId, access.businessDate(at));
        if (outletId != null) {
            access.requireOutletServedBy(nppId, outletId, access.businessDate(at));
            if ("OUTLET".equals(actor.tenantType())) {
                access.requireOutletOwner(actor, outletId);
            }
            if ("NSX".equals(actor.tenantType()) || "NPP".equals(actor.tenantType())) {
                requireScope(jwt, "price_sellin:view", outletId);
            }
        } else if ("NSX".equals(actor.tenantType()) || "NPP".equals(actor.tenantType())) {
            ResolvedScope scope = scopes.resolveScope(jwt, "price_sellin:view");
            if (!"A".equals(scope.scope())) {
                throw new ApiProblem("OUTLET_CONTEXT_REQUIRED",
                        "Cần truyền điểm bán để áp dụng phạm vi phân quyền.", HttpStatus.BAD_REQUEST);
            }
        }
        requireProductPartner(nppId, productId, at);
        EffectivePrice result = lookup(nppId, outletId, productId, unitCode, at, "sellin_price_vnd");
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "price_sellin:view", "SELL_IN_PRICE_VIEWED",
                "price", productId, null,
                Map.of("nppId", nppId.toString(), "priceListId", result.priceListId().toString(),
                        "unitCode", result.unitCode()),
                "Tra cứu giá sell-in", servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"), servletRequest.getHeader("User-Agent"));
        return result;
    }

    public EffectivePrice sellOut(
            Jwt jwt, UUID nppId, UUID outletId, UUID productId, String unitCode, OffsetDateTime requestedAt) {
        OffsetDateTime at = requestedAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : requestedAt;
        CatalogAccessService.Actor actor = access.actor(jwt);
        if ("OUTLET".equals(actor.tenantType())) {
            access.requireOutletOwner(actor, outletId);
            access.requireOutletServedBy(nppId, outletId, access.businessDate(at));
        } else {
            access.requireRelatedNpp(actor, nppId, access.businessDate(at));
            access.requireOutletServedBy(nppId, outletId, access.businessDate(at));
        }
        if ("NSX".equals(actor.tenantType()) || "NPP".equals(actor.tenantType())) {
            requireScope(jwt, "price_sellout:view", outletId);
        }
        requireProductPartner(nppId, productId, at);
        return lookup(nppId, outletId, productId, unitCode, at, "sellout_price_vnd");
    }

    public CalculationResult calculate(Jwt jwt, CalculationRequest request) {
        OffsetDateTime at = request.at() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.at();
        scopes.resolveScope(jwt, "price_sellout:view");
        scopes.resolveScope(jwt, "promotion:view");
        List<CalculatedLine> resultLines = new ArrayList<>();
        UUID sourceTenant = null;
        long totalBeforeVoucher = 0;
        for (CalculationLine input : request.lines()) {
            EffectivePrice price = sellOut(jwt, request.nppId(), request.outletId(),
                    input.productId(), input.unitCode(), at);
            UUID productTenant = productTenant(input.productId());
            if (sourceTenant == null) {
                sourceTenant = productTenant;
            } else if (!sourceTenant.equals(productTenant)) {
                throw new ApiProblem("MIXED_PRICE_LIST_TENANTS",
                        "Một lần tính giá chỉ được chứa SKU của cùng một NSX.", HttpStatus.BAD_REQUEST);
            }
            long gross = PricingMath.multiply(price.priceVnd(), input.quantity());
            long quantityInBaseUnits = PricingMath.multiply(input.quantity(),
                    unitFactor(productTenant, input.productId(), input.unitCode()));
            PromotionBenefit benefit = promotionBenefit(
                    productTenant, request.nppId(), request.outletId(), input.productId(),
                    quantityInBaseUnits, gross, at);
            long amount = gross - benefit.discountVnd();
            totalBeforeVoucher = PricingMath.add(totalBeforeVoucher, amount);
            resultLines.add(new CalculatedLine(input.productId(), price.sku(), price.productName(),
                    input.unitCode().strip().toUpperCase(), input.quantity(), price.priceVnd(),
                    gross, benefit.discountVnd(), amount, false, benefit.promotionCode()));
            if (benefit.gift() != null) {
                resultLines.add(benefit.gift());
            }
        }
        long voucherDiscount = request.voucherCode() == null || request.voucherCode().isBlank()
                ? 0
                : voucherDiscount(sourceTenant, request.voucherCode().strip(), request.nppId(),
                        request.outletId(), totalBeforeVoucher, at);
        return new CalculationResult(resultLines, totalBeforeVoucher, voucherDiscount,
                totalBeforeVoucher - voucherDiscount, "VND", at);
    }

    private PromotionBenefit promotionBenefit(
            UUID tenantId, UUID nppId, UUID outletId, UUID productId,
            long quantityInBaseUnits, long grossAmount, OffsetDateTime at) {
        String scopePredicate = catalogScopes.matchesPredicate("promotion_scope", "promotion_id", "p");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(productId);
        args.add(at);
        args.add(at);
        args.addAll(catalogScopes.matchArguments(nppId, outletId, at));
        List<PromotionRuleRow> rules = jdbc.query("""
                SELECT p.code, r.unit_code, r.min_order_qty, r.discount_basis_points,
                       r.gift_product_id, r.gift_qty
                FROM promotion p
                JOIN promotion_rule r ON r.tenant_id = p.tenant_id AND r.promotion_id = p.id
                WHERE p.tenant_id = ? AND r.product_id = ? AND p.status = 'EFFECTIVE'
                  AND p.effective_from <= ? AND (p.effective_to IS NULL OR p.effective_to >= ?)
                  AND """ + scopePredicate + """
                ORDER BY p.code
                """, (rs, row) -> new PromotionRuleRow(
                rs.getString("code"), rs.getString("unit_code"), rs.getLong("min_order_qty"),
                (Integer) rs.getObject("discount_basis_points"),
                rs.getObject("gift_product_id", UUID.class), (Long) rs.getObject("gift_qty")),
                args.toArray());
        for (PromotionRuleRow rule : rules) {
            long threshold = PricingMath.multiply(rule.minOrderQty(),
                    unitFactor(tenantId, productId, rule.unitCode()));
            if (threshold <= 0 || quantityInBaseUnits < threshold) {
                continue;
            }
            if (rule.discountBasisPoints() != null) {
                long discount = PricingMath.percentageDiscount(grossAmount, rule.discountBasisPoints());
                return new PromotionBenefit(discount, null, rule.code());
            }
            if (rule.giftProductId() != null && rule.giftQty() != null) {
                long giftQuantity = PricingMath.giftQuantity(
                        quantityInBaseUnits, threshold, rule.giftQty());
                GiftProduct giftProduct = jdbc.queryForObject(
                        "SELECT sku, name FROM product WHERE tenant_id = ? AND id = ?",
                        (rs, row) -> new GiftProduct(rs.getString("sku"), rs.getString("name")),
                        tenantId, rule.giftProductId());
                CalculatedLine gift = new CalculatedLine(rule.giftProductId(), giftProduct.sku(),
                        giftProduct.name(),
                        rule.unitCode(), giftQuantity, 0, 0, 0, 0, true, rule.code());
                return new PromotionBenefit(0, gift, rule.code());
            }
        }
        return new PromotionBenefit(0, null, null);
    }

    private long voucherDiscount(
            UUID tenantId, String code, UUID nppId, UUID outletId, long total, OffsetDateTime at) {
        if (tenantId == null) {
            return 0;
        }
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(code);
        args.add(at);
        args.add(at);
        args.addAll(catalogScopes.matchArguments(nppId, outletId, at));
        List<VoucherDiscount> vouchers = jdbc.query("""
                SELECT v.discount_amount_vnd, v.discount_basis_points
                FROM voucher v
                WHERE v.tenant_id = ? AND v.code = ? AND v.status = 'EFFECTIVE'
                  AND v.effective_from <= ? AND (v.effective_to IS NULL OR v.effective_to >= ?)
                  AND """ + catalogScopes.matchesPredicate("voucher_scope", "voucher_id", "v") + """
                LIMIT 1
                """, (rs, row) -> new VoucherDiscount(
                (Long) rs.getObject("discount_amount_vnd"),
                (Integer) rs.getObject("discount_basis_points")), args.toArray());
        if (vouchers.isEmpty()) {
            throw new ApiProblem("VOUCHER_NOT_FOUND", "Voucher không tồn tại hoặc chưa hiệu lực.",
                    HttpStatus.NOT_FOUND);
        }
        VoucherDiscount voucher = vouchers.get(0);
        if (voucher.amountVnd() != null) {
            return PricingMath.fixedVoucherDiscount(voucher.amountVnd(), total);
        }
        return PricingMath.percentageDiscount(total, voucher.basisPoints());
    }

    private UUID productTenant(UUID productId) {
        return jdbc.query("""
                SELECT tenant_id FROM product WHERE id = ? AND status = 'ACTIVE'
                """, (rs, row) -> rs.getObject("tenant_id", UUID.class), productId).stream().findFirst()
                .orElseThrow(() -> new ApiProblem("PRODUCT_NOT_FOUND", "Không tìm thấy SKU đang kinh doanh.",
                        HttpStatus.NOT_FOUND));
    }

    private long unitFactor(UUID tenantId, UUID productId, String unitCode) {
        Long factor = jdbc.query("""
                SELECT base_units FROM unit_conversion
                WHERE tenant_id = ? AND product_id = ? AND unit_code = ?
                """, rs -> rs.next() ? rs.getLong("base_units") : null,
                tenantId, productId, unitCode.strip().toUpperCase());
        if (factor == null) {
            throw new ApiProblem("INVALID_PRODUCT_UNIT", "Đơn vị không có trong quy cách SKU.",
                    HttpStatus.BAD_REQUEST, Map.of("unitCode", unitCode));
        }
        return factor;
    }

    private EffectivePrice lookup(
            UUID nppId,
            UUID outletId,
            UUID productId,
            String unitCode,
            OffsetDateTime at,
            String priceColumn) {
        String scopeTable = "price_list_scope";
        String sql = """
                SELECT p.id AS product_id, p.sku, p.name, pll.unit_code, pll.""" + priceColumn + """
                    AS price_vnd, pl.id AS price_list_id, pl.code AS price_list_code
                FROM price_list pl
                JOIN price_list_line pll ON pll.price_list_id = pl.id AND pll.tenant_id = pl.tenant_id
                JOIN product p ON p.id = pll.product_id AND p.tenant_id = pll.tenant_id
                WHERE p.id = ? AND p.status = 'ACTIVE' AND pll.unit_code = ?
                  AND pll.""" + priceColumn + " IS NOT NULL\n"
                + """
                  AND pl.status = 'EFFECTIVE'
                  AND pl.effective_from <= ? AND (pl.effective_to IS NULL OR pl.effective_to >= ?)
                  AND """ + scopedListPredicate(scopeTable, "price_list_id") + """
                ORDER BY CASE
                    WHEN EXISTS (SELECT 1 FROM price_list_scope s WHERE s.tenant_id = pl.tenant_id
                        AND s.price_list_id = pl.id AND s.scope_type = 'OUTLET' AND s.scope_id = ?) THEN 1
                    WHEN EXISTS (SELECT 1 FROM price_list_scope s WHERE s.tenant_id = pl.tenant_id
                        AND s.price_list_id = pl.id AND s.scope_type = 'NPP' AND s.scope_id = ?) THEN 2
                    WHEN EXISTS (
                        SELECT 1 FROM price_list_scope s
                        JOIN org_unit scoped_unit ON scoped_unit.id = s.scope_id
                            AND scoped_unit.tenant_id = s.tenant_id
                        WHERE s.tenant_id = pl.tenant_id AND s.price_list_id = pl.id
                          AND s.scope_type = 'ORG_UNIT' AND EXISTS (
                            SELECT 1
                            FROM route_outlet ro
                            JOIN route r ON r.id = ro.route_id AND r.tenant_id = ro.tenant_id
                            JOIN org_unit route_unit ON route_unit.id = r.org_unit_id
                                AND route_unit.tenant_id = r.tenant_id
                            WHERE ro.tenant_id = pl.tenant_id AND ro.outlet_id = ?
                              AND ro.effective_from <= ?::date
                              AND (ro.effective_to IS NULL OR ro.effective_to >= ?::date)
                              AND (route_unit.path = scoped_unit.path
                                  OR route_unit.path LIKE scoped_unit.path || '/%')
                        )
                    ) THEN 3
                    WHEN NOT EXISTS (SELECT 1 FROM price_list_scope s
                        WHERE s.tenant_id = pl.tenant_id AND s.price_list_id = pl.id) THEN 4
                    ELSE 5 END,
                    pl.effective_from DESC, pl.created_at DESC
                LIMIT 1
                """;
        List<Object> args = new ArrayList<>();
        args.add(productId);
        args.add(unitCode.strip().toUpperCase());
        args.add(at);
        args.add(at);
        args.add(nppId);
        args.add(outletId);
        args.add(outletId);
        args.add(access.businessDate(at));
        args.add(access.businessDate(at));
        args.add(outletId);
        args.add(nppId);
        args.add(outletId);
        args.add(access.businessDate(at));
        args.add(access.businessDate(at));
        return jdbc.query(sql, rs -> {
            if (!rs.next()) {
                throw new ApiProblem("EFFECTIVE_PRICE_NOT_FOUND", "Không tìm thấy giá hiệu lực.",
                        HttpStatus.NOT_FOUND, Map.of("productId", productId.toString(), "unitCode", unitCode));
            }
            Long price = (Long) rs.getObject("price_vnd");
            if (price == null) {
                throw new ApiProblem("EFFECTIVE_PRICE_NOT_FOUND", "Không có loại giá yêu cầu trong bảng giá.",
                        HttpStatus.NOT_FOUND, Map.of("productId", productId.toString(), "unitCode", unitCode));
            }
            return new EffectivePrice(
                    rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getString("name"),
                    rs.getString("unit_code"), price, rs.getObject("price_list_id", UUID.class),
                    rs.getString("price_list_code"), at);
        }, args.toArray());
    }

    private String scopedListPredicate(String table, String recordIdColumn) {
        return """
                (
                    NOT EXISTS (SELECT 1 FROM """ + table + " s WHERE s.tenant_id = pl.tenant_id AND s."
                + recordIdColumn + """
                        = pl.id)
                    OR EXISTS (
                        SELECT 1 FROM """ + table + " s WHERE s.tenant_id = pl.tenant_id AND s."
                + recordIdColumn + """
                            = pl.id AND (
                                (s.scope_type = 'NPP' AND s.scope_id = ?)
                                OR (s.scope_type = 'OUTLET' AND s.scope_id = ?)
                                OR (s.scope_type = 'ORG_UNIT' AND EXISTS (
                                    SELECT 1
                                    FROM org_unit scoped_unit
                                    JOIN route_outlet ro ON ro.tenant_id = scoped_unit.tenant_id
                                    JOIN route r ON r.id = ro.route_id AND r.tenant_id = ro.tenant_id
                                    JOIN org_unit route_unit ON route_unit.id = r.org_unit_id
                                        AND route_unit.tenant_id = r.tenant_id
                                    WHERE scoped_unit.tenant_id = pl.tenant_id
                                      AND scoped_unit.id = s.scope_id
                                      AND ro.outlet_id = ?
                                      AND ro.effective_from <= ?::date
                                      AND (ro.effective_to IS NULL OR ro.effective_to >= ?::date)
                                      AND (route_unit.path = scoped_unit.path
                                          OR route_unit.path LIKE scoped_unit.path || '/%')
                                ))
                            )
                    )
                )
                """;
    }

    private void requireProductPartner(UUID nppId, UUID productId, OffsetDateTime at) {
        Boolean related = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM product p
                    JOIN tenant_relation tr ON tr.nsx_tenant_id = p.tenant_id
                    WHERE p.id = ? AND tr.npp_tenant_id = ? AND tr.status = 'ACTIVE'
                      AND tr.effective_from <= ?::date
                      AND (tr.effective_to IS NULL OR tr.effective_to >= ?::date)
                )
                """, Boolean.class, productId, nppId,
                access.businessDate(at), access.businessDate(at));
        if (!Boolean.TRUE.equals(related)) {
            throw new ApiProblem("PRODUCT_OUTSIDE_PARTNERSHIP", "SKU không thuộc NSX đang hợp tác với NPP.",
                    HttpStatus.NOT_FOUND);
        }
    }

    private void requireScope(Jwt jwt, String permission, UUID outletId) {
        access.requireScopedOutlet(jwt, permission, outletId);
    }

    public record EffectivePrice(
            UUID productId, String sku, String productName, String unitCode, long priceVnd,
            UUID priceListId, String priceListCode, OffsetDateTime effectiveAt) {
    }

    public record CalculationRequest(
            @jakarta.validation.constraints.NotNull UUID nppId,
            @jakarta.validation.constraints.NotNull UUID outletId,
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.Valid List<CalculationLine> lines,
            String voucherCode,
            OffsetDateTime at) {
    }

    public record CalculationLine(
            @jakarta.validation.constraints.NotNull UUID productId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 32) String unitCode,
            @jakarta.validation.constraints.Positive long quantity) {
    }

    public record CalculatedLine(
            UUID productId, String sku, String productName, String unitCode, long quantity,
            long unitPriceVnd, long grossAmountVnd, long discountVnd, long amountVnd,
            boolean isGift, String promotionCode) {
    }

    public record CalculationResult(
            List<CalculatedLine> lines, long subtotalVnd, long voucherDiscountVnd,
            long totalVnd, String currency, OffsetDateTime calculatedAt) {
    }

    private record PromotionRuleRow(
            String code, String unitCode, long minOrderQty, Integer discountBasisPoints,
            UUID giftProductId, Long giftQty) {
    }

    private record PromotionBenefit(long discountVnd, CalculatedLine gift, String promotionCode) {
    }

    private record VoucherDiscount(Long amountVnd, Integer basisPoints) {
    }

    private record GiftProduct(String sku, String name) {
    }
}
