package com.synera.api.importing;

import com.synera.api.auth.AuthIdentity;
import com.synera.api.auth.AuthIdentityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
public class FoundationImportPolicy {
    private final AuthIdentityRepository identities;
    private final JdbcTemplate jdbc;
    public FoundationImportPolicy(AuthIdentityRepository identities, JdbcTemplate jdbc) {
        this.identities = identities; this.jdbc = jdbc;
    }
    public Actor actor(Jwt jwt) {
        AuthIdentity identity = identities.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new AccessDeniedException("Tài khoản không còn hiệu lực."));
        if (!identity.tenantId().toString().equals(jwt.getClaimAsString("tenant_id"))
                || !"ACTIVE".equals(identity.userStatus()) || !"ACTIVE".equals(identity.tenantStatus())
                || !"A".equals(identity.permissionScopes().get("foundation:import")))
            throw new AccessDeniedException("Bạn không có quyền nhập dữ liệu nền trong tenant này.");
        if(!(identity.tenantType().equals("NSX")&&identity.roleCodes().contains("NSX_ADMIN"))
                &&!(identity.tenantType().equals("NPP")&&identity.roleCodes().stream().anyMatch(role->Set.of("NPP_OWNER","NPP_STAFF").contains(role))))
            throw new AccessDeniedException("Vai hiện tại không được nhập dữ liệu nền.");
        Boolean demo = jdbc.queryForObject("SELECT is_demo FROM tenant WHERE id = ?", Boolean.class, identity.tenantId());
        return new Actor(identity, Boolean.TRUE.equals(demo));
    }
    public Set<ImportKind> allowed(Actor actor) {
        var user = actor.identity();
        if (user.tenantType().equals("NSX") && user.roleCodes().contains("NSX_ADMIN")) return EnumSet.allOf(ImportKind.class);
        if (user.tenantType().equals("NPP") && user.roleCodes().contains("NPP_OWNER"))
            return EnumSet.of(ImportKind.OUTLETS, ImportKind.USERS, ImportKind.INVENTORY);
        if (user.tenantType().equals("NPP") && user.roleCodes().contains("NPP_STAFF"))
            return EnumSet.of(ImportKind.OUTLETS, ImportKind.INVENTORY);
        return Set.of();
    }
    public void require(Actor actor, ImportKind kind) {
        if (!allowed(actor).contains(kind)) throw new AccessDeniedException("Vai hiện tại không được nhập loại dữ liệu này.");
    }
    public record Actor(AuthIdentity identity, boolean demo) {
        public UUID tenantId() { return identity.tenantId(); }
        public UUID userId() { return identity.id(); }
        public boolean nsx() { return identity.tenantType().equals("NSX"); }
    }
}
