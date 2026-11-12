package com.synera.api.organization;

import com.synera.api.catalog.ApiPage;
import com.synera.api.security.RequirePermission;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/org-units")
@SecurityRequirement(name = "bearerAuth")
public class OrganizationController {
    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @GetMapping
    @RequirePermission("org_unit:view")
    public ApiPage<OrganizationService.OrgUnit> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 120) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return organizations.list(jwt, q, page, size);
    }

    @PostMapping
    @RequirePermission("org_unit:manage")
    public OrganizationService.OrgUnit create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody OrganizationService.OrgUnitRequest request,
            HttpServletRequest servletRequest) {
        return organizations.create(jwt, request, servletRequest);
    }

    @PutMapping("/{id}")
    @RequirePermission("org_unit:manage")
    public OrganizationService.OrgUnit update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody OrganizationService.OrgUnitUpdateRequest request,
            HttpServletRequest servletRequest) {
        return organizations.update(jwt, id, request, servletRequest);
    }
}
