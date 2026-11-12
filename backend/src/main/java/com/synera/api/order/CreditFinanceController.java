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
public class CreditFinanceController {
    private final CreditFinanceService finance;

    public CreditFinanceController(CreditFinanceService finance) {
        this.finance = finance;
    }

    @GetMapping("/credit-limits")
    @RequirePermission("credit_limit:view")
    public ApiPage<CreditFinanceService.CreditLimitView> creditLimits(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            HttpServletRequest request) {
        return finance.listCreditLimits(jwt, nppId, page, size, request);
    }

    @PostMapping("/credit-limits")
    @RequirePermission("credit_limit:manage")
    public CreditFinanceService.CreditLimitView proposeCreditLimit(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreditFinanceService.CreateCreditLimitRequest request,
            HttpServletRequest servletRequest) {
        return finance.proposeCreditLimit(jwt, request, servletRequest);
    }

    @PostMapping("/credit-limits/{limitId}/submit")
    @RequirePermission("credit_limit:manage")
    public CreditFinanceService.CreditLimitView submitCreditLimit(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID limitId,
            @Valid @RequestBody CreditFinanceService.CreditLimitActionRequest request,
            HttpServletRequest servletRequest) {
        return finance.submitCreditLimit(jwt, limitId, request, servletRequest);
    }

    @PostMapping("/credit-limits/{limitId}/decision")
    @RequirePermission("credit_limit:approve")
    public CreditFinanceService.CreditLimitView decideCreditLimit(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID limitId,
            @Valid @RequestBody CreditFinanceService.CreditLimitDecisionRequest request,
            HttpServletRequest servletRequest) {
        return finance.decideCreditLimit(jwt, limitId, request, servletRequest);
    }

    @GetMapping("/invoices")
    @RequirePermission("debt:view")
    public ApiPage<CreditFinanceService.InvoiceView> invoices(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            HttpServletRequest request) {
        return finance.listInvoices(jwt, nppId, page, size, request);
    }

    @GetMapping("/payments")
    @RequirePermission("debt:view")
    public ApiPage<CreditFinanceService.PaymentView> payments(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            HttpServletRequest request) {
        return finance.listPayments(jwt, nppId, page, size, request);
    }

    @GetMapping("/deductions")
    @RequirePermission("debt:view")
    public ApiPage<CreditFinanceService.DeductionView> deductions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            HttpServletRequest request) {
        return finance.listDeductions(jwt, nppId, page, size, request);
    }

    @PostMapping("/payments")
    @RequirePermission("payment:declare")
    public CreditFinanceService.PaymentView declarePayment(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreditFinanceService.DeclarePaymentRequest request,
            HttpServletRequest servletRequest) {
        return finance.declarePayment(jwt, request, servletRequest);
    }

    @PostMapping("/payments/{paymentId}/record")
    @RequirePermission("payment:record")
    public CreditFinanceService.PaymentView recordPayment(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId,
            @Valid @RequestBody CreditFinanceService.RecordPaymentRequest request,
            HttpServletRequest servletRequest) {
        return finance.recordPayment(jwt, paymentId, request, servletRequest);
    }

    @PostMapping("/deductions")
    @RequirePermission("payment:record")
    public CreditFinanceService.DeductionView recordDeduction(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreditFinanceService.DeductionRequest request,
            HttpServletRequest servletRequest) {
        return finance.recordDeduction(jwt, request, servletRequest);
    }
}
