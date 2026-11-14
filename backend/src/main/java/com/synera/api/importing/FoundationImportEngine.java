package com.synera.api.importing;

import com.synera.api.auth.ApiProblem;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import static com.synera.api.importing.ImportNormalization.*;

@Service
public class FoundationImportEngine {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    public FoundationImportEngine(JdbcTemplate jdbc, PasswordEncoder passwords) { this.jdbc=jdbc; this.passwords=passwords; }

    public List<PreparedRow> prepare(FoundationImportPolicy.Actor actor, ImportKind kind,
            List<FoundationWorkbookReader.InputRow> input, boolean stored) {
        List<PreparedRow> rows = new ArrayList<>();
        for(var row:input) {
            Map<String,String> data=new LinkedHashMap<>();
            row.data().forEach((key,value)->{if(FoundationWorkbookReader.HEADERS.getOrDefault(row.sheet(),List.of()).contains(key)||(stored&&key.equals("_password_hash")))data.put(key,key.equals("password")?value:text(value));});
            PreparedRow prepared=new PreparedRow(row.sheet(),row.line(),data);
            row.data().keySet().stream().filter(key->!key.startsWith("_")&&!FoundationWorkbookReader.HEADERS.getOrDefault(row.sheet(),List.of()).contains(key)).forEach(key->prepared.warnings.add("Cột không sử dụng: "+key));
            if(!stored&&row.data().keySet().stream().anyMatch(key->key.startsWith("_")))prepared.errors.add("Tên cột bắt đầu bằng _ được dành cho hệ thống.");
            data.replaceAll((key,value)->key.endsWith("_code")||List.of("sku_code","org_code","parent_code","base_unit","case_unit").contains(key)?code(value):value);
            if(data.containsKey("username"))data.put("username",text(data.get("username")).toLowerCase(Locale.ROOT));
            for(String key:List.of("sales_username","supervisor_username"))if(data.containsKey(key))data.put(key,text(data.get(key)).toLowerCase(Locale.ROOT));
            String password=data.remove("password");
            if(!stored&&password!=null&&!password.isEmpty()) {
                if(password.length()<12||password.getBytes(StandardCharsets.UTF_8).length>72)prepared.errors.add("Mật khẩu cần ít nhất 12 ký tự và tối đa 72 byte UTF-8.");
                else data.put("_password_hash",passwords.encode(password));
            }
            rows.add(prepared);
        }
        Map<String,PreparedRow> unique=new HashMap<>();
        for(var row:rows) {
            try {
                row.key=businessKey(row);
                PreparedRow previous=unique.putIfAbsent(row.sheet+":"+row.key,row);
                if(previous!=null){row.errors.add("Trùng mã nghiệp vụ trong tệp tại dòng "+previous.line+".");previous.errors.add("Trùng mã nghiệp vụ trong tệp tại dòng "+row.line+".");}
            } catch(IllegalArgumentException exception){row.errors.add(exception.getMessage());}
        }
        var outletSnapshot=new OutletSnapshot(rows.stream().anyMatch(row->Set.of("outlets","outlet_npp","route_outlets").contains(row.sheet))?visibleOutlets(actor):List.of());
        for(var row:rows) {
            try { validate(actor,kind,row,unique,outletSnapshot); }
            catch(IllegalArgumentException exception){row.errors.add(exception.getMessage());}
            if(row.errors.isEmpty()) {
                row.hash=fingerprint(row.data);
                Map<String,Object> identity=one("SELECT target_id, payload_hash FROM foundation_import_identity WHERE tenant_id=? AND sheet_name=? AND business_key=?",actor.tenantId(),identitySheet(kind,row.sheet),row.key);
                row.operation=row.hash.equals(identity.get("payload_hash"))?"UNCHANGED":row.data.containsKey("_existing")?"UPDATE":"CREATE";
            }
        }
        Map<String,PreparedRow> emails=new HashMap<>();
        for(var row:rows)if(row.sheet.equals("users")&&!row.value("email").isEmpty()) {
            var previous=emails.putIfAbsent(row.value("email"),row);
            if(previous!=null){row.errors.add("Trùng email trong tệp ở dòng "+previous.line+".");previous.errors.add("Trùng email trong tệp ở dòng "+row.line+".");}
        }
        Map<String,List<PreparedRow>> outletNames=new HashMap<>();
        for(var left:rows)if(left.sheet.equals("outlets")) {
            var group=outletNames.computeIfAbsent(folded(left.value("name")),ignored->new ArrayList<>());
            for(var right:group)if(!left.key.equals(right.key)&&similarOutlet(left.value("name"),left.value("address"),right.value("name"),right.value("address"))) {
                left.warnings.add("Có thể trùng điểm bán cùng tệp ở dòng "+right.line+" (cùng tên, địa chỉ gần giống).");break;
            }
            if(group.size()<50)group.add(left);
        }
        return rows;
    }
    private void validate(FoundationImportPolicy.Actor actor,ImportKind kind,PreparedRow row,Map<String,PreparedRow> batch,OutletSnapshot outlets) {
        var data=row.data;
        for(var entry:data.entrySet()) {
            if(entry.getValue().length()>2000)throw invalid("Giá trị cột "+entry.getKey()+" vượt quá 2.000 ký tự.");
            if(entry.getKey().endsWith("_code")||List.of("sku_code","org_code","parent_code").contains(entry.getKey()))
                if(!entry.getValue().isEmpty()&&!entry.getValue().matches("[A-Z0-9._-]{1,80}"))throw invalid("Mã "+entry.getKey()+" chỉ gồm chữ, số, dấu . _ - và tối đa 80 ký tự.");
        }
        switch(row.sheet) {
            case "products" -> {
                require(row,"sku_code","name","base_unit","case_unit","units_per_case");
                bounded(row,"name",200);bounded(row,"category",120);bounded(row,"base_unit",32);bounded(row,"case_unit",32);
                long factor=integer(row.value("units_per_case"),"Hệ số quy đổi",1);
                if(row.value("base_unit").equals(row.value("case_unit"))&&factor!=1)throw invalid("Cùng đơn vị cơ sở thì hệ số phải bằng 1.");
                existing(row,one("SELECT id,version,base_unit_code FROM product WHERE tenant_id=? AND upper(sku)=?",actor.tenantId(),row.key),actor,"product");
                if(data.containsKey("_existing")) {
                    String base=scalar("SELECT base_unit_code FROM product WHERE id=?",UUID.fromString(data.get("_id")));
                    if(!base.equals(row.value("base_unit")))throw invalid("Không đổi đơn vị cơ sở của SKU đã tồn tại qua import.");
                    Map<String,Object> unit=one("SELECT base_units FROM unit_conversion WHERE product_id=? AND unit_code=?",UUID.fromString(data.get("_id")),row.value("case_unit"));
                    if(!unit.isEmpty()&&((Number)unit.get("base_units")).longValue()!=factor)throw invalid("Không đổi hệ số của đơn vị đã tồn tại qua import.");
                }
            }
            case "npp" -> {
                require(row,"npp_code");
                if(kind==ImportKind.CREDIT) {
                    UUID npp=npp(actor,row.value("npp_code"));data.put("_npp",npp.toString());
                    require(row,"credit_limit_vnd","debt_current_vnd");
                    long limit=integer(row.value("credit_limit_vnd"),"Hạn mức",0),debt=integer(row.value("debt_current_vnd"),"Công nợ đầu kỳ",0);
                    if(!row.value("credit_remaining_vnd").isEmpty()&&(!row.value("credit_remaining_vnd").matches("-?[0-9]+")||!new java.math.BigInteger(row.value("credit_remaining_vnd")).equals(java.math.BigInteger.valueOf(limit-debt))))throw invalid("Hạn mức còn lại không khớp hạn mức trừ công nợ đầu kỳ.");
                    UUID target=id(actor,"opening-credit",row.key);data.put("_id",target.toString());
                    var before=one("SELECT id,version,status,limit_amount_vnd FROM credit_limit WHERE id=? AND tenant_id=?",target,actor.tenantId());
                    if(!before.isEmpty()) {data.put("_existing","true");data.put("_version",before.get("version").toString());}
                    if(before.isEmpty()&&exists("SELECT EXISTS(SELECT 1 FROM credit_limit WHERE tenant_id=? AND npp_tenant_id=?)",actor.tenantId(),npp))throw invalid("NPP đã có hạn mức. Hãy dùng luồng điều chỉnh, không nhập lại số đầu kỳ.");
                    data.put("_credit_status",String.valueOf(before.getOrDefault("status","DRAFT")));
                    data.put("_invoice",id(actor,"opening-invoice",row.key).toString());
                    row.warnings.add("Hạn mức được lưu nháp để gửi duyệt. Công nợ tạo hóa đơn số dư đầu kỳ, không tự kích hoạt hạn mức.");
                } else {
                    require(row,"name","org_unit_code");bounded(row,"name",200);
                    data.put("_org",org(actor,row.value("org_unit_code")).toString());
                    var before=one("SELECT id,version,is_demo,tenant_type FROM tenant WHERE upper(code)=?",row.key);
                    if(!before.isEmpty()) {
                        if(!"NPP".equals(before.get("tenant_type"))||!Objects.equals(before.get("is_demo"),actor.demo())||!related(actor,(UUID)before.get("id")))throw invalid("Mã NPP đã được sử dụng ngoài phạm vi được phép.");
                    }
                    existing(row,before,actor,"npp-tenant");
                    if(!row.value("credit_limit_vnd").isEmpty()||!row.value("debt_current_vnd").isEmpty())row.warnings.add("Các cột hạn mức/công nợ không ghi trong loại NPP. Nhập chúng bằng loại Hạn mức và công nợ đầu kỳ.");
                }
            }
            case "org_units" -> {
                require(row,"org_code","name","level");bounded(row,"name",160);integer(row.value("level"),"Cấp đơn vị",0);
                existing(row,one("SELECT id,version FROM org_unit WHERE tenant_id=? AND upper(code)=?",actor.tenantId(),row.key),actor,"org");
                String path=orgPath(actor,row,batch,new HashSet<>());data.put("_path",path);
                if(!row.value("path").isEmpty()&&!code(row.value("path")).equals(code(path)))row.warnings.add("Path sẽ được tính từ cây cha, không lấy giá trị nhập tay.");
                if(!row.value("parent_code").isEmpty())data.put("_parent",reference(actor,"org_units",row.value("parent_code"),batch,()->org(actor,row.value("parent_code"))).toString());
                if(data.containsKey("_existing")) {
                    String old=scalar("SELECT path FROM org_unit WHERE id=? AND tenant_id=?",UUID.fromString(data.get("_id")),actor.tenantId());
                    if(!old.equals(path)&&exists("SELECT EXISTS(SELECT 1 FROM org_unit WHERE tenant_id=? AND path LIKE ?)",actor.tenantId(),old+"/%"))throw invalid("Không chuyển nhánh có đơn vị con qua import. Hãy dùng quản lý tổ chức.");
                }
            }
            case "routes" -> {
                require(row,"route_code","name","org_unit_code");bounded(row,"name",160);
                data.put("_org",org(actor,row.value("org_unit_code")).toString());
                existing(row,one("SELECT id,version FROM route WHERE tenant_id=? AND upper(code)=?",actor.tenantId(),row.key),actor,"route");
                for(String column:List.of("sales_username","supervisor_username"))if(!row.value(column).isEmpty())data.put("_"+column,user(actor,row.value(column)).toString());
            }
            case "outlets" -> {
                require(row,"outlet_code","name","address");bounded(row,"name",200);bounded(row,"contact_name",160);bounded(row,"outlet_type",80);
                data.put("contact_phone",phone(row.value("contact_phone")));
                if(!row.value("contact_email").isEmpty()&&!row.value("contact_email").matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw invalid("Email liên hệ không hợp lệ.");
                var before=outlets.get(row.key);
                existing(row,before,actor,"outlet");
                if(!row.value("npp_code").isEmpty())data.put("_npp",npp(actor,row.value("npp_code")).toString());
                if(!actor.nsx())data.put("_npp",actor.tenantId().toString());
                if(!row.value("org_unit_code").isEmpty()) {
                    if(!actor.nsx())throw invalid("NPP không được thay đổi tổ chức của NSX.");
                    org(actor,row.value("org_unit_code"));
                }
                if(!row.value("route_code").isEmpty()) {
                    if(!actor.nsx())throw invalid("NPP không được tự gán tuyến của NSX.");
                    data.put("_route",route(actor,row.value("route_code")).toString());
                }
                if(before.isEmpty())row.warnings.add("Điểm bán mới ở trạng thái PENDING; phải được duyệt trước khi giao dịch.");
                var candidates=outlets.candidates(row.value("name"));
                for(var candidate:candidates)if(!candidate.get("id").toString().equals(data.get("_id"))&&similarOutlet(row.value("name"),row.value("address"),candidate.get("name").toString(),candidate.get("address").toString())) {
                    row.warnings.add("Có thể trùng điểm bán "+candidate.get("code")+" (cùng tên, địa chỉ gần giống).");break;
                }
            }
            case "outlet_npp" -> {
                require(row,"outlet_code","npp_code");
                data.put("_npp",npp(actor,row.value("npp_code")).toString());
                data.put("_outlet",reference(actor,"outlets",row.value("outlet_code"),batch,()->requiredId(outlets.get(row.value("outlet_code")),"Điểm bán không tồn tại trong phạm vi.")).toString());
                relationIdentity(row,actor,"outlet-npp");
            }
            case "route_outlets" -> {
                require(row,"route_code","outlet_code","visit_order","mcp_weekday","mcp_weeks");
                data.put("_route",reference(actor,"routes",row.value("route_code"),batch,()->route(actor,row.value("route_code"))).toString());
                data.put("_outlet",requiredId(outlets.get(row.value("outlet_code")),"Điểm bán không tồn tại trong phạm vi.").toString());
                integer(row.value("visit_order"),"Thứ tự ghé",0);
                String day=folded(row.value("mcp_weekday")).replace(" ","");
                int dayValue=day.equals("CHUNHAT")||day.equals("CN")?7:day.matches("THU[2-7]")?Integer.parseInt(day.substring(3))-1:Math.toIntExact(integer(day,"Thứ ISO (1=thứ Hai)",1));
                if(dayValue>7)throw invalid("Thứ ghé phải trong khoảng 1–7 hoặc Thứ 2…Chủ nhật.");
                data.put("_day",String.valueOf(dayValue));
                Set<String> weeks=new TreeSet<>(Arrays.asList(row.value("mcp_weeks").replace(" ","").split(",")));
                for(String week:weeks)if(integer(week,"Tuần MCP",1)>5)throw invalid("Tuần MCP phải từ 1 đến 5.");
                data.put("mcp_weeks",String.join(",",weeks));relationIdentity(row,actor,"route-outlet");
                var slots=jdbc.queryForList("SELECT week_of_month,day_of_week FROM mcp_schedule WHERE tenant_id=? AND route_id=? AND outlet_id=? AND effective_from<=CURRENT_DATE AND (effective_to IS NULL OR effective_to>=CURRENT_DATE)",actor.tenantId(),UUID.fromString(data.get("_route")),UUID.fromString(data.get("_outlet")));
                if(!slots.isEmpty()&&(slots.size()!=weeks.size()||slots.stream().anyMatch(slot->((Number)slot.get("day_of_week")).intValue()!=dayValue||!weeks.contains(slot.get("week_of_month").toString()))))throw invalid("Lịch MCP đã tồn tại khác tệp. Hãy điều chỉnh lịch có hiệu lực qua màn MCP để giữ lịch sử.");
            }
            case "users" -> {
                require(row,"username","full_name","role_code","tenant_code");bounded(row,"full_name",100);bounded(row,"username",120);
                if(!code(actor.identity().tenantCode()).equals(row.value("tenant_code")))throw invalid("Chỉ được nhập người dùng cho tenant của chính mình.");
                String email=text(row.value("email")).toLowerCase(Locale.ROOT);
                if(email.isEmpty()&&row.value("username").contains("@"))email=row.value("username");
                if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")||email.length()>254)throw invalid("Cần cột email hợp lệ để tài khoản đăng nhập. Có thể dùng email làm username.");
                data.put("email",email);
                var before=one("SELECT id,tenant_id,version FROM app_users WHERE lower(email)=?",email);
                if(!before.isEmpty()&&!actor.tenantId().equals(before.get("tenant_id")))throw invalid("Email đã được sử dụng ngoài phạm vi được phép.");
                existing(row,before,actor,"user");
                if(data.get("_id").equals(actor.userId().toString()))throw invalid("Không được nhập/sửa vai của chính mình.");
                int max=actor.identity().roles().stream().mapToInt(role->role.assignmentLevel()).max().orElse(0);
                var role=one("SELECT code,tenant_type,assignment_level FROM role WHERE code=?",row.value("role_code"));
                if(role.isEmpty()||!actor.identity().tenantType().equals(role.get("tenant_type"))||((Number)role.get("assignment_level")).intValue()>max)throw invalid("Vai không tồn tại, khác loại tenant hoặc cao hơn cấp của người nhập.");
                if(!"A".equals(actor.identity().permissionScopes().get("role:assign")))throw invalid("Cần quyền gán vai cho import người dùng.");
                if(before.isEmpty()&&!data.containsKey("_password_hash"))throw invalid("Người dùng mới cần mật khẩu trong cột password.");
                if(!before.isEmpty()) {
                    Integer targetMax=jdbc.queryForObject("SELECT COALESCE(MAX(r.assignment_level),0) FROM user_role ur JOIN role r ON r.code=ur.role_code WHERE ur.user_id=?",Integer.class,UUID.fromString(data.get("_id")));
                    if(targetMax!=null&&targetMax>max)throw invalid("Không được sửa tài khoản có vai cao hơn mình.");
                    data.remove("_password_hash");row.warnings.add("Tài khoản đã tồn tại: giữ mật khẩu và các vai cũ, chỉ thêm vai được phép.");
                }
                var collision=one("SELECT id FROM app_users WHERE tenant_id=? AND lower(username)=? AND id<>?",actor.tenantId(),row.value("username"),UUID.fromString(data.get("_id")));
                if(!collision.isEmpty())throw invalid("Username đã thuộc tài khoản khác trong tenant.");
                if(!row.value("org_unit_code").isEmpty())data.put("_org",org(actor,row.value("org_unit_code")).toString());
                if(!row.value("route_code").isEmpty())data.put("_route",route(actor,row.value("route_code")).toString());
            }
            case "inventory_npp" -> {
                require(row,"npp_code","sku_code","stock_case","updated_at");
                UUID npp=npp(actor,row.value("npp_code"));data.put("_npp",npp.toString());
                Map<String,Object> product=productForNpp(actor,npp,row.value("sku_code"));
                data.put("_product",product.get("id").toString());data.put("_base",product.get("base_unit_code").toString());
                List<Map<String,Object>> units=jdbc.queryForList("SELECT unit_code,base_units FROM unit_conversion WHERE product_id=? AND NOT is_base_unit ORDER BY unit_code",product.get("id"));
                if(units.size()!=1)throw invalid("SKU phải có đúng một đơn vị thùng để nhập stock_case; cần chuẩn hóa quy đổi trước.");
                long quantity=integer(row.value("stock_case"),"Tồn thùng",0);
                try {data.put("_quantity",String.valueOf(Math.multiplyExact(quantity,((Number)units.getFirst().get("base_units")).longValue())));}
                catch(ArithmeticException exception){throw invalid("Số lượng sau quy đổi quá lớn.");}
                OffsetDateTime at=parseTime(row.value("updated_at"));data.put("updated_at",at.toString());
                LocalDate businessDay=at.atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
                if(exists("SELECT EXISTS(SELECT 1 FROM period WHERE tenant_id=? AND status='CLOSED' AND starts_on<=? AND ends_on>=?)",actor.nsx()?actor.tenantId():product.get("tenant_id"),businessDay,businessDay))throw invalid("Ngày tồn đầu kỳ thuộc kỳ đã chốt.");
                UUID target=id(actor,"opening-stock",row.key);data.put("_id",target.toString());
                if(exists("SELECT EXISTS(SELECT 1 FROM inventory_npp WHERE id=?)",target))data.put("_existing","true");
                if(!samePayload(actor,kind,row)&&exists("SELECT EXISTS(SELECT 1 FROM inventory_npp WHERE npp_tenant_id=? AND product_id=? AND id<>? AND recorded_at>=?)",npp,product.get("id"),target,at))throw invalid("Đã có tồn mới hơn số đầu kỳ; không ghi đè lịch sử tồn.");
                if(!row.value("sellout_7d_case").isEmpty()||!row.value("days_cover").isEmpty()||!row.value("stock_status").isEmpty())row.warnings.add("Chỉ nhập stock_case và updated_at; các cột bán ra 7 ngày/độ phủ/trạng thái là thông tin đối chiếu, không tạo giao dịch bán ra.");
            }
            default -> throw invalid("Sheet không được hỗ trợ.");
        }
    }
    private String orgPath(FoundationImportPolicy.Actor actor,PreparedRow row,Map<String,PreparedRow> batch,Set<String> seen) {
        if(!seen.add(row.key))throw invalid("Cây tổ chức có chu trình cha/con.");
        String parent=row.value("parent_code");
        if(parent.isEmpty())return "/"+row.key;
        var pending=batch.get("org_units:"+parent);
        String parentPath=pending!=null?orgPath(actor,pending,batch,seen):scalar("SELECT path FROM org_unit WHERE tenant_id=? AND upper(code)=?",actor.tenantId(),parent);
        if(parentPath.isEmpty())throw invalid("Đơn vị cha không tồn tại: "+parent);
        return parentPath+"/"+row.key;
    }
    private void relationIdentity(PreparedRow row,FoundationImportPolicy.Actor actor,String namespace) {
        UUID target=id(actor,namespace,row.key);row.data.put("_id",target.toString());
        if(exists("SELECT EXISTS(SELECT 1 FROM foundation_import_identity WHERE tenant_id=? AND sheet_name=? AND business_key=?)",actor.tenantId(),row.sheet,row.key))row.data.put("_existing","true");
    }
    private boolean samePayload(FoundationImportPolicy.Actor actor,ImportKind kind,PreparedRow row) {
        return exists("SELECT EXISTS(SELECT 1 FROM foundation_import_identity WHERE tenant_id=? AND sheet_name=? AND business_key=? AND payload_hash=?)",actor.tenantId(),identitySheet(kind,row.sheet),row.key,fingerprint(row.data));
    }
    private void existing(PreparedRow row,Map<String,Object> before,FoundationImportPolicy.Actor actor,String namespace) {
        row.data.put("_id",before.isEmpty()?id(actor,namespace,row.key).toString():before.get("id").toString());
        if(!before.isEmpty()){row.data.put("_existing","true");row.data.put("_version",String.valueOf(before.getOrDefault("version",0)));}
    }
    private UUID reference(FoundationImportPolicy.Actor actor,String sheet,String key,Map<String,PreparedRow> batch,Supplier<UUID> fallback) {
        var pending=batch.get(sheet+":"+key);
        if(pending==null)return fallback.get();
        if(!pending.errors.isEmpty())throw invalid("Bản ghi tham chiếu có lỗi trong sheet "+sheet+": "+key);
        return pending.data.containsKey("_id")?UUID.fromString(pending.data.get("_id")):id(actor,sheet.equals("org_units")?"org":sheet.equals("routes")?"route":"outlet",key);
    }
    UUID org(FoundationImportPolicy.Actor actor,String code) { return requiredId(one("SELECT id FROM org_unit WHERE tenant_id=? AND upper(code)=?",actor.tenantId(),code),"Đơn vị tổ chức không tồn tại trong tenant: "+code); }
    UUID route(FoundationImportPolicy.Actor actor,String code) { return requiredId(one("SELECT id FROM route WHERE tenant_id=? AND upper(code)=?",actor.tenantId(),code),"Tuyến không tồn tại trong tenant: "+code); }
    UUID user(FoundationImportPolicy.Actor actor,String username) { return requiredId(one("SELECT id FROM app_users WHERE tenant_id=? AND status='ACTIVE' AND (lower(username)=? OR lower(email)=?)",actor.tenantId(),username,username),"Người dùng không tồn tại trong tenant: "+username); }
    UUID npp(FoundationImportPolicy.Actor actor,String code) {
        var target=one("SELECT id,is_demo FROM tenant WHERE upper(code)=? AND tenant_type='NPP' AND status='ACTIVE'",code);
        if(target.isEmpty()||!Objects.equals(target.get("is_demo"),actor.demo())||!related(actor,(UUID)target.get("id")))throw invalid("NPP không tồn tại hoặc ngoài phạm vi: "+code);
        return (UUID)target.get("id");
    }
    boolean related(FoundationImportPolicy.Actor actor,UUID npp) {
        return actor.nsx()?exists("SELECT EXISTS(SELECT 1 FROM tenant_relation WHERE nsx_tenant_id=? AND npp_tenant_id=? AND status='ACTIVE' AND effective_from<=CURRENT_DATE AND (effective_to IS NULL OR effective_to>=CURRENT_DATE))",actor.tenantId(),npp):actor.tenantId().equals(npp);
    }
    Map<String,Object> outlet(FoundationImportPolicy.Actor actor,String code) {
        var rows=visibleOutlets(actor).stream().filter(row->code.equals(ImportNormalization.code(String.valueOf(row.get("code"))))).toList();
        if(rows.size()>1)throw invalid("Mã điểm bán không duy nhất trong phạm vi. Cần xử lý trùng trước: "+code);
        return rows.isEmpty()?Map.of():rows.getFirst();
    }
    private List<Map<String,Object>> visibleOutlets(FoundationImportPolicy.Actor actor) {
        return jdbc.queryForList("""
                SELECT o.id,o.code,o.name,o.address,o.version,o.tenant_id FROM outlet o JOIN tenant t ON t.id=o.tenant_id
                WHERE o.deleted_at IS NULL AND t.is_demo=? AND
                  (o.created_by_tenant_id=? OR EXISTS(SELECT 1 FROM outlet_npp onp
                   WHERE onp.outlet_id=o.id AND onp.status IN ('ACTIVE','PENDING')
                     AND onp.effective_from<=CURRENT_DATE AND (onp.effective_to IS NULL OR onp.effective_to>=CURRENT_DATE)
                     AND (onp.npp_tenant_id=? OR EXISTS(SELECT 1 FROM tenant_relation tr
                       WHERE tr.nsx_tenant_id=? AND tr.npp_tenant_id=onp.npp_tenant_id AND tr.status='ACTIVE'
                       AND tr.effective_from<=CURRENT_DATE AND (tr.effective_to IS NULL OR tr.effective_to>=CURRENT_DATE)))))
                """,actor.demo(),actor.tenantId(),actor.tenantId(),actor.tenantId());
    }
    private static class OutletSnapshot {
        private final Map<String,List<Map<String,Object>>> codes=new HashMap<>(),names=new HashMap<>();
        OutletSnapshot(List<Map<String,Object>> rows){for(var row:rows){codes.computeIfAbsent(code(row.get("code").toString()),ignored->new ArrayList<>()).add(row);names.computeIfAbsent(folded(row.get("name").toString()),ignored->new ArrayList<>()).add(row);}}
        Map<String,Object> get(String code){var matches=codes.getOrDefault(code,List.of());if(matches.size()>1)throw invalid("Mã điểm bán không duy nhất trong phạm vi: "+code);return matches.isEmpty()?Map.of():matches.getFirst();}
        List<Map<String,Object>> candidates(String name){return names.getOrDefault(folded(name),List.of()).stream().limit(50).toList();}
    }
    private Map<String,Object> productForNpp(FoundationImportPolicy.Actor actor,UUID npp,String sku) {
        List<Map<String,Object>> products=jdbc.queryForList("""
                SELECT p.id,p.base_unit_code,p.tenant_id FROM product p JOIN tenant t ON t.id=p.tenant_id
                WHERE upper(p.sku)=? AND p.status='ACTIVE' AND t.is_demo=?
                  AND (?=p.tenant_id OR ?=?) AND EXISTS(SELECT 1 FROM tenant_relation tr
                  WHERE tr.nsx_tenant_id=p.tenant_id AND tr.npp_tenant_id=? AND tr.status='ACTIVE'
                    AND tr.effective_from<=CURRENT_DATE AND (tr.effective_to IS NULL OR tr.effective_to>=CURRENT_DATE))
                """,sku,actor.demo(),actor.tenantId(),actor.identity().tenantType(),"NPP",npp);
        if(products.size()!=1)throw invalid("SKU không tồn tại hoặc không duy nhất trong các NSX phục vụ NPP: "+sku);
        return products.getFirst();
    }
    static String businessKey(PreparedRow row) {
        return switch(row.sheet) {
            case "products"->row.value("sku_code");case "npp"->row.value("npp_code");case "org_units"->row.value("org_code");
            case "outlets"->row.value("outlet_code");case "routes"->row.value("route_code");case "users"->row.value("username");
            case "inventory_npp"->row.value("npp_code")+":"+row.value("sku_code");
            case "outlet_npp"->row.value("outlet_code")+":"+row.value("npp_code");
            case "route_outlets"->row.value("route_code")+":"+row.value("outlet_code");default->throw invalid("Sheet không hợp lệ.");
        };
    }
    static void require(PreparedRow row,String... keys){for(String key:keys)if(row.value(key).isEmpty())throw invalid("Thiếu trường bắt buộc: "+key);}
    static void bounded(PreparedRow row,String key,int max){if(row.value(key).length()>max)throw invalid("Cột "+key+" dài quá "+max+" ký tự.");}
    static UUID requiredId(Map<String,Object> row,String message){if(row.isEmpty())throw invalid(message);return (UUID)row.get("id");}
    Map<String,Object> one(String sql,Object... args){var rows=jdbc.queryForList(sql,args);if(rows.size()>1)throw invalid("Mã tham chiếu không duy nhất.");return rows.isEmpty()?Map.of():rows.getFirst();}
    String scalar(String sql,Object... args){var rows=jdbc.queryForList(sql,String.class,args);return rows.isEmpty()?"":rows.getFirst();}
    boolean exists(String sql,Object... args){return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));}
    static UUID id(FoundationImportPolicy.Actor actor,String type,String key){return UUID.nameUUIDFromBytes(("synera-import:"+actor.tenantId()+":"+type+":"+key).getBytes(StandardCharsets.UTF_8));}
    static String identitySheet(ImportKind kind,String sheet){return kind==ImportKind.CREDIT?"opening_credit":sheet;}
    static OffsetDateTime parseTime(String value){try{return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC);}catch(Exception ignored){try{return LocalDate.parse(value).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);}catch(Exception exception){throw invalid("Thời gian cần theo ISO, ví dụ 2026-11-13T08:00:00+07:00 hoặc 2026-11-13.");}}}
    static String hash(String value){return hash(value.getBytes(StandardCharsets.UTF_8));}
    static String fingerprint(Map<String,String> values){
        StringBuilder canonical=new StringBuilder();values.entrySet().stream().filter(entry->!entry.getKey().startsWith("_")).sorted(Map.Entry.comparingByKey()).forEach(entry->canonical.append(entry.getKey().length()).append(':').append(entry.getKey()).append(entry.getValue().length()).append(':').append(entry.getValue()));
        return hash(canonical.toString());
    }
    static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception exception){throw new IllegalStateException(exception);}}
    static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
    static ApiProblem conflict(String message){return new ApiProblem("IMPORT_DATA_CHANGED",message,HttpStatus.CONFLICT);}
    public static class PreparedRow {
        public final String sheet;public final int line;public final Map<String,String> data;
        public final List<String> errors=new ArrayList<>(),warnings=new ArrayList<>();
        public String key="",hash="",operation="CREATE";
        public PreparedRow(String sheet,int line,Map<String,String> data){this.sheet=sheet;this.line=line;this.data=data;}
        public String value(String key){return data.getOrDefault(key,"");}
        public Map<String,String> publicData(){Map<String,String> result=new LinkedHashMap<>();data.forEach((key,value)->{if(!key.startsWith("_")&&!key.equals("password"))result.put(key,value);});return result;}
    }
}
