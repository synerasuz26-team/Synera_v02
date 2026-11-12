package com.synera.api.catalog;

import com.synera.api.security.RequirePermission;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/prices")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class EffectivePricingController {
    private final EffectivePricingService pricing;

    public EffectivePricingController(EffectivePricingService pricing) {
        this.pricing = pricing;
    }

    @GetMapping("/sell-in")
    @RequirePermission("price_sellin:view")
    public EffectivePricingService.EffectivePrice sellIn(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @NotNull UUID nppId,
            @RequestParam(required = false) UUID outletId,
            @RequestParam @NotNull UUID productId,
            @RequestParam @NotBlank @Size(max = 32) String unitCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at,
            HttpServletRequest servletRequest) {
        return pricing.sellIn(jwt, nppId, outletId, productId, unitCode, at, servletRequest);
    }

    @GetMapping("/sell-out")
    @RequirePermission("price_sellout:view")
    public EffectivePricingService.EffectivePrice sellOut(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @NotNull UUID nppId,
            @RequestParam @NotNull UUID outletId,
            @RequestParam @NotNull UUID productId,
            @RequestParam @NotBlank @Size(max = 32) String unitCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
        return pricing.sellOut(jwt, nppId, outletId, productId, unitCode, at);
    }

    @PostMapping("/calculate")
    @RequirePermission("price_sellout:view")
    public EffectivePricingService.CalculationResult calculate(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody EffectivePricingService.CalculationRequest request) {
        return pricing.calculate(jwt, request);
    }
}
