package com.synera.api.order;

import com.synera.api.catalog.ApiPage;
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
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class ReconciliationController {
    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping("/reconciliations")
    @RequirePermission("reconciliation:view")
    public ApiPage<ReconciliationService.ReconciliationView> reconciliations(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID periodId,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return reconciliation.listReconciliations(jwt, periodId, nppId, page, size);
    }

    @GetMapping("/periods")
    @RequirePermission("reconciliation:view")
    public ApiPage<ReconciliationService.PeriodView> periods(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return reconciliation.listPeriods(jwt, page, size);
    }

    @GetMapping("/reconciliations/{reconciliationId}/exceptions")
    @RequirePermission("reconciliation:view")
    public ApiPage<ReconciliationService.ExceptionView> exceptions(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID reconciliationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return reconciliation.listExceptions(jwt, reconciliationId, status, page, size);
    }

    @PostMapping("/reconciliations/{reconciliationId}/confirm")
    @RequirePermission("reconciliation:confirm")
    public ReconciliationService.ReconciliationView confirm(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID reconciliationId,
            @Valid @RequestBody ReconciliationService.ActionRequest request,
            HttpServletRequest servletRequest) {
        return reconciliation.confirm(jwt, reconciliationId, request, servletRequest);
    }

    @PostMapping("/reconciliation-exceptions/{exceptionId}/resolve")
    @RequirePermission("reconciliation:resolve_exception")
    public ReconciliationService.ExceptionView resolveException(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID exceptionId,
            @Valid @RequestBody ReconciliationService.ActionRequest request,
            HttpServletRequest servletRequest) {
        return reconciliation.resolveException(jwt, exceptionId, request, servletRequest);
    }

    @PostMapping("/periods")
    @RequirePermission("period:close")
    public ReconciliationService.PeriodView createPeriod(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReconciliationService.CreatePeriodRequest request,
            HttpServletRequest servletRequest) {
        return reconciliation.createPeriod(jwt, request, servletRequest);
    }

    @PostMapping("/periods/{periodId}/close")
    @RequirePermission("period:close")
    public ReconciliationService.PeriodView closePeriod(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID periodId,
            @Valid @RequestBody ReconciliationService.ActionRequest request,
            HttpServletRequest servletRequest) {
        return reconciliation.closePeriod(jwt, periodId, request, servletRequest);
    }

    @GetMapping("/reports/operations/reconciliation")
    @RequirePermission("report:ops")
    public ApiPage<ReconciliationService.ReportRow> operationsReport(
            @AuthenticationPrincipal Jwt jwt, @RequestParam UUID periodId,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return reconciliation.operationsReport(jwt, periodId, nppId, page, size);
    }

    @GetMapping("/reports/official/reconciliation")
    @RequirePermission("report:official")
    public ApiPage<ReconciliationService.ReportRow> officialReport(
            @AuthenticationPrincipal Jwt jwt, @RequestParam UUID periodId,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            HttpServletRequest servletRequest) {
        return reconciliation.officialReport(jwt, periodId, nppId, page, size, servletRequest);
    }

    @GetMapping("/reports/reconciliation/difference")
    @RequirePermission("report:ops")
    public ApiPage<ReconciliationService.ReportRow> differenceReport(
            @AuthenticationPrincipal Jwt jwt, @RequestParam UUID periodId,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return reconciliation.operationsReport(jwt, periodId, nppId, page, size);
    }

    @PostMapping("/period-adjustments")
    @RequirePermission("period:adjust_create")
    public ReconciliationService.AdjustmentView createAdjustment(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReconciliationService.AdjustmentRequest request,
            HttpServletRequest servletRequest) {
        return reconciliation.createAdjustment(jwt, request, servletRequest);
    }

    @GetMapping("/period-adjustments")
    @RequireAnyPermission({"period:adjust_create", "period:adjust_approve"})
    public ApiPage<ReconciliationService.AdjustmentView> adjustments(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID periodId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return reconciliation.listAdjustments(jwt, periodId, page, size);
    }

    @PostMapping("/period-adjustments/{adjustmentId}/approve")
    @RequirePermission("period:adjust_approve")
    public ReconciliationService.AdjustmentView approveAdjustment(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID adjustmentId,
            @Valid @RequestBody ReconciliationService.ActionRequest request,
            HttpServletRequest servletRequest) {
        return reconciliation.approveAdjustment(jwt, adjustmentId, request, servletRequest);
    }
}
