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
public class SellOutOrderController {
    private final SellOutOrderService orders;

    public SellOutOrderController(SellOutOrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/orders/sell-out")
    @RequirePermission("order_so:view")
    public ApiPage<SellOutOrderService.OrderSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 32) String status,
            @RequestParam(required = false) UUID outletId,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return orders.list(jwt, status, outletId, nppId, page, size);
    }

    @GetMapping("/orders/sell-out/{orderId}")
    @RequirePermission("order_so:view")
    public SellOutOrderService.OrderDetail get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return orders.get(jwt, orderId);
    }

    @PostMapping("/orders/sell-out")
    @RequirePermission("order_so:create")
    public SellOutOrderService.OrderDetail create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody SellOutOrderService.OrderCreateRequest request,
            HttpServletRequest servletRequest) {
        return orders.create(jwt, idempotencyKey, request, servletRequest);
    }

    @PutMapping("/orders/sell-out/{orderId}")
    @RequirePermission("order_so:update_draft")
    public SellOutOrderService.OrderDetail updateDraft(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @Valid @RequestBody SellOutOrderService.DraftUpdateRequest request,
            HttpServletRequest servletRequest) {
        return orders.updateDraft(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/orders/sell-out/{orderId}/submit")
    @RequirePermission("order_so:create")
    public SellOutOrderService.OrderDetail submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @Valid @RequestBody SellOutOrderService.SubmitOrderRequest request,
            HttpServletRequest servletRequest) {
        return orders.submitDraft(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/orders/sell-out/{orderId}/cancel")
    @RequirePermission("order_so:update_draft")
    public SellOutOrderService.OrderDetail cancelBeforeConfirmation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @Valid @RequestBody SellOutOrderService.CancelOrderRequest request,
            HttpServletRequest servletRequest) {
        return orders.cancelBeforeConfirmation(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/orders/sell-out/{orderId}/cancel-confirmed")
    @RequirePermission("order_so:cancel_confirmed")
    public SellOutOrderService.OrderDetail cancelConfirmed(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @Valid @RequestBody SellOutOrderService.CancelOrderRequest request,
            HttpServletRequest servletRequest) {
        return orders.cancelConfirmed(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/orders/sell-out/{orderId}/fulfillment")
    @RequirePermission("order_so:fulfill_confirm")
    public SellOutOrderService.OrderDetail confirmFulfillment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @Valid @RequestBody SellOutOrderService.FulfillmentRequest request,
            HttpServletRequest servletRequest) {
        return orders.confirmFulfillment(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/orders/sell-out/{orderId}/shipment")
    @RequirePermission("order_so:ship_update")
    public SellOutOrderService.OrderDetail ship(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @Valid @RequestBody SellOutOrderService.ShipmentRequest request,
            HttpServletRequest servletRequest) {
        return orders.ship(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/orders/sell-out/{orderId}/receipt")
    @RequirePermission("order_so:receive_confirm")
    public SellOutOrderService.OrderDetail receive(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @Valid @RequestBody SellOutOrderService.ReceiptRequest request,
            HttpServletRequest servletRequest) {
        return orders.receive(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/orders/sell-out/{orderId}/returns")
    @RequirePermission("return:create")
    public SellOutOrderService.ReturnSummary createReturn(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @Valid @RequestBody SellOutOrderService.ReturnCreateRequest request,
            HttpServletRequest servletRequest) {
        return orders.createReturn(jwt, orderId, request, servletRequest);
    }

    @PostMapping("/returns/{returnId}/decision")
    @RequirePermission("return:approve")
    public SellOutOrderService.ReturnSummary decideReturn(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID returnId,
            @Valid @RequestBody SellOutOrderService.ReturnDecisionRequest request,
            HttpServletRequest servletRequest) {
        return orders.decideReturn(jwt, returnId, request, servletRequest);
    }
}
