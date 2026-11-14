package com.synera.api.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synera.api.auth.ApiProblem;
import com.synera.api.catalog.ApiPage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;private final ActiveIdentity identity;private final ObjectMapper json;
    public NotificationService(JdbcTemplate jdbc,ActiveIdentity identity,ObjectMapper json){this.jdbc=jdbc;this.identity=identity;this.json=json;}
    public ApiPage<Notice> list(Jwt jwt,boolean unread,int page,int size){
        var actor=identity.require(jwt);String where="tenant_id=? AND recipient_id=?"+(unread?" AND read_at IS NULL":"");
        long count=jdbc.queryForObject("SELECT count(*) FROM notification WHERE "+where,Long.class,actor.tenant(),actor.user());
        var rows=jdbc.query("SELECT id,notification_type,payload::text,created_at,read_at FROM notification WHERE "+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(rs,row)->new Notice(rs.getObject(1,UUID.class),rs.getString(2),parse(rs.getString(3)),rs.getObject(4,OffsetDateTime.class),rs.getObject(5,OffsetDateTime.class)),actor.tenant(),actor.user(),size,(long)page*size);
        return ApiPage.of(rows,page,size,count);
    }
    public int read(Jwt jwt,UUID id){var actor=identity.require(jwt);return jdbc.update("UPDATE notification SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE tenant_id=? AND recipient_id=? AND id=?",actor.tenant(),actor.user(),id);}
    public int readAll(Jwt jwt){var actor=identity.require(jwt);return jdbc.update("UPDATE notification SET read_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND recipient_id=? AND read_at IS NULL",actor.tenant(),actor.user());}
    @Transactional
    public UUID register(Jwt jwt,String device,String token){
        var actor=identity.require(jwt);
        if(!device.equals(jwt.getClaimAsString("device_id")))throw new IllegalArgumentException("Thiết bị phải khớp phiên đăng nhập.");
        if(!token.matches("(?:ExponentPushToken|ExpoPushToken)\\[[A-Za-z0-9_-]{10,200}\\]"))throw new IllegalArgumentException("Expo push token không hợp lệ.");
        // Never transfer a token to another signed-in account implicitly.
        var existing=jdbc.queryForList("SELECT user_id FROM push_device WHERE expo_token=? FOR UPDATE",token);
        if(!existing.isEmpty()&&!actor.user().equals(existing.get(0).get("user_id")))throw new ApiProblem("DEVICE_ALREADY_REGISTERED","Thiết bị đang thuộc tài khoản khác; cần hủy đăng ký từ tài khoản đó.",HttpStatus.CONFLICT);
        long count=jdbc.queryForObject("SELECT count(*) FROM push_device WHERE user_id=? AND active AND device_id<>?",Long.class,actor.user(),device);
        if(count>=5)throw new IllegalArgumentException("Đã đạt giới hạn 5 thiết bị push.");
        return jdbc.queryForObject("INSERT INTO push_device(id,tenant_id,user_id,device_id,expo_token) VALUES(?,?,?,?,?) ON CONFLICT(user_id,device_id) DO UPDATE SET expo_token=EXCLUDED.expo_token,active=true,updated_at=CURRENT_TIMESTAMP RETURNING id",UUID.class,UUID.randomUUID(),actor.tenant(),actor.user(),device,token);
    }
    public int unregister(Jwt jwt,String device){var actor=identity.require(jwt);return jdbc.update("UPDATE push_device SET active=false,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND user_id=? AND device_id=?",actor.tenant(),actor.user(),device);}
    private JsonNode parse(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    public record Notice(UUID id,String type,JsonNode payload,OffsetDateTime createdAt,OffsetDateTime readAt){}
}
