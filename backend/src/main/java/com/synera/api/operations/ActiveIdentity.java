package com.synera.api.operations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class ActiveIdentity {
    private final JdbcTemplate jdbc;
    public ActiveIdentity(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Jwt fresh(Jwt jwt){
        Identity actor=require(jwt);
        var user=new com.synera.api.auth.AuthIdentityRepository(jdbc).findById(actor.user()).orElseThrow(()->new AccessDeniedException("Tài khoản không tồn tại."));
        return Jwt.withTokenValue(jwt.getTokenValue()).headers(h->h.putAll(jwt.getHeaders())).claims(c->c.putAll(jwt.getClaims()))
            .claim("tenant_type",user.tenantType()).claim("permission_scopes",user.permissionScopes())
            .claim("org_unit_ids",user.orgUnitIds().stream().map(UUID::toString).toList())
            .claim("route_ids",user.routeIds().stream().map(UUID::toString).toList()).build();
    }
    public Identity require(Jwt jwt) {
        UUID user=UUID.fromString(jwt.getSubject()),tenant=UUID.fromString(jwt.getClaimAsString("tenant_id"));
        Boolean ok=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_users u JOIN tenant t ON t.id=u.tenant_id WHERE u.id=? AND u.tenant_id=? AND u.status='ACTIVE' AND t.status='ACTIVE')",Boolean.class,user,tenant);
        if(!Boolean.TRUE.equals(ok))throw new AccessDeniedException("Tài khoản không còn hoạt động trong tenant này.");
        return new Identity(user,tenant);
    }
    public Identity admin(Jwt jwt) {
        Identity actor=require(jwt);
        Boolean ok=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code WHERE ur.user_id=? AND ur.tenant_id=? AND rp.permission_code='config:manage' AND rp.scope_code='A')",Boolean.class,actor.user(),actor.tenant());
        if(!Boolean.TRUE.equals(ok))throw new AccessDeniedException("Bạn không có quyền quản lý cấu hình tenant.");
        return actor;
    }
    public Identity systemAdmin(Jwt jwt) {
        Identity actor=require(jwt);
        Boolean ok=jdbc.queryForObject("""
            SELECT EXISTS(
                SELECT 1 FROM user_role ur
                JOIN tenant t ON t.id=ur.tenant_id
                WHERE ur.user_id=? AND ur.tenant_id=? AND ur.role_code='SYS_ADMIN'
                  AND t.tenant_type='PLATFORM'
            )
            """,Boolean.class,actor.user(),actor.tenant());
        if(!Boolean.TRUE.equals(ok))throw new AccessDeniedException("Chức năng này chỉ dành cho SYS_ADMIN.");
        return actor;
    }
    public record Identity(UUID user,UUID tenant){}
}
