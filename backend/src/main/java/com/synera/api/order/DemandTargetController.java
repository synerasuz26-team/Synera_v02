package com.synera.api.order;

import com.synera.api.catalog.ApiPage;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class DemandTargetController {
    private final DemandTargetService planning;

    public DemandTargetController(DemandTargetService planning) {
        this.planning = planning;
    }

    @GetMapping("/demand-plans")
    @RequirePermission("demand_plan:view")
    public ApiPage<DemandTargetService.DemandPlanView> demandPlans(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return planning.listDemandPlans(jwt, nppId, from, to, page, size);
    }

    @PostMapping("/demand-plans")
    @RequirePermission("demand_plan:submit")
    public DemandTargetService.DemandPlanView createDemandPlan(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DemandTargetService.DemandPlanRequest request,
            HttpServletRequest servletRequest) {
        return planning.createDemandPlan(jwt, request, servletRequest);
    }

    @GetMapping("/targets")
    @RequirePermission("target:view")
    public ApiPage<DemandTargetService.TargetView> targets(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID periodId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return planning.listTargets(jwt, periodId, page, size);
    }

    @PostMapping("/targets")
    @RequirePermission("target:manage")
    public DemandTargetService.TargetView createTarget(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DemandTargetService.TargetRequest request,
            HttpServletRequest servletRequest) {
        return planning.createTarget(jwt, request, servletRequest);
    }
}
