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
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class InventorySelloutController {
    private final InventorySelloutService data;

    public InventorySelloutController(InventorySelloutService data) {
        this.data = data;
    }

    @GetMapping("/inventory")
    @RequirePermission("inventory:view")
    public ApiPage<InventorySelloutService.InventoryView> inventory(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) String sku,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return data.listInventory(jwt, nppId, sku, page, size);
    }

    @PostMapping("/inventory")
    @RequirePermission("inventory:update")
    public InventorySelloutService.InventoryView updateInventory(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody InventorySelloutService.InventoryRequest request,
            HttpServletRequest servletRequest) {
        return data.updateInventory(jwt, request, servletRequest);
    }

    @GetMapping("/sellout-records")
    @RequirePermission("sellout_data:view")
    public ApiPage<InventorySelloutService.SelloutView> selloutRecords(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return data.listSellout(jwt, nppId, outletId, sku, from, to, page, size);
    }

    @PostMapping("/sellout-records")
    @RequirePermission("sellout_data:submit")
    public InventorySelloutService.SelloutView createSellout(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody InventorySelloutService.SelloutRequest request,
            HttpServletRequest servletRequest) {
        return data.createSellout(jwt, request, servletRequest);
    }

    @PostMapping(value = "/imports/inventory/preview", consumes = "multipart/form-data")
    @RequirePermission("inventory:update")
    public InventorySelloutService.ImportBatchView previewInventoryImport(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID nppId,
            @RequestParam("file") MultipartFile file, HttpServletRequest servletRequest) {
        return data.previewInventoryImport(jwt, nppId, file, servletRequest);
    }

    @PostMapping(value = "/imports/sellout/preview", consumes = "multipart/form-data")
    @RequirePermission("sellout_data:submit")
    public InventorySelloutService.ImportBatchView previewSelloutImport(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID nppId,
            @RequestParam("file") MultipartFile file, HttpServletRequest servletRequest) {
        return data.previewSelloutImport(jwt, nppId, file, servletRequest);
    }

    @GetMapping("/imports/{batchId}")
    @RequireAnyPermission({"inventory:view", "sellout_data:view"})
    public InventorySelloutService.ImportBatchView getImport(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID batchId) {
        return data.getImportBatch(jwt, batchId);
    }

    @GetMapping("/imports/{batchId}/rows")
    @RequireAnyPermission({"inventory:view", "sellout_data:view"})
    public ApiPage<InventorySelloutService.ImportRowView> importRows(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID batchId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return data.listImportRows(jwt, batchId, page, size);
    }

    @PostMapping("/imports/{batchId}/commit")
    @RequireAnyPermission({"inventory:update", "sellout_data:submit"})
    public InventorySelloutService.ImportBatchView commitImport(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID batchId,
            HttpServletRequest servletRequest) {
        return data.commitImport(jwt, batchId, servletRequest);
    }
}
