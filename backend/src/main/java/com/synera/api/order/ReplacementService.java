package com.synera.api.order;

import com.synera.api.auth.ApiProblem;
import com.synera.api.operations.ActiveIdentity;
import com.synera.api.organization.BusinessAccessService;
import com.synera.api.security.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ReplacementService {
    private final JdbcTemplate jdbc;private final ActiveIdentity identity;private final BusinessAccessService access;
    private final SellOutOrderService orders;private final OrderFlowService flow;private final AuditService audit;
    public ReplacementService(JdbcTemplate jdbc,ActiveIdentity identity,BusinessAccessService access,SellOutOrderService orders,OrderFlowService flow,AuditService audit){this.jdbc=jdbc;this.identity=identity;this.access=access;this.orders=orders;this.flow=flow;this.audit=audit;}
    public List<Map<String,Object>> list(Jwt token,UUID order){Jwt jwt=identity.fresh(token);access.resolve(jwt,"order_so:view");orders.get(jwt,order);return jdbc.queryForList("SELECT r.id,r.status,r.request_type AS type,r.reason,r.resolution,r.requested_by AS requester,r.version,d.status AS delivery_status FROM return_request r LEFT JOIN replacement_delivery d ON d.return_request_id=r.id WHERE r.order_so_id=? AND r.deleted_at IS NULL ORDER BY r.created_at,r.id",order);}
    @Transactional
    public Map<String,Object> advance(Jwt token,UUID requestId,long version,String reason,boolean receive,HttpServletRequest servlet){
        Jwt jwt=identity.fresh(token);var actor=identity.require(jwt);String permission=receive?"order_so:receive_confirm":"order_so:ship_update";access.resolve(jwt,permission);
        var refs=jdbc.queryForList("SELECT order_so_id FROM return_request WHERE id=? AND deleted_at IS NULL",requestId);
        if(refs.isEmpty())throw new AccessDeniedException("Yêu cầu ngoài phạm vi.");UUID orderId=(UUID)refs.getFirst().get("order_so_id");
        orders.get(jwt,orderId); // Includes tenant, outlet and route scope before taking any write lock.
        var rows=jdbc.queryForList("SELECT r.tenant_id,r.status,r.request_type,r.version,d.status AS delivery_status,d.shipped_by,os.version AS order_version,os.status AS order_status FROM return_request r JOIN replacement_delivery d ON d.return_request_id=r.id JOIN order_so os ON os.id=r.order_so_id WHERE r.id=? FOR UPDATE OF r,d,os",requestId);
        if(rows.isEmpty())throw new ApiProblem("REPLACEMENT_NOT_APPROVED","Yêu cầu giao bù chưa được duyệt.",HttpStatus.CONFLICT);
        var row=rows.getFirst();UUID tenant=(UUID)row.get("tenant_id");String target=receive?"RECEIVED":"SHIPPED";
        if(!"APPROVED".equals(row.get("status"))||!"REPLACEMENT".equals(row.get("request_type")))throw new ApiProblem("REPLACEMENT_NOT_APPROVED","Yêu cầu giao bù chưa được duyệt.",HttpStatus.CONFLICT);
        if(!receive&&(!"NPP".equals(jwt.getClaimAsString("tenant_type"))||!tenant.equals(actor.tenant())))throw new AccessDeniedException("Chỉ NPP phụ trách được giao bù.");
        if(receive&&actor.user().equals(row.get("shipped_by")))throw new AccessDeniedException("Người giao không tự xác nhận nhận bù.");
        if(target.equals(row.get("delivery_status")))return Map.of("id",requestId,"status",target,"version",row.get("version"));
        if(!"RECEIVED_ISSUE".equals(row.get("order_status"))||!Objects.equals(row.get("delivery_status"),receive?"SHIPPED":"PENDING")||((Number)row.get("version")).longValue()!=version)throw new ApiProblem("REPLACEMENT_VERSION_OR_STATUS","Giao bù đã thay đổi hoặc chưa đúng bước.",HttpStatus.CONFLICT);
        if(receive){
            jdbc.update("UPDATE order_line l SET shipped_qty=GREATEST(l.shipped_qty,LEAST(l.confirmed_qty,l.received_qty+rrl.quantity)),received_qty=LEAST(l.confirmed_qty,l.received_qty+rrl.quantity) FROM return_request_line rrl WHERE rrl.return_request_id=? AND rrl.order_line_id=l.id AND l.order_so_id=?",requestId,orderId);
            jdbc.update("UPDATE replacement_delivery SET status='RECEIVED',received_by=?,received_at=CURRENT_TIMESTAMP WHERE return_request_id=?",actor.user(),requestId);
            long missing=jdbc.queryForObject("SELECT count(*) FROM order_line WHERE order_so_id=? AND deleted_at IS NULL AND received_qty<confirmed_qty",Long.class,orderId);
            long pending=jdbc.queryForObject("SELECT count(*) FROM replacement_delivery d JOIN return_request r ON r.id=d.return_request_id WHERE r.order_so_id=? AND d.status<>'RECEIVED'",Long.class,orderId);
            if(missing==0&&pending==0){
                long orderVersion=((Number)row.get("order_version")).longValue();orderVersion=flow.transition("SELL_OUT",tenant,orderId,"RECEIVED_ISSUE","RECEIVED_FULL","SYSTEM",null,"Đã nhận đủ giao bù.",orderVersion);
                flow.transition("SELL_OUT",tenant,orderId,"RECEIVED_FULL","CLOSED","SYSTEM",null,"Đóng sau xác nhận nhận bù.",orderVersion);
                jdbc.update("INSERT INTO ticket_event(id,tenant_id,ticket_id,actor_id,event_type,body) SELECT gen_random_uuid(),tenant_id,id,?,'REPLACEMENT_RECEIVED',? FROM ticket WHERE order_so_id=? AND receipt_issue AND deleted_at IS NULL",actor.user(),reason,orderId);
                jdbc.update("UPDATE ticket SET status='CLOSED',updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE order_so_id=? AND receipt_issue AND deleted_at IS NULL AND status NOT IN('CLOSED','CANCELLED')",orderId);
                jdbc.update("UPDATE task SET status='DONE',updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE tenant_id=? AND order_so_id=? AND status IN('OPEN','IN_PROGRESS') AND deleted_at IS NULL",tenant,orderId);
            }
        }else jdbc.update("UPDATE replacement_delivery SET status='SHIPPED',shipped_by=?,shipped_at=CURRENT_TIMESTAMP WHERE return_request_id=?",actor.user(),requestId);
        jdbc.update("UPDATE return_request SET version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",requestId);
        audit.recordIfSensitive(tenant,actor.user(),permission,"REPLACEMENT_"+target,"return_request",requestId,row,Map.of("status",target),reason,servlet.getRemoteAddr(),jwt.getClaimAsString("device_id"),servlet.getHeader("User-Agent"));
        return Map.of("id",requestId,"status",target,"version",version+1);
    }
}
