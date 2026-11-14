package com.synera.api.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.ApiPage;
import com.synera.api.organization.BusinessAccessService;
import com.synera.api.operations.DatabaseCapacityPolicy;
import com.synera.api.partner.PartnerAccessService;
import com.synera.api.security.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.EncryptedDocumentException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class InventorySelloutService {
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_IMPORT_ROWS = 10000;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern FILE_NAME_SEPARATOR = Pattern.compile("[\\\\/]+");

    private final JdbcTemplate jdbc;
    private final BusinessAccessService access;
    private final PartnerAccessService partnerAccess;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public InventorySelloutService(
            JdbcTemplate jdbc, BusinessAccessService access,
            PartnerAccessService partnerAccess, AuditService audit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.access = access;
        this.partnerAccess = partnerAccess;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public ApiPage<InventoryView> listInventory(
            Jwt jwt, UUID requestedNppId, String sku, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        NppVisibility visibility = nppVisibility(jwt, actor, "inventory:view", requestedNppId, "i");
        StringBuilder where = new StringBuilder("i.tenant_id = i.npp_tenant_id AND ")
                .append(visibility.sql());
        List<Object> args = new ArrayList<>(visibility.arguments());
        if (sku != null && !sku.isBlank()) {
            where.append(" AND LOWER(p.sku) = ?");
            args.add(sku.strip().toLowerCase(Locale.ROOT));
        }
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT DISTINCT i.npp_tenant_id, i.product_id
                    FROM inventory_npp i
                    JOIN product p ON p.id = i.product_id
                    WHERE
                """ + where + ") current_inventory", Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<InventoryView> rows = jdbc.query("""
                SELECT DISTINCT ON (i.npp_tenant_id, i.product_id)
                    i.id, i.npp_tenant_id, n.name AS npp_name, p.id AS product_id,
                    p.sku, p.name AS product_name, i.unit_code, i.quantity, i.recorded_at,
                    i.recorded_by
                FROM inventory_npp i
                JOIN tenant n ON n.id = i.npp_tenant_id
                JOIN product p ON p.id = i.product_id
                WHERE
                """ + where + """
                ORDER BY i.npp_tenant_id, i.product_id, i.recorded_at DESC, i.id DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new InventoryView(
                rs.getObject("id", UUID.class), rs.getObject("npp_tenant_id", UUID.class),
                rs.getString("npp_name"), rs.getObject("product_id", UUID.class),
                rs.getString("sku"), rs.getString("product_name"), rs.getString("unit_code"),
                rs.getLong("quantity"), rs.getObject("recorded_at", OffsetDateTime.class),
                rs.getObject("recorded_by", UUID.class)), args.toArray());
        return ApiPage.of(rows, page, size, total == null ? 0 : total);
    }

    public ApiPage<SelloutView> listSellout(
            Jwt jwt, UUID requestedNppId, UUID outletId, String sku,
            OffsetDateTime from, OffsetDateTime to, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        NppVisibility visibility = nppVisibility(jwt, actor, "sellout_data:view", requestedNppId, "s");
        StringBuilder where = new StringBuilder("s.tenant_id = s.npp_tenant_id AND ")
                .append(visibility.sql());
        List<Object> args = new ArrayList<>(visibility.arguments());
        if (outletId != null) {
            requireOutletAccess(jwt, actor, "sellout_data:view", requestedNppId, outletId);
            where.append(" AND s.outlet_id = ?");
            args.add(outletId);
        }
        if (sku != null && !sku.isBlank()) {
            where.append(" AND LOWER(p.sku) = ?");
            args.add(sku.strip().toLowerCase(Locale.ROOT));
        }
        if (from != null) {
            where.append(" AND s.occurred_at >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND s.occurred_at < ?");
            args.add(to);
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ApiProblem("DATE_RANGE_INVALID",
                    "Thời điểm bắt đầu phải trước thời điểm kết thúc.", HttpStatus.BAD_REQUEST);
        }
        long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sellout_record s JOIN product p ON p.id = s.product_id
                WHERE
                """ + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<SelloutView> rows = jdbc.query("""
                SELECT s.id, s.npp_tenant_id, n.name AS npp_name, s.outlet_id,
                    o.code AS outlet_code, o.name AS outlet_name, p.id AS product_id,
                    p.sku, p.name AS product_name, s.unit_code, s.quantity,
                    s.occurred_at, s.source, s.created_by
                FROM sellout_record s
                JOIN tenant n ON n.id = s.npp_tenant_id
                JOIN product p ON p.id = s.product_id
                LEFT JOIN outlet o ON o.id = s.outlet_id
                WHERE
                """ + where + " ORDER BY s.occurred_at DESC, s.id LIMIT ? OFFSET ?",
                (rs, row) -> new SelloutView(
                        rs.getObject("id", UUID.class), rs.getObject("npp_tenant_id", UUID.class),
                        rs.getString("npp_name"), rs.getObject("outlet_id", UUID.class),
                        rs.getString("outlet_code"), rs.getString("outlet_name"),
                        rs.getObject("product_id", UUID.class), rs.getString("sku"),
                        rs.getString("product_name"), rs.getString("unit_code"),
                        rs.getLong("quantity"), rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getString("source"), rs.getObject("created_by", UUID.class)),
                args.toArray());
        return ApiPage.of(rows, page, size, total);
    }

    @Transactional
    public InventoryView updateInventory(
            Jwt jwt, InventoryRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        UUID nppId = requireNpp(jwt, actor, "inventory:update", request.nppTenantId());
        if (request.quantity() < 0) {
            throw new ApiProblem("QUANTITY_NEGATIVE", "Số lượng tồn không được âm.", HttpStatus.BAD_REQUEST);
        }
        ProductUnit product = requireProductUnit(nppId, request.sku(), request.unitCode());
        long baseQuantity = baseQuantity(request.quantity(), product.baseUnits());
        OffsetDateTime recordedAt = request.recordedAt() == null
                ? OffsetDateTime.now(ZoneOffset.UTC) : request.recordedAt().withOffsetSameInstant(ZoneOffset.UTC);
        UUID recordId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_npp (
                    id, tenant_id, npp_tenant_id, product_id, unit_code, quantity, recorded_at, recorded_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, recordId, nppId, nppId, product.id(), product.baseUnitCode(),
                baseQuantity, recordedAt, actor.userId());
        InventoryView result = loadInventoryRecord(recordId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "inventory:update",
                "INVENTORY_SNAPSHOT_CREATED", "inventory_npp", recordId, null, result,
                request.reason(), servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public SelloutView createSellout(
            Jwt jwt, SelloutRequest request, HttpServletRequest servletRequest) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        UUID nppId = requireNpp(jwt, actor, "sellout_data:submit", request.nppTenantId());
        if (request.quantity() <= 0) {
            throw new ApiProblem("QUANTITY_NOT_POSITIVE",
                    "Số lượng bán ra phải lớn hơn 0.", HttpStatus.BAD_REQUEST);
        }
        if (request.outletCode() == null || request.outletCode().isBlank()) {
            throw new ApiProblem("OUTLET_REQUIRED",
                    "Cần chọn điểm bán cho dữ liệu sell-out.", HttpStatus.BAD_REQUEST);
        }
        ProductUnit product = requireProductUnit(nppId, request.sku(), request.unitCode());
        long baseQuantity = baseQuantity(request.quantity(), product.baseUnits());
        OffsetDateTime occurredAt = request.occurredAt() == null
                ? OffsetDateTime.now(ZoneOffset.UTC) : request.occurredAt().withOffsetSameInstant(ZoneOffset.UTC);
        UUID outletId = resolveOutlet(nppId, request.outletCode(), occurredAt);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sellout_record (
                    id, tenant_id, npp_tenant_id, outlet_id, product_id,
                    unit_code, quantity, occurred_at, source, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?)
                """, id, nppId, nppId, outletId, product.id(), product.baseUnitCode(),
                baseQuantity, occurredAt, actor.userId());
        SelloutView result = loadSelloutRecord(id);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), "sellout_data:submit",
                "SELLOUT_RECORD_CREATED", "sellout_record", id, null, result,
                request.reason(), servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    @Transactional
    public ImportBatchView previewInventoryImport(
            Jwt jwt, UUID requestedNppId, MultipartFile file, HttpServletRequest servletRequest) {
        return previewImport(jwt, requestedNppId, file, "INVENTORY", "inventory:update", servletRequest);
    }

    @Transactional
    public ImportBatchView previewSelloutImport(
            Jwt jwt, UUID requestedNppId, MultipartFile file, HttpServletRequest servletRequest) {
        return previewImport(jwt, requestedNppId, file, "SELLOUT", "sellout_data:submit", servletRequest);
    }

    @Transactional
    public ImportBatchView commitImport(
            Jwt jwt, UUID batchId, HttpServletRequest servletRequest) {
        DatabaseCapacityPolicy.requireCapacityForHeavyWrite(jdbc);
        BusinessAccessService.Actor actor = access.actor(jwt);
        ImportBatch batch = requireImportBatch(jwt, actor, batchId, true);
        if ("COMMITTED".equals(batch.status())) {
            return loadImportBatch(batchId, batch.tenantId());
        }
        if (batch.invalidCount() != 0) {
            throw new ApiProblem("IMPORT_HAS_INVALID_ROWS",
                    "Không thể nhập dữ liệu khi bản xem trước còn dòng lỗi.",
                    HttpStatus.CONFLICT, Map.of("invalidCount", batch.invalidCount()));
        }
        List<ImportDataRow> rows = jdbc.query("""
                SELECT row_data::text AS row_data FROM data_import_row
                WHERE tenant_id = ? AND batch_id = ? AND valid = TRUE
                ORDER BY line_number
                """, (rs, row) -> new ImportDataRow(
                parseJson(rs.getString("row_data"))), batch.tenantId(), batch.id());
        for (ImportDataRow row : rows) {
            JsonNode data = row.data();
            UUID productId = UUID.fromString(data.path("productId").asText());
            String baseUnitCode = data.path("baseUnitCode").asText();
            long quantity = data.path("baseQuantity").asLong();
            OffsetDateTime occurredAt = OffsetDateTime.parse(data.path("occurredAt").asText());
            if ("INVENTORY".equals(batch.importType())) {
                jdbc.update("""
                        INSERT INTO inventory_npp (
                            id, tenant_id, npp_tenant_id, product_id, unit_code, quantity,
                            recorded_at, recorded_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), batch.tenantId(), batch.nppTenantId(), productId,
                        baseUnitCode, quantity, occurredAt, actor.userId());
            } else {
                String outlet = data.path("outletId").asText("");
                jdbc.update("""
                        INSERT INTO sellout_record (
                            id, tenant_id, npp_tenant_id, outlet_id, product_id, unit_code,
                            quantity, occurred_at, source, created_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'IMPORT', ?)
                        """, UUID.randomUUID(), batch.tenantId(), batch.nppTenantId(),
                        outlet.isBlank() ? null : UUID.fromString(outlet), productId,
                        baseUnitCode, quantity, occurredAt, actor.userId());
            }
        }
        int changed = jdbc.update("""
                UPDATE data_import_batch SET status = 'COMMITTED',
                    committed_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND tenant_id = ? AND version = ? AND status = 'PREVIEWED'
                """, batch.id(), batch.tenantId(), batch.version());
        if (changed == 0) {
            throw new ApiProblem("IMPORT_VERSION_CONFLICT",
                    "Lô nhập đã được cập nhật, vui lòng tải lại.", HttpStatus.CONFLICT);
        }
        ImportBatchView result = loadImportBatch(batch.id(), batch.tenantId());
        String permission = "INVENTORY".equals(batch.importType())
                ? "inventory:update" : "sellout_data:submit";
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), permission, "DATA_IMPORT_COMMITTED",
                "data_import_batch", batch.id(), batch, result,
                "Xác nhận nhập " + batch.rowCount() + " dòng",
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    public ImportBatchView getImportBatch(Jwt jwt, UUID batchId) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ImportBatch batch = requireImportBatch(jwt, actor, batchId, false);
        return loadImportBatch(batchId, batch.tenantId());
    }

    public ApiPage<ImportRowView> listImportRows(
            Jwt jwt, UUID batchId, int page, int size) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        ImportBatch batch = requireImportBatch(jwt, actor, batchId, false);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM data_import_row WHERE tenant_id = ? AND batch_id = ?
                """, Long.class, batch.tenantId(), batch.id());
        List<ImportRowView> rows = jdbc.query("""
                SELECT line_number, row_data::text AS row_data, errors::text AS errors, valid
                FROM data_import_row WHERE tenant_id = ? AND batch_id = ?
                ORDER BY line_number LIMIT ? OFFSET ?
                """, (rs, row) -> new ImportRowView(rs.getInt("line_number"),
                parseJson(rs.getString("row_data")),
                parseJson(rs.getString("errors")), rs.getBoolean("valid")),
                batch.tenantId(), batch.id(), size, (long) page * size);
        return ApiPage.of(rows, page, size, total == null ? 0 : total);
    }

    private ImportBatchView previewImport(
            Jwt jwt, UUID requestedNppId, MultipartFile file, String type, String permission,
            HttpServletRequest servletRequest) {
        DatabaseCapacityPolicy.requireCapacityForHeavyWrite(jdbc);
        BusinessAccessService.Actor actor = access.actor(jwt);
        UUID nppId = requireNpp(jwt, actor, permission, requestedNppId);
        byte[] content = readFile(file);
        String hash = sha256(content);
        ImportBatch existing = findImportBatch(nppId, type, hash);
        if (existing != null) {
            return loadImportBatch(existing.id(), nppId);
        }
        String originalFilename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String filename = FILE_NAME_SEPARATOR.matcher(originalFilename).replaceAll("_");
        if (filename.length() > 255) {
            filename = filename.substring(filename.length() - 255);
        }
        List<List<String>> sheet = parseSheet(filename, content);
        if (sheet.size() < 2) {
            throw invalidImport("IMPORT_EMPTY", "Tệp phải có dòng tiêu đề và ít nhất một dòng dữ liệu.");
        }
        Map<String, Integer> columns = columnIndexes(sheet.get(0));
        UUID batchId = UUID.randomUUID();
        UUID insertedBatchId = jdbc.query("""
                INSERT INTO data_import_batch (
                    id, tenant_id, npp_tenant_id, import_type, file_sha256, file_name,
                    status, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'PREVIEWED', ?)
                ON CONFLICT (tenant_id, import_type, file_sha256) DO NOTHING
                RETURNING id
                """, rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                batchId, nppId, nppId, type, hash, filename, actor.userId());
        if (insertedBatchId == null) {
            ImportBatch concurrent = findImportBatch(nppId, type, hash);
            if (concurrent != null) {
                return loadImportBatch(concurrent.id(), nppId);
            }
            throw new ApiProblem("IMPORT_CONFLICT",
                    "Tệp nhập đang được xử lý; vui lòng thử lại.", HttpStatus.CONFLICT);
        }
        int validCount = 0;
        int invalidCount = 0;
        List<String> required = "INVENTORY".equals(type)
                ? List.of("sku", "unit", "quantity", "recordedat")
                : List.of("sku", "unit", "quantity", "occurredat");
        List<String> absent = required.stream().filter(column -> !columns.containsKey(column)).toList();
        if (!absent.isEmpty()) {
            throw new ApiProblem("IMPORT_HEADER_INVALID",
                    "Thiếu cột bắt buộc trong tệp nhập.", HttpStatus.BAD_REQUEST,
                    Map.of("missingColumns", absent));
        }
        if (sheet.size() - 1 > MAX_IMPORT_ROWS) {
            throw invalidImport("IMPORT_TOO_MANY_ROWS",
                    "Tệp nhập vượt quá giới hạn 10.000 dòng.");
        }
        for (int i = 1; i < sheet.size(); i++) {
            List<String> values = sheet.get(i);
            if (values.stream().allMatch(String::isBlank)) {
                continue;
            }
            ValidatedImportRow validated = validateImportRow(
                    nppId, type, columns, values, i + 1);
            validCount += validated.errors().isEmpty() ? 1 : 0;
            invalidCount += validated.errors().isEmpty() ? 0 : 1;
            insertImportRow(batchId, nppId, i + 1, validated.data(), validated.errors());
        }
        if (validCount + invalidCount == 0) {
            throw invalidImport("IMPORT_EMPTY", "Tệp không có dòng dữ liệu để nhập.");
        }
        jdbc.update("""
                UPDATE data_import_batch SET row_count = ?, valid_count = ?, invalid_count = ?
                WHERE id = ? AND tenant_id = ?
                """, validCount + invalidCount, validCount, invalidCount, batchId, nppId);
        ImportBatchView result = loadImportBatch(batchId, nppId);
        audit.recordIfSensitive(actor.tenantId(), actor.userId(), permission, "DATA_IMPORT_PREVIEWED",
                "data_import_batch", batchId, null, result, filename,
                servletRequest.getRemoteAddr(), jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return result;
    }

    private ValidatedImportRow validateImportRow(
            UUID nppId, String type, Map<String, Integer> columns, List<String> values, int lineNumber) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();
        String sku = value(columns, values, "sku");
        String unit = value(columns, values, "unit");
        long quantity = parseQuantity(value(columns, values, "quantity"), errors);
        String dateField = "INVENTORY".equals(type) ? "recordedat" : "occurredat";
        OffsetDateTime occurredAt = parseDate(value(columns, values, dateField), errors);
        ProductUnit product = null;
        try {
            product = requireProductUnit(nppId, sku, unit);
        } catch (ApiProblem problem) {
            errors.add(problem.getMessage());
        }
        long baseQuantity = 0;
        if (product != null && quantity >= 0) {
            try {
                baseQuantity = baseQuantity(quantity, product.baseUnits());
            } catch (ApiProblem problem) {
                errors.add(problem.getMessage());
            }
        }
        if ("SELLOUT".equals(type) && quantity <= 0) {
            errors.add("Số lượng bán ra phải lớn hơn 0.");
        }
        String outletCode = value(columns, values, "outletcode");
        if ("SELLOUT".equals(type) && outletCode.isBlank()) {
            errors.add("Cần nhập mã điểm bán cho dòng sell-out.");
        }
        UUID outletId = null;
        if ("SELLOUT".equals(type) && !outletCode.isBlank()) {
            try {
                outletId = resolveOutlet(nppId, outletCode, occurredAt);
            } catch (ApiProblem problem) {
                errors.add(problem.getMessage());
            }
        }
        data.put("sku", sku);
        data.put("unitCode", unit);
        data.put("quantity", quantity);
        data.put("baseQuantity", baseQuantity);
        data.put("occurredAt", occurredAt == null ? "" : occurredAt.toString());
        data.put("productId", product == null ? "" : product.id().toString());
        data.put("baseUnitCode", product == null ? "" : product.baseUnitCode());
        data.put("outletId", outletId == null ? "" : outletId.toString());
        data.put("lineNumber", lineNumber);
        return new ValidatedImportRow(data, errors);
    }

    private List<List<String>> parseSheet(String filename, byte[] content) {
        String lowerName = filename.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".csv")) {
            return parseCsv(new String(content, StandardCharsets.UTF_8));
        }
        if (!lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xls")) {
            throw invalidImport("IMPORT_FILE_TYPE_INVALID", "Chỉ hỗ trợ tệp Excel (.xlsx/.xls) hoặc CSV.");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw invalidImport("IMPORT_SHEET_MISSING", "Tệp Excel không có trang tính.");
            }
            var sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<List<String>> rows = new ArrayList<>();
            int lastRow = sheet.getLastRowNum();
            if (lastRow >= MAX_IMPORT_ROWS + 1) {
                throw invalidImport("IMPORT_TOO_MANY_ROWS", "Tệp nhập vượt quá giới hạn 10.000 dòng.");
            }
            int lastColumn = sheet.getRow(0) == null ? 0 : sheet.getRow(0).getLastCellNum();
            for (int rowIndex = 0; rowIndex <= lastRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                List<String> values = new ArrayList<>();
                for (int column = 0; column < lastColumn; column++) {
                    Cell cell = row == null ? null : row.getCell(column);
                    values.add(cellValue(cell, formatter));
                }
                rows.add(values);
            }
            return rows;
        } catch (EncryptedDocumentException exception) {
            throw new ApiProblem("IMPORT_FILE_ENCRYPTED",
                    "Không hỗ trợ tệp Excel được mã hóa.", HttpStatus.BAD_REQUEST);
        } catch (IOException exception) {
            throw new ApiProblem("IMPORT_FILE_INVALID",
                    "Không thể đọc nội dung tệp Excel.", HttpStatus.BAD_REQUEST);
        }
    }

    private String cellValue(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toString();
        }
        return formatter.formatCellValue(cell).strip();
    }

    private List<List<String>> parseCsv(String text) {
        String normalized = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < normalized.length() && normalized.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                row.add(value.toString().strip());
                value.setLength(0);
            } else if ((current == '\n' || current == '\r') && !quoted) {
                if (current == '\r' && i + 1 < normalized.length() && normalized.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(value.toString().strip());
                value.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                value.append(current);
            }
        }
        if (quoted) {
            throw invalidImport("IMPORT_CSV_MALFORMED", "Tệp CSV có dấu nháy không khớp.");
        }
        if (!row.isEmpty() || value.length() > 0) {
            row.add(value.toString().strip());
            rows.add(row);
        }
        if (rows.size() > MAX_IMPORT_ROWS + 1) {
            throw invalidImport("IMPORT_TOO_MANY_ROWS", "Tệp nhập vượt quá giới hạn 10.000 dòng.");
        }
        return rows;
    }

    private Map<String, Integer> columnIndexes(List<String> header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String normalized = normalizeColumn(header.get(i));
            if (!normalized.isBlank() && columns.putIfAbsent(normalized, i) != null) {
                throw invalidImport("IMPORT_HEADER_DUPLICATE",
                        "Tệp nhập có tên cột bị trùng: " + header.get(i));
            }
        }
        return columns;
    }

    private String normalizeColumn(String value) {
        return value.replace("_", "").replace(" ", "").strip().toLowerCase(Locale.ROOT);
    }

    private String value(Map<String, Integer> columns, List<String> values, String name) {
        Integer index = columns.get(name);
        return index == null || index >= values.size() ? "" : values.get(index).strip();
    }

    private long parseQuantity(String text, List<String> errors) {
        try {
            String normalized = text.strip();
            if (normalized.matches("[+-]?\\d{1,3}(,\\d{3})+")) {
                normalized = normalized.replace(",", "");
            }
            long quantity = Long.parseLong(normalized);
            if (quantity < 0) {
                errors.add("Số lượng không được âm.");
            }
            return quantity;
        } catch (NumberFormatException exception) {
            errors.add("Số lượng phải là số nguyên.");
            return -1;
        }
    }

    private OffsetDateTime parseDate(String text, List<String> errors) {
        if (text == null || text.isBlank()) {
            errors.add("Thiếu ngày ghi nhận.");
            return null;
        }
        try {
            return OffsetDateTime.parse(text.strip()).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(text.strip()).atStartOfDay(BUSINESS_ZONE)
                        .toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException exception) {
                try {
                    return LocalDateTime.parse(text.strip()).atZone(BUSINESS_ZONE)
                            .toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException invalid) {
                    errors.add("Ngày phải theo định dạng ISO (yyyy-MM-dd hoặc ISO datetime).");
                    return null;
                }
            }
        }
    }

    private ProductUnit requireProductUnit(UUID nppId, String sku, String unitCode) {
        if (sku == null || sku.isBlank() || unitCode == null || unitCode.isBlank()) {
            throw new ApiProblem("IMPORT_PRODUCT_FIELDS_REQUIRED",
                    "Cần nhập SKU và đơn vị.", HttpStatus.BAD_REQUEST);
        }
        List<ProductUnit> matches = jdbc.query("""
                SELECT p.id, p.base_unit_code, uc.base_units
                FROM product p
                JOIN unit_conversion uc ON uc.tenant_id = p.tenant_id AND uc.product_id = p.id
                WHERE LOWER(p.sku) = ? AND LOWER(uc.unit_code) = ?
                  AND p.status = 'ACTIVE'
                  AND EXISTS (
                      SELECT 1 FROM tenant_relation tr
                      WHERE tr.nsx_tenant_id = p.tenant_id AND tr.npp_tenant_id = ?
                        AND tr.status = 'ACTIVE' AND tr.effective_from <= ?
                        AND (tr.effective_to IS NULL OR tr.effective_to >= ?)
                  )
                ORDER BY p.id
                """, (rs, row) -> new ProductUnit(rs.getObject("id", UUID.class),
                rs.getString("base_unit_code"), rs.getLong("base_units")),
                sku.strip().toLowerCase(Locale.ROOT), unitCode.strip().toLowerCase(Locale.ROOT),
                nppId, access.businessToday(), access.businessToday());
        if (matches.isEmpty()) {
            throw new ApiProblem("PRODUCT_OR_UNIT_NOT_FOUND",
                    "Không tìm thấy SKU/đơn vị trong danh mục NSX đang liên kết.", HttpStatus.BAD_REQUEST);
        }
        if (matches.size() > 1) {
            throw new ApiProblem("PRODUCT_SKU_AMBIGUOUS",
                    "SKU trùng ở nhiều NSX liên kết; cần chọn sản phẩm cụ thể.",
                    HttpStatus.BAD_REQUEST, Map.of("sku", sku));
        }
        return matches.get(0);
    }

    private long baseQuantity(long quantity, long baseUnits) {
        try {
            return Math.multiplyExact(quantity, baseUnits);
        } catch (ArithmeticException exception) {
            throw new ApiProblem("QUANTITY_OVERFLOW",
                    "Số lượng sau quy đổi vượt giới hạn cho phép.", HttpStatus.BAD_REQUEST);
        }
    }

    private UUID resolveOutlet(UUID nppId, String outletCode, OffsetDateTime occurredAt) {
        if (outletCode == null || outletCode.isBlank()) {
            return null;
        }
        LocalDate occurredDate = occurredAt == null
                ? LocalDate.now(BUSINESS_ZONE) : occurredAt.toInstant().atZone(BUSINESS_ZONE).toLocalDate();
        List<UUID> matches = jdbc.query("""
                SELECT o.id FROM outlet o
                JOIN outlet_npp onp ON onp.outlet_id = o.id AND onp.npp_tenant_id = ?
                WHERE LOWER(o.code) = LOWER(?) AND o.deleted_at IS NULL
                  AND onp.status = 'ACTIVE' AND onp.effective_from <= ?
                  AND (onp.effective_to IS NULL OR onp.effective_to >= ?)
                ORDER BY o.id
                """, (rs, row) -> rs.getObject("id", UUID.class),
                nppId, outletCode.strip(), occurredDate, occurredDate);
        if (matches.isEmpty()) {
            throw new ApiProblem("OUTLET_NOT_SERVED",
                    "Điểm bán không thuộc mạng lưới phục vụ của NPP.", HttpStatus.BAD_REQUEST);
        }
        if (matches.size() > 1) {
            throw new ApiProblem("OUTLET_CODE_AMBIGUOUS",
                    "Mã điểm bán không duy nhất trong NPP.", HttpStatus.BAD_REQUEST);
        }
        return matches.get(0);
    }

    private UUID requireNpp(
            Jwt jwt, BusinessAccessService.Actor actor, String permission, UUID requestedNppId) {
        if ("NPP".equals(actor.tenantType())) {
            if (requestedNppId != null && !actor.tenantId().equals(requestedNppId)) {
                throw new AccessDeniedException("NPP chỉ được ghi dữ liệu của chính mình.");
            }
            return actor.tenantId();
        }
        if ("NSX".equals(actor.tenantType()) && requestedNppId != null) {
            partnerAccess.requireNpp(jwt, actor, permission, requestedNppId);
            return requestedNppId;
        }
        throw new AccessDeniedException("Chức năng này chỉ dành cho NPP hoặc NSX có phạm vi được cấp.");
    }

    private NppVisibility nppVisibility(
            Jwt jwt, BusinessAccessService.Actor actor, String permission,
            UUID requestedNppId, String alias) {
        if ("NPP".equals(actor.tenantType())) {
            if (requestedNppId != null && !actor.tenantId().equals(requestedNppId)) {
                throw new AccessDeniedException("NPP chỉ được xem dữ liệu của chính mình.");
            }
            return new NppVisibility(alias + ".npp_tenant_id = ?", List.of(actor.tenantId()));
        }
        if (!"NSX".equals(actor.tenantType())) {
            throw new AccessDeniedException("Chỉ NSX hoặc NPP được truy cập dữ liệu này.");
        }
        PartnerAccessService.Filter npps = partnerAccess.npps(jwt, actor, permission, "n");
        List<Object> args = new ArrayList<>(npps.arguments());
        String sql = alias + ".npp_tenant_id IN (SELECT n.id FROM tenant n WHERE " + npps.sql() + ")";
        if (requestedNppId != null) {
            partnerAccess.requireNpp(jwt, actor, permission, requestedNppId);
            sql += " AND " + alias + ".npp_tenant_id = ?";
            args.add(requestedNppId);
        }
        return new NppVisibility(sql, args);
    }

    private void requireOutletAccess(
            Jwt jwt, BusinessAccessService.Actor actor, String permission,
            UUID requestedNppId, UUID outletId) {
        UUID nppId = "NPP".equals(actor.tenantType())
                ? actor.tenantId() : requestedNppId;
        if (nppId == null) {
            throw new ApiProblem("NPP_REQUIRED", "Cần chọn NPP để lọc theo điểm bán.",
                    HttpStatus.BAD_REQUEST);
        }
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM outlet_npp
                    WHERE outlet_id = ? AND npp_tenant_id = ? AND status = 'ACTIVE'
                      AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                )
                """, Boolean.class, outletId, nppId, today, today);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new AccessDeniedException("Điểm bán nằm ngoài phạm vi NPP.");
        }
    }

    private ImportBatch findImportBatch(UUID tenantId, String type, String hash) {
        return jdbc.query("""
                SELECT id, tenant_id, npp_tenant_id, import_type, status, row_count,
                    valid_count, invalid_count, version
                FROM data_import_batch
                WHERE tenant_id = ? AND import_type = ? AND file_sha256 = ?
                """, rs -> rs.next() ? importBatch(rs) : null, tenantId, type, hash);
    }

    private ImportBatch requireImportBatch(
            Jwt jwt, BusinessAccessService.Actor actor, UUID batchId, boolean forUpdate) {
        if (!"NPP".equals(actor.tenantType()) && !"NSX".equals(actor.tenantType())) {
            throw new AccessDeniedException("Không được truy cập lô nhập dữ liệu.");
        }
        ImportBatch batch = jdbc.query("""
                SELECT b.id, b.tenant_id, b.npp_tenant_id, b.import_type, b.status,
                    b.row_count, b.valid_count, b.invalid_count, b.version
                FROM data_import_batch b WHERE b.id = ?
                """, rs -> rs.next() ? importBatch(rs) : null, batchId);
        if (batch == null) {
            throw new AccessDeniedException("Lô nhập không tồn tại hoặc ngoài phạm vi được phân quyền.");
        }
        if (!batch.tenantId().equals(batch.nppTenantId())) {
            throw new AccessDeniedException("Lô nhập không hợp lệ.");
        }
        String permission = forUpdate
                ? ("INVENTORY".equals(batch.importType()) ? "inventory:update" : "sellout_data:submit")
                : ("INVENTORY".equals(batch.importType()) ? "inventory:view" : "sellout_data:view");
        access.resolve(jwt, permission);
        if ("NPP".equals(actor.tenantType())) {
            if (!actor.tenantId().equals(batch.nppTenantId())) {
                throw new AccessDeniedException("Lô nhập nằm ngoài phạm vi NPP.");
            }
        } else {
            partnerAccess.requireNpp(jwt, actor, permission, batch.nppTenantId());
        }
        if (forUpdate) {
            ImportBatch locked = jdbc.query("""
                    SELECT b.id, b.tenant_id, b.npp_tenant_id, b.import_type, b.status,
                        b.row_count, b.valid_count, b.invalid_count, b.version
                    FROM data_import_batch b
                    WHERE b.id = ? AND b.tenant_id = ? AND b.npp_tenant_id = ?
                    FOR UPDATE
                    """, rs -> rs.next() ? importBatch(rs) : null,
                    batch.id(), batch.tenantId(), batch.nppTenantId());
            if (locked == null) {
                throw new AccessDeniedException("Lô nhập không tồn tại hoặc ngoài phạm vi được phân quyền.");
            }
            batch = locked;
        }
        return batch;
    }

    private ImportBatchView loadImportBatch(UUID id, UUID tenantId) {
        return jdbc.query("""
                SELECT id, tenant_id, npp_tenant_id, import_type, file_name, status,
                    row_count, valid_count, invalid_count, created_by, created_at,
                    committed_at, version
                FROM data_import_batch WHERE id = ? AND tenant_id = ?
                """, rs -> rs.next() ? new ImportBatchView(
                rs.getObject("id", UUID.class), rs.getObject("npp_tenant_id", UUID.class),
                rs.getString("import_type"),
                rs.getString("file_name"), rs.getString("status"), rs.getInt("row_count"),
                rs.getInt("valid_count"), rs.getInt("invalid_count"),
                rs.getObject("created_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("committed_at", OffsetDateTime.class), rs.getLong("version")) : null,
                id, tenantId);
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Dữ liệu xem trước trong cơ sở dữ liệu không hợp lệ.", exception);
        }
    }

    private ImportBatch importBatch(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ImportBatch(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("npp_tenant_id", UUID.class), rs.getString("import_type"),
                rs.getString("status"), rs.getInt("row_count"), rs.getInt("valid_count"),
                rs.getInt("invalid_count"), rs.getLong("version"));
    }

    private InventoryView loadInventoryRecord(UUID id) {
        return jdbc.query("""
                SELECT i.id, i.npp_tenant_id, n.name AS npp_name, p.id AS product_id,
                    p.sku, p.name AS product_name, i.unit_code, i.quantity, i.recorded_at, i.recorded_by
                FROM inventory_npp i JOIN tenant n ON n.id = i.npp_tenant_id
                JOIN product p ON p.id = i.product_id WHERE i.id = ?
                """, rs -> rs.next() ? new InventoryView(
                rs.getObject("id", UUID.class), rs.getObject("npp_tenant_id", UUID.class),
                rs.getString("npp_name"), rs.getObject("product_id", UUID.class),
                rs.getString("sku"), rs.getString("product_name"), rs.getString("unit_code"),
                rs.getLong("quantity"), rs.getObject("recorded_at", OffsetDateTime.class),
                rs.getObject("recorded_by", UUID.class)) : null, id);
    }

    private SelloutView loadSelloutRecord(UUID id) {
        return jdbc.query("""
                SELECT s.id, s.npp_tenant_id, n.name AS npp_name, s.outlet_id,
                    o.code AS outlet_code, o.name AS outlet_name, p.id AS product_id,
                    p.sku, p.name AS product_name, s.unit_code, s.quantity, s.occurred_at,
                    s.source, s.created_by
                FROM sellout_record s JOIN tenant n ON n.id = s.npp_tenant_id
                JOIN product p ON p.id = s.product_id LEFT JOIN outlet o ON o.id = s.outlet_id
                WHERE s.id = ?
                """, rs -> rs.next() ? new SelloutView(
                rs.getObject("id", UUID.class), rs.getObject("npp_tenant_id", UUID.class),
                rs.getString("npp_name"), rs.getObject("outlet_id", UUID.class),
                rs.getString("outlet_code"), rs.getString("outlet_name"),
                rs.getObject("product_id", UUID.class), rs.getString("sku"),
                rs.getString("product_name"), rs.getString("unit_code"), rs.getLong("quantity"),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("source"),
                rs.getObject("created_by", UUID.class)) : null, id);
    }

    private void insertImportRow(
            UUID batchId, UUID tenantId, int lineNumber, Map<String, Object> data, List<String> errors) {
        try {
            jdbc.update("""
                    INSERT INTO data_import_row (id, tenant_id, batch_id, line_number, row_data, errors, valid)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                    """, UUID.randomUUID(), tenantId, batchId, lineNumber,
                    objectMapper.writeValueAsString(data), objectMapper.writeValueAsString(errors),
                    errors.isEmpty());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể lưu dữ liệu xem trước.", exception);
        }
    }

    private byte[] readFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalidImport("IMPORT_FILE_REQUIRED", "Cần tải lên tệp dữ liệu.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw invalidImport("IMPORT_FILE_TOO_LARGE", "Dung lượng tệp không được vượt quá 5 MB.");
        }
        try {
            byte[] content = file.getBytes();
            if (content.length > MAX_FILE_BYTES) {
                throw invalidImport("IMPORT_FILE_TOO_LARGE", "Dung lượng tệp không được vượt quá 5 MB.");
            }
            return content;
        } catch (IOException exception) {
            throw new ApiProblem("IMPORT_FILE_READ_FAILED",
                    "Không thể đọc tệp tải lên.", HttpStatus.BAD_REQUEST);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 không khả dụng.", exception);
        }
    }

    private ApiProblem invalidImport(String code, String message) {
        return new ApiProblem(code, message, HttpStatus.BAD_REQUEST);
    }

    private record NppVisibility(String sql, List<Object> arguments) {
    }

    private record ProductUnit(UUID id, String baseUnitCode, long baseUnits) {
    }

    private record ValidatedImportRow(Map<String, Object> data, List<String> errors) {
    }

    private record ImportBatch(
            UUID id, UUID tenantId, UUID nppTenantId, String importType, String status,
            int rowCount, int validCount, int invalidCount, long version) {
    }

    private record ImportDataRow(JsonNode data) {
    }

    public record InventoryRequest(
            UUID nppTenantId, @NotBlank @Size(max = 80) String sku,
            @NotBlank @Size(max = 32) String unitCode, @PositiveOrZero long quantity,
            OffsetDateTime recordedAt, String reason) {
    }

    public record SelloutRequest(
            UUID nppTenantId, @NotBlank @Size(max = 80) String sku,
            @NotBlank @Size(max = 32) String unitCode, @Positive long quantity,
            @NotBlank @Size(max = 80) String outletCode,
            OffsetDateTime occurredAt, String reason) {
    }

    public record InventoryView(
            UUID id, UUID nppTenantId, String nppName, UUID productId, String sku,
            String productName, String unitCode, long quantity, OffsetDateTime recordedAt,
            UUID recordedBy) {
    }

    public record SelloutView(
            UUID id, UUID nppTenantId, String nppName, UUID outletId, String outletCode,
            String outletName, UUID productId, String sku, String productName, String unitCode,
            long quantity, OffsetDateTime occurredAt, String source, UUID createdBy) {
    }

    public record ImportBatchView(
            UUID id, UUID nppTenantId, String importType, String fileName, String status,
            int rowCount, int validCount, int invalidCount, UUID createdBy,
            OffsetDateTime createdAt, OffsetDateTime committedAt, long version) {
    }

    public record ImportRowView(int lineNumber, JsonNode data, JsonNode errors, boolean valid) {
    }
}
