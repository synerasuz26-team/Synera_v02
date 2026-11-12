package com.synera.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
public class RoleAssignmentController {
    private final RoleAssignmentService roleAssignments;

    public RoleAssignmentController(RoleAssignmentService roleAssignments) {
        this.roleAssignments = roleAssignments;
    }

    @RequirePermission("role:assign")
    @PostMapping("/{userId}/roles")
    public ResponseEntity<Void> assignRole(
            @PathVariable UUID userId,
            @Valid @RequestBody RoleAssignmentRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest servletRequest) {
        roleAssignments.assign(
                UUID.fromString(jwt.getSubject()),
                userId,
                request.roleCode(),
                request.reason(),
                servletRequest.getRemoteAddr(),
                jwt.getClaimAsString("device_id"),
                servletRequest.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    public record RoleAssignmentRequest(
            @NotBlank @Size(max = 40) String roleCode,
            @NotBlank @Size(max = 500) String reason) {
    }
}
