package com.synera.api.importing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.ApiPage;
import com.synera.api.operations.DatabaseCapacityPolicy;
import com.synera.api.security.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.*;

import static com.synera.api.importing.FoundationImportEngine.*;

@Service
public class FoundationImportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FoundationImportPolicy policy;
    private final FoundationWorkbookReader reader;
    private final FoundationImportEngine engine;
    private final FoundationImportWriter writer;
    private final AuditService audit;
    public FoundationImportService(JdbcTemplate jdbc,ObjectMapper json,FoundationImportPolicy policy,
            FoundationWorkbookReader reader,FoundationImportEngine engine,FoundationImportWriter writer,AuditService audit) {
        this.jdbc=jdbc;this.json=json;this.policy=policy;this.reader=reader;this.engine=engine;this.writer=writer;this.audit=audit;
    }
    public List<TypeView> types(Jwt jwt) {
        var actor=policy.actor(jwt);
        return policy.allowed(actor).stream().sorted().map(kind->new TypeView(kind.name(),kind.label,kind.sheets,actor.demo())).toList();
    }
    public void requireType(Jwt jwt,ImportKind kind){policy.require(policy.actor(jwt),kind);}

    @Transactional
    public BatchView preview(Jwt jwt,ImportKind kind,MultipartFile file,String reason,HttpServletRequest request) {
        DatabaseCapacityPolicy.requireCapacityForHeavyWrite(jdbc);
        var actor=policy.actor(jwt);policy.require(actor,kind);
        var parsed=reader.read(kind,file);
        if(parsed.demo()&&!actor.demo())throw new ApiProblem("DEMO_IN_REAL_TENANT","Workbook được đánh dấu demo; không được nhập vào tenant thật.",HttpStatus.CONFLICT);
        if(parsed.rows().isEmpty())throw FoundationWorkbookReader.bad("Tệp không có dòng dữ liệu.");
        if(kind==ImportKind.USERS&&parsed.rows().size()>200)throw FoundationWorkbookReader.bad("Mỗi lô người dùng tối đa 200 dòng để băm mật khẩu an toàn. Hãy chia nhỏ tệp.");
        var prepared=engine.prepare(actor,kind,parsed.rows(),false);
        UUID batchId=UUID.randomUUID();
        String filename=Optional.ofNullable(file.getOriginalFilename()).orElse("upload").replaceAll("[\\\\/]","_");
        if(filename.length()>255)filename=filename.substring(filename.length()-255);
        int errors=(int)prepared.stream().filter(row->!row.errors.isEmpty()).count();
        int warnings=(int)prepared.stream().filter(row->!row.warnings.isEmpty()).count();
        jdbc.update("""
                INSERT INTO foundation_import_batch(id,tenant_id,import_kind,file_name,file_sha256,
                  row_count,error_count,warning_count,created_by,reason) VALUES (?,?,?,?,?,?,?,?,?,?)
                """,batchId,actor.tenantId(),kind.name(),filename,hash(parsed.bytes()),prepared.size(),errors,warnings,actor.userId(),reason);
        for(var row:prepared)jdbc.update("""
                INSERT INTO foundation_import_row(id,tenant_id,batch_id,sheet_name,line_number,row_data,errors,warnings,operation)
                VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?)
                """,UUID.randomUUID(),actor.tenantId(),batchId,row.sheet,row.line,encode(row.data),encode(row.errors),encode(row.warnings),row.operation);
        BatchView result=load(actor,batchId);
        audit(actor,jwt,request,batchId,"FOUNDATION_IMPORT_PREVIEWED",result,reason);
        return result;
    }
    @Transactional(isolation=Isolation.SERIALIZABLE)
    public BatchView commit(Jwt jwt,UUID batchId,HttpServletRequest request) {
        DatabaseCapacityPolicy.requireCapacityForHeavyWrite(jdbc);
        var actor=policy.actor(jwt);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",actor.tenantId().toString());
        var locked=jdbc.queryForList("SELECT id FROM foundation_import_batch WHERE id=? AND tenant_id=? AND created_by=? FOR UPDATE",batchId,actor.tenantId(),actor.userId());
        if(locked.isEmpty())throw missing();
        BatchView batch=load(actor,batchId);ImportKind kind=ImportKind.parse(batch.kind());policy.require(actor,kind);
        if(batch.status().equals("COMMITTED"))return batch;
        if(batch.errorCount()>0)throw new ApiProblem("IMPORT_HAS_ERRORS","Lô có dòng lỗi; chưa ghi dữ liệu nghiệp vụ. Hãy sửa tệp rồi tải lên lại.",HttpStatus.CONFLICT);
        var input=jdbc.query("SELECT sheet_name,line_number,row_data::text FROM foundation_import_row WHERE batch_id=? AND tenant_id=? ORDER BY sheet_name,line_number",
                (rs,index)->new FoundationWorkbookReader.InputRow(rs.getString(1),rs.getInt(2),decodeMap(rs.getString(3))),batchId,actor.tenantId());
        var prepared=engine.prepare(actor,kind,input,true);
        var errors=prepared.stream().filter(row->!row.errors.isEmpty()).map(row->Map.of("sheet",row.sheet,"line",row.line,"errors",row.errors)).toList();
        if(!errors.isEmpty())throw new ApiProblem("IMPORT_REVALIDATION_FAILED","Dữ liệu tham chiếu đã thay đổi. Hãy xem lỗi và tạo bản xem trước mới.",HttpStatus.CONFLICT,Map.of("rows",errors));
        // Recheck the preview versions so a stale preview cannot overwrite another operator.
        Map<String,Map<String,String>> preview=new HashMap<>();
        input.forEach(row->preview.put(row.sheet()+":"+row.line(),row.data()));
        for(var row:prepared) {
            var before=preview.get(row.sheet+":"+row.line);
            if(!row.operation.equals("UNCHANGED")&&(!Objects.equals(before.get("_id"),row.data.get("_id"))||!Objects.equals(before.get("_version"),row.data.get("_version"))))
                throw conflict("Bản ghi đã thay đổi sau xem trước, tại "+row.sheet+" dòng "+row.line+". Hãy xem trước lại.");
        }
        prepared.sort(Comparator.comparingInt((PreparedRow row)->row.sheet.equals("org_units")?row.value("_path").split("/").length:row.sheet.equals(kind.sheets.getFirst())?0:100).thenComparingInt(row->row.line));
        int created=0,updated=0,unchanged=0;
        for(var row:prepared) {
            if(row.operation.equals("UNCHANGED"))unchanged++;
            else {
                writer.write(actor,kind,row);
                if(row.operation.equals("CREATE"))created++;else updated++;
            }
            jdbc.update("UPDATE foundation_import_row SET operation=?,row_data=row_data-'_password_hash' WHERE tenant_id=? AND batch_id=? AND sheet_name=? AND line_number=?",row.operation,actor.tenantId(),batchId,row.sheet,row.line);
        }
        jdbc.update("UPDATE foundation_import_batch SET status='COMMITTED',committed_at=CURRENT_TIMESTAMP,created_count=?,updated_count=?,unchanged_count=? WHERE id=? AND tenant_id=?",created,updated,unchanged,batchId,actor.tenantId());
        BatchView result=load(actor,batchId);
        audit(actor,jwt,request,batchId,"FOUNDATION_IMPORT_COMMITTED",result,result.reason());
        return result;
    }
    public BatchView get(Jwt jwt,UUID id){var actor=policy.actor(jwt);var result=load(actor,id);policy.require(actor,ImportKind.parse(result.kind()));return result;}
    @Transactional
    public Map<String,Integer> submitCredit(Jwt jwt,UUID id,HttpServletRequest request) {
        var actor=policy.actor(jwt);policy.require(actor,ImportKind.CREDIT);
        var batch=load(actor,id);
        if(!batch.kind().equals("CREDIT")||!batch.status().equals("COMMITTED"))
            throw new ApiProblem("IMPORT_NOT_CREDIT","Chỉ gửi duyệt hạn mức từ lô công nợ đã ghi thành công.",HttpStatus.CONFLICT);
        int changed=jdbc.update("""
                UPDATE credit_limit c SET status='PENDING_APPROVAL',version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE c.tenant_id=? AND c.created_by=? AND c.status='DRAFT' AND EXISTS(
                  SELECT 1 FROM foundation_import_row r
                  WHERE r.batch_id=? AND r.tenant_id=c.tenant_id AND r.sheet_name='npp'
                    AND (r.row_data->>'_id')=c.id::text)
                """,actor.tenantId(),actor.userId(),id);
        var result=Map.of("submittedCount",changed);
        audit(actor,jwt,request,id,"FOUNDATION_CREDIT_SUBMITTED",result,batch.reason());
        return result;
    }
    public ApiPage<BatchView> list(Jwt jwt,int page,int size) {
        var actor=policy.actor(jwt);
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM foundation_import_batch WHERE tenant_id=? AND created_by=?",Long.class,actor.tenantId(),actor.userId());
        var batches=jdbc.query("SELECT * FROM foundation_import_batch WHERE tenant_id=? AND created_by=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(rs,index)->mapBatch(rs),actor.tenantId(),actor.userId(),size,(long)page*size);
        return ApiPage.of(batches,page,size,count==null?0:count);
    }
    public ApiPage<RowView> rows(Jwt jwt,UUID id,boolean errorsOnly,int page,int size) {
        var actor=policy.actor(jwt);var batch=load(actor,id);policy.require(actor,ImportKind.parse(batch.kind()));
        String where="tenant_id=? AND batch_id=?"+(errorsOnly?" AND errors<>'[]'::jsonb":"");
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM foundation_import_row WHERE "+where,Long.class,actor.tenantId(),id);
        var rows=jdbc.query("SELECT sheet_name,line_number,row_data::text,errors::text,warnings::text,operation FROM foundation_import_row WHERE "+where+" ORDER BY sheet_name,line_number LIMIT ? OFFSET ?",(rs,index)->{
            var row=new PreparedRow(rs.getString(1),rs.getInt(2),decodeMap(rs.getString(3)));
            return new RowView(row.sheet,row.line,row.publicData(),decodeList(rs.getString(4)),decodeList(rs.getString(5)),rs.getString(6));
        },actor.tenantId(),id,size,(long)page*size);
        return ApiPage.of(rows,page,size,count==null?0:count);
    }
    private BatchView load(FoundationImportPolicy.Actor actor,UUID id) {
        var list=jdbc.query("SELECT * FROM foundation_import_batch WHERE id=? AND tenant_id=? AND created_by=?",(rs,index)->mapBatch(rs),id,actor.tenantId(),actor.userId());
        if(list.isEmpty())throw missing();return list.getFirst();
    }
    private BatchView mapBatch(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new BatchView(rs.getObject("id",UUID.class),rs.getString("import_kind"),rs.getString("file_name"),rs.getString("status"),rs.getInt("row_count"),rs.getInt("error_count"),rs.getInt("warning_count"),rs.getInt("created_count"),rs.getInt("updated_count"),rs.getInt("unchanged_count"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("committed_at",OffsetDateTime.class),rs.getString("reason"));
    }
    private void audit(FoundationImportPolicy.Actor actor,Jwt jwt,HttpServletRequest request,UUID id,String action,Object result,String reason) {
        audit.recordIfSensitive(actor.tenantId(),actor.userId(),"foundation:import",action,"foundation_import_batch",id,null,result,reason,request.getRemoteAddr(),jwt.getClaimAsString("device_id"),request.getHeader("User-Agent"));
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception exception){throw new IllegalStateException(exception);}}
    private Map<String,String> decodeMap(String value){try{return json.readValue(value,new TypeReference<LinkedHashMap<String,String>>(){});}catch(Exception exception){throw new IllegalStateException(exception);}}
    private List<String> decodeList(String value){try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(Exception exception){throw new IllegalStateException(exception);}}
    private ApiProblem missing(){return new ApiProblem("IMPORT_BATCH_NOT_FOUND","Không tìm thấy lô nhập trong phạm vi tài khoản.",HttpStatus.NOT_FOUND);}
    public record TypeView(String kind,String label,List<String> sheets,boolean demoTenant){}
    public record BatchView(UUID id,String kind,String fileName,String status,int rowCount,int errorCount,int warningCount,int createdCount,int updatedCount,int unchangedCount,OffsetDateTime createdAt,OffsetDateTime committedAt,String reason){}
    public record RowView(String sheet,int line,Map<String,String> data,List<String> errors,List<String> warnings,String operation){}
}
