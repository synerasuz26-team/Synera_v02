package com.synera.api.files;

import com.synera.api.auth.ApiProblem;
import com.synera.api.operations.*;
import com.synera.api.organization.BusinessAccessService;
import com.synera.api.order.SellOutOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.*;

@Service
public class AttachmentService {
    private static final String SHARED_RECEIPT="(a.owner_type='ORDER_SO' AND a.upload_status='READY' AND EXISTS(SELECT 1 FROM order_receipt_attachment ra WHERE ra.attachment_id=a.id AND ra.order_so_id=a.owner_id))";
    private final JdbcTemplate jdbc;private final ObjectStorage storage;private final ActiveIdentity identity;
    private final RuntimeSettings settings;private final BusinessAccessService access;private final SellOutOrderService sellOut;
    @Value("${app.files.photos-enabled:false}") private boolean photosEnabled;
    public AttachmentService(JdbcTemplate jdbc,ObjectStorage storage,ActiveIdentity identity,RuntimeSettings settings,BusinessAccessService access,SellOutOrderService sellOut){this.jdbc=jdbc;this.storage=storage;this.identity=identity;this.settings=settings;this.access=access;this.sellOut=sellOut;}
    public Map<String,Object> options(Jwt jwt){var actor=identity.require(jwt);var config=settings.get(actor.tenant());return Map.of("enabled",photosEnabled,"maxBytes",Math.min(200_000L,config.number("upload_max_bytes")),"maxEdge",Math.min(1280L,config.number("image_max_edge")),"formats",List.of("image/jpeg","image/png"));}
    @Transactional
    public Map<String,Object> prepare(Jwt jwt,String ownerType,UUID ownerId,String filename,String contentType,long size){
        var actor=identity.require(jwt);
        requirePhotosEnabled();
        authorize(jwt,ownerType,ownerId,true);
        var config=settings.get(actor.tenant());
        if(!Set.of("image/jpeg","image/png").contains(contentType)||size<1||size>Math.min(200_000L,config.number("upload_max_bytes")))throw new IllegalArgumentException("Chỉ nhận ảnh JPEG/PNG đã nén, tối đa 200 KB.");
        if(Set.of("VISIT","TICKET").contains(ownerType)){
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{
                try(var statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){
                    statement.setString(1,actor.tenant()+":"+ownerType+":"+ownerId);
                    statement.execute();
                }
                return null;
            });
            Long count=jdbc.queryForObject("SELECT count(*) FROM attachment WHERE tenant_id=? AND owner_type=? AND owner_id=? AND upload_status IN('PENDING','READY')",Long.class,actor.tenant(),ownerType,ownerId);
            if(count!=null&&count>=3)throw new ApiProblem("ATTACHMENT_LIMIT_REACHED","Mỗi lượt ghé hoặc ticket tối đa 3 ảnh.",HttpStatus.CONFLICT);
        }
        DatabaseCapacityPolicy.requireCapacityForHeavyWrite(jdbc);
        UUID id=UUID.randomUUID();String raw="quarantine/"+actor.tenant()+"/"+id;var expiry=Duration.ofMinutes(config.number("upload_expiry_minutes"));
        var url=storage.uploadUrl(raw,contentType,size,expiry);
        jdbc.update("INSERT INTO attachment(id,tenant_id,owner_type,owner_id,storage_key,upload_key,file_name,content_type,size_bytes,uploaded_by,upload_status,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,'PENDING',?)",id,actor.tenant(),ownerType,ownerId,raw,raw,filename,contentType,size,actor.user(),OffsetDateTime.now(ZoneOffset.UTC).plus(expiry));
        return Map.of("id",id,"url",url.url(),"headers",url.headers(),"expiresInSeconds",expiry.toSeconds());
    }
    @Transactional
    public Map<String,Object> complete(Jwt jwt,UUID id){
        var actor=identity.require(jwt);requirePhotosEnabled();var row=load(actor.tenant(),id,true);
        if(!actor.user().equals(row.get("uploaded_by")))throw new AccessDeniedException("Chỉ người tải lên được hoàn tất tệp.");
        authorize(jwt,(String)row.get("owner_type"),(UUID)row.get("owner_id"),true);
        if("READY".equals(row.get("upload_status")))return Map.of("id",id,"status","READY");
        OffsetDateTime expires=(OffsetDateTime)row.get("expires_at");
        if(!"PENDING".equals(row.get("upload_status"))||expires==null||!expires.isAfter(OffsetDateTime.now()))throw new ApiProblem("UPLOAD_EXPIRED","Phiên tải lên đã hết hạn.",HttpStatus.CONFLICT);
        DatabaseCapacityPolicy.requireCapacityForHeavyWrite(jdbc);
        var config=settings.get(actor.tenant());
        byte[] bytes=storage.read((String)row.get("upload_key"),((Number)row.get("size_bytes")).longValue(),(int)config.number("upload_max_bytes"));
        var images=ImageSanitizer.sanitize(bytes,(String)row.get("content_type"),(int)Math.min(1280L,config.number("image_max_edge")),(int)Math.min(256L,config.number("thumbnail_edge")));
        // Public objects have different keys from the signed, overwriteable quarantine object.
        String key="images/"+actor.tenant()+"/"+id+".jpg",thumbnail="thumbnails/"+actor.tenant()+"/"+id+".jpg";
        storage.write(key,images.image(),"image/jpeg");storage.write(thumbnail,images.thumbnail(),"image/jpeg");
        jdbc.update("UPDATE attachment SET storage_key=?,thumbnail_key=?,content_type='image/jpeg',size_bytes=?,upload_status='READY',completed_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=?",key,thumbnail,images.image().length,id,actor.tenant());
        return Map.of("id",id,"status","READY");
    }
    public Map<String,Object> download(Jwt jwt,UUID id,boolean thumbnail){
        var actor=identity.require(jwt);var row=load(actor.tenant(),id,false);
        authorize(jwt,(String)row.get("owner_type"),(UUID)row.get("owner_id"),false);
        if(!"READY".equals(row.get("upload_status")))throw new ApiProblem("FILE_NOT_READY","Ảnh chưa được kiểm tra hoặc là tệp legacy cần chuyển kho.",HttpStatus.CONFLICT);
        return Map.of("url",storage.downloadUrl((String)row.get(thumbnail?"thumbnail_key":"storage_key"),Duration.ofMinutes(5)),"expiresInSeconds",300);
    }
    public List<Map<String,Object>> list(Jwt jwt,String type,UUID owner){var actor=identity.require(jwt);authorize(jwt,type,owner,false);return jdbc.queryForList("SELECT a.id,a.file_name,a.content_type,a.size_bytes,a.created_at FROM attachment a WHERE (a.tenant_id=? OR "+SHARED_RECEIPT+") AND a.owner_type=? AND a.owner_id=? AND a.upload_status='READY' ORDER BY a.created_at DESC LIMIT 100",actor.tenant(),type,owner);}
    private Map<String,Object> load(UUID tenant,UUID id,boolean lock){var rows=jdbc.query("SELECT a.* FROM attachment a WHERE a.id=? AND (a.tenant_id=?"+(lock?"":" OR "+SHARED_RECEIPT)+")"+(lock?" FOR UPDATE":""),(rs,row)->{var data=new org.springframework.jdbc.core.ColumnMapRowMapper().mapRow(rs,row);data.put("expires_at",rs.getObject("expires_at",OffsetDateTime.class));return data;},id,tenant);if(rows.isEmpty())throw new AccessDeniedException("Không tìm thấy tệp trong tenant hiện tại.");return rows.get(0);}
    private void authorize(Jwt jwt,String type,UUID owner,boolean write){
        jwt=identity.fresh(jwt);
        var actor=identity.require(jwt);
        switch(type){
            case "VISIT"->access.requireVisitAccess(jwt,write?"visit:checkin":"visit:view",owner);
            case "TICKET"->{
                String permission=write?"ticket:process":"ticket:view";
                var scopes=jwt.getClaimAsMap("permission_scopes");
                boolean creatorWrite=write&&scopes!=null&&scopes.containsKey("ticket:create")&&!scopes.containsKey("ticket:process");
                String scope=creatorWrite?"O":access.resolve(jwt,permission).scope();
                Boolean allowed=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ticket WHERE id=? AND tenant_id=? AND deleted_at IS NULL AND (?='A' OR created_by=? OR (?=false AND assignee_id=?)))",Boolean.class,owner,actor.tenant(),scope,actor.user(),creatorWrite,actor.user());
                if(!Boolean.TRUE.equals(allowed))throw new AccessDeniedException("Ticket ngoài phạm vi được phép.");
            }
            case "ORDER_SO"->{
                sellOut.get(jwt,owner);
                if(write)access.resolve(jwt,"order_so:receive_confirm");
            }
            default->throw new IllegalArgumentException("Loại tệp phải là VISIT, TICKET hoặc ORDER_SO.");
        }
    }

    private void requirePhotosEnabled(){
        if(!photosEnabled)throw new ApiProblem("FEATURE_DISABLED","Tính năng ảnh sắp có; hiện chưa cấu hình kho lưu trữ ngoài database.",HttpStatus.SERVICE_UNAVAILABLE);
    }
}
