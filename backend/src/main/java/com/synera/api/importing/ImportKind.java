package com.synera.api.importing;

import java.util.List;
import java.util.Locale;

public enum ImportKind {
    PRODUCTS("Sản phẩm và quy đổi", "products"),
    NPP("Nhà phân phối", "npp"),
    OUTLETS("Điểm bán và liên hệ", "outlets", "outlet_npp"),
    ORG_UNITS("Cây tổ chức", "org_units"),
    ROUTES("Tuyến và MCP", "routes", "route_outlets"),
    USERS("Người dùng và vai", "users"),
    INVENTORY("Tồn đầu kỳ NPP", "inventory_npp"),
    CREDIT("Hạn mức và công nợ đầu kỳ", "npp");

    public final String label;
    public final List<String> sheets;
    ImportKind(String label, String... sheets) { this.label = label; this.sheets = List.of(sheets); }
    public static ImportKind parse(String value) {
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Loại dữ liệu nhập không hợp lệ."); }
    }
}
