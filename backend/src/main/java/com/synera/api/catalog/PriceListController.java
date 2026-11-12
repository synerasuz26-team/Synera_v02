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
@RequestMapping("/api/v1/price-lists")
@SecurityRequirement(name = "bearerAuth")
public class PriceListController {
    private final PriceListService priceLists;

    public PriceListController(PriceListService priceLists) {
        this.priceLists = priceLists;
    }

    @GetMapping
    @RequireAnyPermission({"price_list:manage", "price_list:approve"})
    public ApiPage<PriceListService.PriceListSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @RequestParam(required = false)
            @jakarta.validation.constraints.Pattern(
                    regexp = "DRAFT|PENDING_APPROVAL|APPROVED|EFFECTIVE|REJECTED|EXPIRED") String status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            HttpServletRequest servletRequest) {
        return priceLists.list(jwt, nppId, outletId, status, page, size, servletRequest);
    }

    @GetMapping("/{id}")
    @RequireAnyPermission({"price_list:manage", "price_list:approve"})
    public PriceListService.PriceListDetail get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId, HttpServletRequest servletRequest) {
        return priceLists.get(jwt, id, nppId, outletId, servletRequest);
    }

    @PostMapping
    @RequirePermission("price_list:manage")
    public PriceListService.PriceListDetail create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PriceListService.PriceListRequest request,
            HttpServletRequest servletRequest) {
        return priceLists.create(jwt, request, servletRequest);
    }

    @PostMapping("/{id}/submit")
    @RequirePermission("price_list:manage")
    public PriceListService.PriceListDetail submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @Valid @RequestBody PriceListService.WorkflowRequest request,
            HttpServletRequest servletRequest) {
        return priceLists.submit(jwt, id, nppId, outletId, request, servletRequest);
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("price_list:approve")
    public PriceListService.PriceListDetail approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @Valid @RequestBody PriceListService.WorkflowRequest request,
            HttpServletRequest servletRequest) {
        return priceLists.approve(jwt, id, nppId, outletId, request, servletRequest);
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("price_list:approve")
    public PriceListService.PriceListDetail reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @Valid @RequestBody PriceListService.WorkflowRequest request,
            HttpServletRequest servletRequest) {
        return priceLists.reject(jwt, id, nppId, outletId, request, servletRequest);
    }

    @PostMapping("/{id}/activate")
    @RequirePermission("price_list:approve")
    public PriceListService.PriceListDetail activate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @Valid @RequestBody PriceListService.ActivateRequest request,
            HttpServletRequest servletRequest) {
        return priceLists.activate(jwt, id, nppId, outletId, request, servletRequest);
    }
}
