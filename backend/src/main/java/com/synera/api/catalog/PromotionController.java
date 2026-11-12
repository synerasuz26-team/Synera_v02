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
@RequestMapping("/api/v1/promotions")
@SecurityRequirement(name = "bearerAuth")
public class PromotionController {
    private final PromotionService promotions;

    public PromotionController(PromotionService promotions) {
        this.promotions = promotions;
    }

    @GetMapping
    @RequirePermission("promotion:view")
    public ApiPage<PromotionService.PromotionSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return promotions.list(jwt, nppId, outletId, page, size);
    }

    @GetMapping("/{id}")
    @RequireAnyPermission({"promotion:manage", "promotion:approve"})
    public PromotionService.PromotionDetail get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId, HttpServletRequest servletRequest) {
        return promotions.getForNsx(jwt, id, nppId, outletId, servletRequest);
    }

    @PostMapping
    @RequirePermission("promotion:manage")
    public PromotionService.PromotionDetail create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PromotionService.PromotionRequest request,
            HttpServletRequest servletRequest) {
        return promotions.create(jwt, request, servletRequest);
    }

    @PostMapping("/{id}/submit")
    @RequirePermission("promotion:manage")
    public PromotionService.PromotionDetail submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @Valid @RequestBody PromotionService.WorkflowRequest request,
            HttpServletRequest servletRequest) {
        return promotions.submit(jwt, id, nppId, outletId, request, servletRequest);
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("promotion:approve")
    public PromotionService.PromotionDetail approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @Valid @RequestBody PromotionService.WorkflowRequest request,
            HttpServletRequest servletRequest) {
        return promotions.approve(jwt, id, nppId, outletId, request, servletRequest);
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("promotion:approve")
    public PromotionService.PromotionDetail reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @Valid @RequestBody PromotionService.WorkflowRequest request,
            HttpServletRequest servletRequest) {
        return promotions.reject(jwt, id, nppId, outletId, request, servletRequest);
    }

    @PostMapping("/{id}/activate")
    @RequirePermission("promotion:approve")
    public PromotionService.PromotionDetail activate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @Valid @RequestBody PromotionService.ActivateRequest request,
            HttpServletRequest servletRequest) {
        return promotions.activate(jwt, id, nppId, outletId, request, servletRequest);
    }
}
