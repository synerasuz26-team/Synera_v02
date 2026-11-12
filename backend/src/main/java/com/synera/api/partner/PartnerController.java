package com.synera.api.partner;

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

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class PartnerController {
    private final PartnerService partners;

    public PartnerController(PartnerService partners) {
        this.partners = partners;
    }

    @GetMapping("/npps")
    @RequirePermission("npp:view")
    public ApiPage<PartnerService.NppSummary> npps(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 120) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return partners.npps(jwt, q, page, size);
    }

    @GetMapping("/npps/{nppId}")
    @RequirePermission("npp:view")
    public PartnerService.NppSummary npp(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID nppId) {
        return partners.npp(jwt, nppId);
    }

    @GetMapping("/outlets")
    @RequirePermission("outlet:view")
    public ApiPage<PartnerService.OutletSummary> outlets(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 120) String q,
            @RequestParam(required = false) @Size(max = 20) String status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return partners.outlets(jwt, q, status, page, size);
    }

    @GetMapping("/outlets/{outletId}")
    @RequirePermission("outlet:view")
    public PartnerService.Outlet360 outlet(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID outletId) {
        return partners.outlet(jwt, outletId);
    }

    @PostMapping("/outlets")
    @RequirePermission("outlet:create")
    public PartnerService.OutletSummary createOutlet(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PartnerService.OutletCreateRequest request,
            HttpServletRequest servletRequest) {
        return partners.createOutlet(jwt, request, servletRequest);
    }

    @PutMapping("/outlets/{outletId}")
    @RequirePermission("outlet:update")
    public PartnerService.OutletSummary updateOutlet(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID outletId,
            @Valid @RequestBody PartnerService.OutletUpdateRequest request,
            HttpServletRequest servletRequest) {
        return partners.updateOutlet(jwt, outletId, request, servletRequest);
    }

    @PostMapping("/outlets/{outletId}/approve")
    @RequirePermission("outlet:approve")
    public PartnerService.OutletSummary approveOutlet(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID outletId,
            @Valid @RequestBody PartnerService.OutletApprovalRequest request,
            HttpServletRequest servletRequest) {
        return partners.approveOutlet(jwt, outletId, request, true, servletRequest);
    }

    @PostMapping("/outlets/{outletId}/reject")
    @RequirePermission("outlet:approve")
    public PartnerService.OutletSummary rejectOutlet(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID outletId,
            @Valid @RequestBody PartnerService.OutletApprovalRequest request,
            HttpServletRequest servletRequest) {
        return partners.approveOutlet(jwt, outletId, request, false, servletRequest);
    }

    @PostMapping("/outlets/{outletId}/npp-assignments")
    @RequirePermission("outlet:assign")
    public void assignNpp(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID outletId,
            @Valid @RequestBody PartnerService.OutletNppAssignmentRequest request,
            HttpServletRequest servletRequest) {
        partners.assignNpp(jwt, outletId, request, servletRequest);
    }

    @GetMapping("/outlets/{outletId}/contacts")
    @RequirePermission("contact:manage")
    public List<PartnerService.ContactSummary> contacts(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID outletId) {
        return partners.contacts(jwt, outletId);
    }

    @PostMapping("/outlets/{outletId}/contacts")
    @RequirePermission("contact:manage")
    public PartnerService.ContactSummary createContact(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID outletId,
            @Valid @RequestBody PartnerService.ContactRequest request,
            HttpServletRequest servletRequest) {
        return partners.createContact(jwt, outletId, request, servletRequest);
    }

    @PutMapping("/outlets/{outletId}/contacts/{contactId}")
    @RequirePermission("contact:manage")
    public PartnerService.ContactSummary updateContact(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID outletId,
            @PathVariable UUID contactId,
            @Valid @RequestBody PartnerService.ContactUpdateRequest request,
            HttpServletRequest servletRequest) {
        return partners.updateContact(jwt, outletId, contactId, request, servletRequest);
    }
}
