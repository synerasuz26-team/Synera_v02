package com.synera.api.order;

import com.synera.api.catalog.ApiPage;
import com.synera.api.security.RequirePermission;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class SellInOrderController {
    private final SellInOrderService orders;

    public SellInOrderController(SellInOrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/orders/sell-in")
    @RequirePermission("order_si:view")
    public ApiPage<SellInOrderService.OrderSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 32) String status,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return orders.list(jwt, status, nppId, page, size);
    }

    @GetMapping("/orders/sell-in/{orderId}")
    @RequirePermission("order_si:view")
    public SellInOrderService.OrderDetail get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return orders.get(jwt, orderId);
    }

    @PostMapping("/orders/sell-in")
    @RequirePermission("order_si:create")
    public SellInOrderService.OrderDetail create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody SellInOrderService.CreateRequest request,
            HttpServletRequest servletRequest) {
        return orders.create(jwt, idempotencyKey, request, servletRequest);
    }

    @PostMapping("/orders/sell-in/{orderId}/repeat")
    @RequirePermission("order_si:create")
    public SellInOrderService.OrderDetail repeat(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody SellInOrderService.RepeatRequest request,
            HttpServletRequest servletRequest) {
        return orders.repeat(jwt, orderId, idempotencyKey, request, servletRequest);
    }

    @PostMapping("/orders/sell-in/{orderId}/submit")
    @RequirePermission("order_si:create")
    public SellInOrderService.OrderDetail submit(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @Valid @RequestBody SellInOrderService.SubmitRequest request,
            HttpServletRequest servletRequest) {
        return orders.submit(jwt, orderId, request, servletRequest);
    }

    @PutMapping("/orders/sell-in/{orderId}")
    @RequirePermission("order_si:cancel_draft")
    public SellInOrderService.OrderDetail updateDraft(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @Valid @RequestBody SellInOrderService.DraftUpdateRequest request,
            HttpServletRequest servletRequest) {
        return orders.updateDraft(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/orders/sell-in/{orderId}/review")
    @RequirePermission("order_si:review")
    public SellInOrderService.OrderDetail review(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @Valid @RequestBody SellInOrderService.ReviewRequest request,
            HttpServletRequest servletRequest) {
        return orders.review(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/orders/sell-in/{orderId}/approve")
    @RequirePermission("order_si:approve")
    public SellInOrderService.OrderDetail approve(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @Valid @RequestBody SellInOrderService.DecisionRequest request,
            HttpServletRequest servletRequest) {
        return orders.approve(jwt, orderId, request, false, servletRequest);
    }

    @PostMapping("/orders/sell-in/{orderId}/approve-over-limit")
    @RequirePermission("order_si:approve_over_limit")
    public SellInOrderService.OrderDetail approveOverLimit(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @Valid @RequestBody SellInOrderService.DecisionRequest request,
            HttpServletRequest servletRequest) {
        return orders.approve(jwt, orderId, request, true, servletRequest);
    }

    @PostMapping("/orders/sell-in/{orderId}/reject")
    @RequirePermission("order_si:reject")
    public SellInOrderService.OrderDetail reject(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @Valid @RequestBody SellInOrderService.RejectRequest request,
            HttpServletRequest servletRequest) {
        return orders.reject(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/orders/sell-in/{orderId}/cancel")
    @RequirePermission("order_si:cancel_draft")
    public SellInOrderService.OrderDetail cancel(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @Valid @RequestBody SellInOrderService.CancelRequest request,
            HttpServletRequest servletRequest) {
        return orders.cancel(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/orders/sell-in/{orderId}/shipment")
    @RequirePermission("order_si:ship")
    public SellInOrderService.OrderDetail ship(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @Valid @RequestBody SellInOrderService.ShipmentRequest request,
            HttpServletRequest servletRequest) {
        return orders.ship(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/orders/sell-in/{orderId}/receipt")
    @RequirePermission("order_si:receive_confirm")
    public SellInOrderService.OrderDetail receive(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @Valid @RequestBody SellInOrderService.ReceiptRequest request,
            HttpServletRequest servletRequest) {
        return orders.receive(jwt, orderId, request, servletRequest);
    }
}
