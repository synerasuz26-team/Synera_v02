package com.synera.api.importing;

import com.synera.api.auth.ApiProblem;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.*;

@Component
public class FoundationWorkbookReader {
    public static final int MAX_ROWS = 5000;
    public static final Map<String, List<String>> HEADERS = Map.ofEntries(
            Map.entry("products", List.of("sku_code","name","category","base_unit","case_unit","units_per_case","note")),
            Map.entry("npp", List.of("npp_code","name","org_unit_code","address","credit_limit_vnd","debt_current_vnd","credit_remaining_vnd")),
            Map.entry("outlets", List.of("outlet_code","name","outlet_type","address","contact_name","org_unit_code","route_code","status","contact_phone","contact_email","npp_code")),
            Map.entry("outlet_npp", List.of("outlet_code","npp_code")),
            Map.entry("org_units", List.of("org_code","parent_code","name","level","path")),
            Map.entry("routes", List.of("route_code","name","org_unit_code","sales_username","supervisor_username")),
            Map.entry("route_outlets", List.of("route_code","outlet_code","visit_order","mcp_weekday","mcp_weeks")),
            Map.entry("users", List.of("username","full_name","role_code","tenant_code","org_unit_code","route_code","password","email")),
            Map.entry("inventory_npp", List.of("npp_code","sku_code","stock_case","sellout_7d_case","days_cover","stock_status","updated_at")));

    public Parsed read(ImportKind kind, MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 5 * 1024 * 1024)
            throw bad("Tệp phải có dữ liệu và không vượt quá 5 MB.");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = file.getBytes();
            if (name.endsWith(".csv")) {
                var rows=readRows(kind.sheets.getFirst(), csv(new String(bytes, StandardCharsets.UTF_8)));
                return new Parsed(rows,hasDemo(rows),bytes);
            }
            if (!name.endsWith(".xlsx")) throw bad("Chỉ hỗ trợ tệp .xlsx hoặc CSV UTF-8.");
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                List<InputRow> result = new ArrayList<>();
                boolean demo = false;
                Sheet tenants = workbook.getSheet("tenants");
                if (tenants != null) {
                    List<InputRow> tenantRows = readRows("tenants", values(tenants));
                    demo = hasDemo(tenantRows);
                }
                for (String sheetName : kind.sheets) {
                    Sheet sheet = workbook.getSheet(sheetName);
                    if (sheet == null && sheetName.equals(kind.sheets.getFirst())) throw bad("Thiếu sheet " + sheetName + ". Hãy tải mẫu đúng loại dữ liệu.");
                    if (sheet != null) result.addAll(readRows(sheetName, values(sheet)));
                }
                if (result.isEmpty() || result.size() > MAX_ROWS) throw bad("Mỗi lô cần từ 1 đến 5.000 dòng dữ liệu.");
                return new Parsed(result, demo||hasDemo(result), bytes);
            }
        } catch (ApiProblem exception) { throw exception; }
        catch (Exception exception) { var problem=bad("Không đọc được tệp. Kiểm tra định dạng, mã hóa UTF-8 và bỏ mật khẩu bảo vệ workbook."); problem.initCause(exception);throw problem; }
    }
    private List<List<String>> values(Sheet sheet) {
        if (sheet.getLastRowNum() > MAX_ROWS) throw bad("Sheet vượt quá 5.000 dòng.");
        List<List<String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        for (int i=0;i<=sheet.getLastRowNum();i++) {
            Row row=sheet.getRow(i); List<String> cells=new ArrayList<>();
            int count=row==null?0:row.getLastCellNum();
            if(count>64)throw bad("Sheet có quá nhiều cột.");
            for(int j=0;j<count;j++) {
                Cell cell=row.getCell(j);
                // ISO-date cells (OOXML t=d) are valid in XLSX but not represented by POI's CellType enum.
                if(cell instanceof org.apache.poi.xssf.usermodel.XSSFCell xssf) {
                    var type=xssf.getCTCell().getDomNode().getAttributes().getNamedItem("t");
                    if(type!=null&&type.getNodeValue().equals("d")) {
                        String literal=xssf.getCTCell().getV();
                        try { cells.add(java.time.OffsetDateTime.parse(literal).toString()); }
                        catch(java.time.format.DateTimeParseException exception) {
                            cells.add(java.time.LocalDateTime.parse(literal).atOffset(ZoneOffset.ofHours(7)).toString());
                        }
                        continue;
                    }
                }
                if(cell!=null&&cell.getCellType()==CellType.FORMULA)throw bad("Không nhận ô công thức. Hãy dán giá trị trước khi nhập.");
                if(cell!=null&&cell.getCellType()==CellType.NUMERIC&&DateUtil.isCellDateFormatted(cell))
                    cells.add(cell.getLocalDateTimeCellValue().atOffset(ZoneOffset.ofHours(7)).toString());
                else if(cell!=null&&cell.getCellType()==CellType.NUMERIC)cells.add(java.math.BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString());
                else cells.add(cell==null?"":formatter.formatCellValue(cell));
            }
            rows.add(cells);
        }
        return rows;
    }
    List<InputRow> readRows(String sheet, List<List<String>> rows) {
        if(rows.isEmpty())throw bad("Sheet " + sheet + " trống.");
        List<String> header=rows.getFirst().stream().map(value->ImportNormalization.text(value).replace("\uFEFF", "").toLowerCase(Locale.ROOT)).toList();
        if(header.isEmpty()||header.stream().anyMatch(String::isBlank)||new HashSet<>(header).size()!=header.size())throw bad("Dòng tiêu đề có cột trống hoặc trùng tên.");
        List<InputRow> result=new ArrayList<>();
        for(int index=1;index<rows.size();index++) {
            List<String> cells=rows.get(index);
            if(cells.stream().allMatch(String::isBlank))continue;
            if(cells.size()>header.size())throw bad("Dòng "+(index+1)+" có nhiều ô hơn tiêu đề.");
            Map<String,String> data=new LinkedHashMap<>();
            for(int j=0;j<header.size();j++)data.put(header.get(j),j<cells.size()?cells.get(j):"");
            result.add(new InputRow(sheet,index+1,data));
        }
        if(result.size()>MAX_ROWS)throw bad("Mỗi lô không vượt quá 5.000 dòng.");
        return result;
    }
    private static boolean hasDemo(List<InputRow> rows){return rows.stream().anyMatch(row->List.of("1","true","yes","y").contains(ImportNormalization.text(row.data().getOrDefault("is_demo","")).toLowerCase(Locale.ROOT)));}
    static List<List<String>> csv(String content) {
        List<List<String>> rows=new ArrayList<>(); List<String> row=new ArrayList<>(); StringBuilder cell=new StringBuilder(); boolean quoted=false;
        for(int i=0;i<content.length();i++) {
            char c=content.charAt(i);
            if(c=='"') {
                if(quoted&&i+1<content.length()&&content.charAt(i+1)=='"'){cell.append('"');i++;}
                else if(quoted||cell.isEmpty())quoted=!quoted;
                else throw bad("CSV có dấu nháy không hợp lệ.");
            } else if(c==','&&!quoted){row.add(cell.toString());cell.setLength(0);}
            else if((c=='\n'||c=='\r')&&!quoted){if(c=='\r'&&i+1<content.length()&&content.charAt(i+1)=='\n')i++;row.add(cell.toString());rows.add(row);row=new ArrayList<>();cell.setLength(0);}
            else cell.append(c);
        }
        if(quoted)throw bad("CSV thiếu dấu nháy đóng.");
        if(!cell.isEmpty()||!row.isEmpty()){row.add(cell.toString());rows.add(row);}
        return rows;
    }
    static ApiProblem bad(String message) { return new ApiProblem("IMPORT_FILE_INVALID",message,HttpStatus.BAD_REQUEST); }
    public record InputRow(String sheet,int line,Map<String,String> data) { }
    public record Parsed(List<InputRow> rows,boolean demo,byte[] bytes) { }
}
