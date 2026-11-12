package com.synera.api.catalog;

import com.synera.api.security.RequireAnyPermission;
import com.synera.api.security.RequirePermission;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/vouchers")
@SecurityRequirement(name = "bearerAuth")
public class VoucherController {
    private final VoucherService vouchers;

    public VoucherController(VoucherService vouchers) {
        this.vouchers = vouchers;
    }

    @GetMapping
    @RequirePermission("promotion:view")
    public ApiPage<VoucherService.VoucherSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return vouchers.list(jwt, nppId, outletId, page, size);
    }

    @GetMapping("/{id}")
    @RequireAnyPermission({"promotion:manage", "promotion:approve"})
    public VoucherService.VoucherDetail get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId, HttpServletRequest servletRequest) {
        return vouchers.getForNsx(jwt, id, nppId, outletId, servletRequest);
    }

    @PostMapping
    @RequirePermission("promotion:manage")
    public VoucherService.VoucherDetail create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody VoucherService.VoucherRequest request,
            HttpServletRequest servletRequest) {
        return vouchers.create(jwt, request, servletRequest);
    }

    @PostMapping("/{id}/submit")
    @RequirePermission("promotion:manage")
    public VoucherService.VoucherDetail submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @Valid @RequestBody VoucherService.WorkflowRequest request,
            HttpServletRequest servletRequest) {
        return vouchers.submit(jwt, id, nppId, outletId, request, servletRequest);
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("promotion:approve")
    public VoucherService.VoucherDetail approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @Valid @RequestBody VoucherService.WorkflowRequest request,
            HttpServletRequest servletRequest) {
        return vouchers.approve(jwt, id, nppId, outletId, request, servletRequest);
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("promotion:approve")
    public VoucherService.VoucherDetail reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @Valid @RequestBody VoucherService.WorkflowRequest request,
            HttpServletRequest servletRequest) {
        return vouchers.reject(jwt, id, nppId, outletId, request, servletRequest);
    }

    @PostMapping("/{id}/activate")
    @RequirePermission("promotion:approve")
    public VoucherService.VoucherDetail activate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @Valid @RequestBody VoucherService.ActivateRequest request,
            HttpServletRequest servletRequest) {
        return vouchers.activate(jwt, id, nppId, outletId, request, servletRequest);
    }
}
