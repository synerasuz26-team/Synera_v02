package com.synera.api.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class ExpoPushWorker {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final String accessToken;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public ExpoPushWorker(JdbcTemplate jdbc,ObjectMapper json,@Value("${EXPO_ACCESS_TOKEN:}") String accessToken){this.jdbc=jdbc;this.json=json;this.accessToken=accessToken;}
    public long deliver(UUID tenant,RuntimeSettings.Values config){
        long processed=0;
        jdbc.update("UPDATE push_delivery p SET status='CANCELLED',last_error='DEVICE_INACTIVE' FROM push_device d,notification n,app_users u,tenant t WHERE p.device_id=d.id AND p.notification_id=n.id AND u.id=d.user_id AND t.id=d.tenant_id AND p.tenant_id=? AND p.status IN('PENDING','WAITING_RECEIPT') AND (NOT d.active OR d.user_id<>n.recipient_id OR d.tenant_id<>n.tenant_id OR u.status<>'ACTIVE' OR t.status<>'ACTIVE')",tenant);
        var rows=jdbc.queryForList("SELECT p.id,p.device_id,p.status,p.attempts,p.ticket_id,d.expo_token,n.id AS notification_id,n.notification_type FROM push_delivery p JOIN push_device d ON d.id=p.device_id JOIN notification n ON n.id=p.notification_id JOIN app_users u ON u.id=d.user_id AND u.status='ACTIVE' JOIN tenant t ON t.id=d.tenant_id AND t.status='ACTIVE' WHERE p.tenant_id=? AND p.status IN('PENDING','WAITING_RECEIPT') AND p.next_attempt_at<=CURRENT_TIMESTAMP AND d.active AND d.user_id=n.recipient_id AND d.tenant_id=n.tenant_id ORDER BY p.created_at LIMIT 25 FOR UPDATE OF p",tenant);
        for(var row:rows){
            UUID id=(UUID)row.get("id");int attempt=((Number)row.get("attempts")).intValue()+1;boolean receipt="WAITING_RECEIPT".equals(row.get("status"));
            try {
                JsonNode reply=exchange(receipt?"getReceipts":"send",receipt?Map.of("ids",java.util.List.of(row.get("ticket_id"))):Map.of("to",row.get("expo_token"),"title","SYNERA","body",title(row.get("notification_type").toString()),"data",Map.of("notificationId",row.get("notification_id").toString()),"sound","default"));
                JsonNode data=receipt?reply.path("data").path(row.get("ticket_id").toString()):reply.path("data");
                if("ok".equals(data.path("status").asText())){
                    if(receipt)jdbc.update("UPDATE push_delivery SET status='DELIVERED',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
                    else if(!data.path("id").asText().isBlank())jdbc.update("UPDATE push_delivery SET status='WAITING_RECEIPT',ticket_id=?,attempts=0,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '15 minutes',updated_at=CURRENT_TIMESTAMP WHERE id=?",data.path("id").asText(),id);
                    else retry(id,attempt,config,"INVALID_TICKET",receipt);
                }else{
                    String error=data.path("details").path("error").asText("PROVIDER_ERROR");
                    if("DeviceNotRegistered".equals(error)){
                        jdbc.update("UPDATE push_device SET active=false WHERE id=?",row.get("device_id"));
                        jdbc.update("UPDATE push_delivery SET status='FAILED',last_error='DeviceNotRegistered',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
                    }else retry(id,attempt,config,error.matches("[A-Za-z0-9_]{1,100}")?error:"PROVIDER_ERROR",receipt);
                }
            }catch(Exception error){retry(id,attempt,config,"PUSH_NETWORK_OR_RESPONSE",receipt);}
            processed++;
        }
        return processed;
    }
    private void retry(UUID id,int attempt,RuntimeSettings.Values config,String error,boolean receipt){
        boolean exhausted=attempt>=config.number("push_max_attempts");
        jdbc.update("UPDATE push_delivery SET status=?,attempts=?,last_error=?,next_attempt_at=CURRENT_TIMESTAMP+(? * INTERVAL '1 second'),updated_at=CURRENT_TIMESTAMP WHERE id=?",exhausted?"FAILED":receipt?"WAITING_RECEIPT":"PENDING",attempt,error,Math.min(3600,30L*(1L<<Math.min(attempt,7))),id);
    }
    protected JsonNode exchange(String endpoint,Object payload)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("https://exp.host/--/api/v2/push/"+endpoint)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").header("Accept","application/json");
        if(!accessToken.isBlank())request.header("Authorization","Bearer "+accessToken);
        var response=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("Push provider unavailable");
        return json.readTree(response.body());
    }
    public static String title(String type){return switch(type){case "ORDER_STATUS_CHANGED"->"Đơn hàng đã đổi trạng thái";case "TASK_ASSIGNED"->"Bạn có công việc được giao";default->"Bạn có thông báo cần xem";};}
}
