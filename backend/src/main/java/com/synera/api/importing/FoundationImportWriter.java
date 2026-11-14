package com.synera.api.importing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.synera.api.importing.FoundationImportEngine.*;

@Service
public class FoundationImportWriter {
    private final JdbcTemplate jdbc;
    public FoundationImportWriter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void write(FoundationImportPolicy.Actor actor,ImportKind kind,PreparedRow row) {
        UUID owner=actor.tenantId(),target=uuid(row,"_id");
        long version=Long.parseLong(row.value("_version").isEmpty()?"0":row.value("_version"));
        boolean update=row.data.containsKey("_existing");
        switch(row.sheet) {
            case "products" -> {
                if(update)checked(jdbc.update("UPDATE product SET name=?,category=?,note=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND version=?",row.value("name"),row.value("category"),row.value("note"),target,owner,version));
                else jdbc.update("INSERT INTO product(id,tenant_id,sku,name,base_unit_code,category,note) VALUES (?,?,?,?,?,?,?)",target,owner,row.key,row.value("name"),row.value("base_unit"),row.value("category"),row.value("note"));
                unit(actor,target,row.value("base_unit"),1,true);
                if(!row.value("case_unit").equals(row.value("base_unit")))unit(actor,target,row.value("case_unit"),Long.parseLong(row.value("units_per_case")),false);
            }
            case "org_units" -> {
                UUID parent=optionalUuid(row,"_parent");
                if(update)checked(jdbc.update("UPDATE org_unit SET name=?,unit_type=?,parent_id=?,path=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND version=?",row.value("name"),"LEVEL_"+row.value("level"),parent,row.value("_path"),target,owner,version));
                else jdbc.update("INSERT INTO org_unit(id,tenant_id,parent_id,code,name,unit_type,path) VALUES (?,?,?,?,?,?,?)",target,owner,parent,row.key,row.value("name"),"LEVEL_"+row.value("level"),row.value("_path"));
            }
            case "npp" -> {
                if(kind==ImportKind.CREDIT){openingCredit(actor,row,version);break;}
                if(update)checked(jdbc.update("UPDATE tenant SET name=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_type='NPP' AND is_demo=? AND version=?",row.value("name"),target,actor.demo(),version));
                else jdbc.update("INSERT INTO tenant(id,tenant_type,code,name,is_demo) VALUES (?,'NPP',?,?,?)",target,row.key,row.value("name"),actor.demo());
                jdbc.update("""
                        INSERT INTO npp_profile(tenant_id,npp_tenant_id,org_unit_id,address) VALUES (?,?,?,?)
                        ON CONFLICT(tenant_id,npp_tenant_id) DO UPDATE SET org_unit_id=EXCLUDED.org_unit_id,address=EXCLUDED.address
                        """,owner,target,uuid(row,"_org"),row.value("address"));
                jdbc.update("""
                        INSERT INTO tenant_relation(id,tenant_id,nsx_tenant_id,npp_tenant_id)
                        VALUES (?,?,?,?) ON CONFLICT(id) DO NOTHING
                        """,id(actor,"tenant-relation",row.key),owner,owner,target);
            }
            case "routes" -> {
                if(update)checked(jdbc.update("UPDATE route SET name=?,org_unit_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND version=?",row.value("name"),uuid(row,"_org"),target,owner,version));
                else jdbc.update("INSERT INTO route(id,tenant_id,org_unit_id,code,name) VALUES (?,?,?,?,?)",target,owner,uuid(row,"_org"),row.key,row.value("name"));
                for(String key:new String[]{"_sales_username","_supervisor_username"})if(!row.value(key).isEmpty())assignRoute(owner,uuid(row,key),target);
            }
            case "outlets" -> {
                if(update)checked(jdbc.update("UPDATE outlet SET name=?,address=?,outlet_type=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=?",row.value("name"),row.value("address"),row.value("outlet_type"),target,version));
                else {
                    UUID outletTenant=id(actor,"outlet-tenant",row.key);
                    jdbc.update("INSERT INTO tenant(id,tenant_type,code,name,is_demo) VALUES (?,'OUTLET',?,?,?)",outletTenant,"OL-"+outletTenant.toString().replace("-", ""),row.value("name"),actor.demo());
                    jdbc.update("INSERT INTO outlet(id,tenant_id,code,name,address,outlet_type,status,created_by,created_by_tenant_id) VALUES (?,?,?,?,?,?,'PENDING',?,?)",target,outletTenant,row.key,row.value("name"),row.value("address"),row.value("outlet_type"),actor.userId(),owner);
                }
                if(!row.value("contact_name").isEmpty())jdbc.update("""
                        INSERT INTO contact(id,tenant_id,outlet_id,full_name,phone,email,is_primary) VALUES (?,?,?,?,?,?,TRUE)
                        ON CONFLICT(id) DO UPDATE SET full_name=EXCLUDED.full_name,phone=EXCLUDED.phone,email=EXCLUDED.email,
                          updated_at=CURRENT_TIMESTAMP,version=contact.version+1
                        """,id(actor,"primary-contact",row.key),owner,target,row.value("contact_name"),row.value("contact_phone"),row.value("contact_email"));
                if(!row.value("_npp").isEmpty())linkNpp(actor,target,uuid(row,"_npp"));
                if(!row.value("_route").isEmpty())linkRoute(actor,uuid(row,"_route"),target,0);
            }
            case "outlet_npp" -> linkNpp(actor,uuid(row,"_outlet"),uuid(row,"_npp"));
            case "route_outlets" -> {
                UUID route=uuid(row,"_route"),outlet=uuid(row,"_outlet");
                linkRoute(actor,route,outlet,Integer.parseInt(row.value("visit_order")));
                for(String week:row.value("mcp_weeks").split(","))jdbc.update("""
                        INSERT INTO mcp_schedule(id,tenant_id,route_id,outlet_id,week_of_month,day_of_week,effective_from)
                        SELECT ?,?,?,?,?,?,CURRENT_DATE WHERE NOT EXISTS(SELECT 1 FROM mcp_schedule
                         WHERE tenant_id=? AND route_id=? AND outlet_id=? AND week_of_month=? AND day_of_week=?
                           AND effective_from<=CURRENT_DATE AND (effective_to IS NULL OR effective_to>=CURRENT_DATE))
                        ON CONFLICT(id) DO NOTHING
                        """,id(actor,"mcp",row.key+":"+week+":"+row.value("_day")),owner,route,outlet,Integer.parseInt(week),Integer.parseInt(row.value("_day")),owner,route,outlet,Integer.parseInt(week),Integer.parseInt(row.value("_day")));
            }
            case "users" -> {
                if(update)checked(jdbc.update("UPDATE app_users SET full_name=?,username=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND version=?",row.value("full_name"),row.value("username"),target,owner,version));
                else jdbc.update("INSERT INTO app_users(id,tenant_id,full_name,username,email,password_hash,status) VALUES (?,?,?,?,?,?,'ACTIVE')",target,owner,row.value("full_name"),row.value("username"),row.value("email"),row.value("_password_hash"));
                jdbc.update("INSERT INTO user_role(id,tenant_id,user_id,role_code,assigned_by) VALUES (?,?,?,?,?) ON CONFLICT(tenant_id,user_id,role_code) DO NOTHING",id(actor,"user-role",target+":"+row.value("role_code")),owner,target,row.value("role_code"),actor.userId());
                if(!row.value("_org").isEmpty())jdbc.update("INSERT INTO user_org_unit(tenant_id,user_id,org_unit_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",owner,target,uuid(row,"_org"));
                if(!row.value("_route").isEmpty())assignRoute(owner,target,uuid(row,"_route"));
            }
            case "inventory_npp" -> {
                if(update)checked(jdbc.update("UPDATE inventory_npp SET quantity=?,recorded_at=?,recorded_by=? WHERE id=? AND tenant_id=?",Long.parseLong(row.value("_quantity")),parseTime(row.value("updated_at")),actor.userId(),target,uuid(row,"_npp")));
                else jdbc.update("INSERT INTO inventory_npp(id,tenant_id,npp_tenant_id,product_id,unit_code,quantity,recorded_at,recorded_by) VALUES (?,?,?,?,?,?,?,?)",target,uuid(row,"_npp"),uuid(row,"_npp"),uuid(row,"_product"),row.value("_base"),Long.parseLong(row.value("_quantity")),parseTime(row.value("updated_at")),actor.userId());
            }
            default -> throw new IllegalArgumentException("Sheet không hỗ trợ.");
        }
        jdbc.update("""
                INSERT INTO foundation_import_identity(tenant_id,sheet_name,business_key,target_id,payload_hash)
                VALUES (?,?,?,?,?) ON CONFLICT(tenant_id,sheet_name,business_key) DO UPDATE
                SET target_id=EXCLUDED.target_id,payload_hash=EXCLUDED.payload_hash,updated_at=CURRENT_TIMESTAMP
                """,owner,identitySheet(kind,row.sheet),row.key,target,row.hash);
    }
    private void openingCredit(FoundationImportPolicy.Actor actor,PreparedRow row,long version) {
        UUID owner=actor.tenantId(),npp=uuid(row,"_npp"),target=uuid(row,"_id"),invoice=uuid(row,"_invoice");
        if(!row.value("_credit_status").equals("DRAFT"))throw conflict("Hạn mức đầu kỳ đã gửi/duyệt; không sửa qua import.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM invoice WHERE id=? AND (version>0 OR period_id IS NOT NULL)) OR EXISTS(SELECT 1 FROM payment WHERE invoice_id=?) OR EXISTS(SELECT 1 FROM deduction WHERE invoice_id=?)",Boolean.class,invoice,invoice,invoice)))throw conflict("Công nợ đầu kỳ đã được sử dụng; không sửa trực tiếp qua import.");
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
        long limit=Long.parseLong(row.value("credit_limit_vnd")),debt=Long.parseLong(row.value("debt_current_vnd"));
        if(row.data.containsKey("_existing"))checked(jdbc.update("UPDATE credit_limit SET limit_amount_vnd=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND version=? AND status='DRAFT'",limit,target,owner,version));
        else jdbc.update("INSERT INTO credit_limit(id,tenant_id,npp_tenant_id,limit_amount_vnd,status,created_by,effective_from) VALUES (?,?,?,?,'DRAFT',?,?)",target,owner,npp,limit,actor.userId(),now);
        jdbc.update("""
                INSERT INTO invoice(id,tenant_id,npp_tenant_id,invoice_code,amount_vnd,issued_at)
                VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET amount_vnd=EXCLUDED.amount_vnd,
                  updated_at=CURRENT_TIMESTAMP WHERE invoice.version=0 AND invoice.period_id IS NULL
                """,invoice,owner,npp,"OPEN-"+invoice.toString().replace("-", ""),debt,now);
    }
    private void unit(FoundationImportPolicy.Actor actor,UUID product,String code,long factor,boolean base) {
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,product_id,unit_code,unit_name,base_units,is_base_unit) VALUES (?,?,?,?,?,?,?) ON CONFLICT(tenant_id,product_id,unit_code) DO NOTHING",id(actor,"unit",product+":"+code),actor.tenantId(),product,code,code,factor,base);
    }
    private void assignRoute(UUID owner,UUID user,UUID route) {jdbc.update("INSERT INTO user_route(tenant_id,user_id,route_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",owner,user,route);}
    private void linkNpp(FoundationImportPolicy.Actor actor,UUID outlet,UUID npp) {
        jdbc.update("""
                INSERT INTO outlet_npp(id,tenant_id,outlet_id,npp_tenant_id,status)
                SELECT ?,?,?,?,'PENDING' WHERE NOT EXISTS(SELECT 1 FROM outlet_npp
                  WHERE outlet_id=? AND npp_tenant_id=? AND status IN ('ACTIVE','PENDING')
                    AND effective_from<=CURRENT_DATE AND (effective_to IS NULL OR effective_to>=CURRENT_DATE))
                ON CONFLICT(id) DO NOTHING
                """,id(actor,"outlet-npp-link",outlet+":"+npp),npp,outlet,npp,outlet,npp);
    }
    private void linkRoute(FoundationImportPolicy.Actor actor,UUID route,UUID outlet,int position) {
        jdbc.update("""
                INSERT INTO route_outlet(id,tenant_id,route_id,outlet_id,position)
                SELECT ?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM route_outlet
                  WHERE tenant_id=? AND route_id=? AND outlet_id=? AND effective_from<=CURRENT_DATE
                    AND (effective_to IS NULL OR effective_to>=CURRENT_DATE)) ON CONFLICT(id) DO NOTHING
                """,id(actor,"route-outlet-link",route+":"+outlet),actor.tenantId(),route,outlet,position,actor.tenantId(),route,outlet);
    }
    private static void checked(int count){if(count!=1)throw conflict("Bản ghi đã thay đổi sau xem trước. Hãy tải lại tệp và kiểm tra.");}
    private static UUID uuid(PreparedRow row,String key){return UUID.fromString(row.value(key));}
    private static UUID optionalUuid(PreparedRow row,String key){return row.value(key).isEmpty()?null:uuid(row,key);}
}
