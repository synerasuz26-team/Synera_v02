package com.synera.api.catalog;

import com.synera.api.security.RequireAnyPermission;
import com.synera.api.security.RequirePermission;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/products")
@SecurityRequirement(name = "bearerAuth")
public class ProductController {
    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    @RequirePermission("product:view")
    public ApiPage<ProductService.ProductSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 80) String q,
            @RequestParam(required = false)
            @jakarta.validation.constraints.Pattern(regexp = "ACTIVE|INACTIVE|ARCHIVED") String status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return products.list(jwt, q, status, page, size);
    }

    @GetMapping("/{productId}")
    @RequirePermission("product:view")
    public ProductService.ProductDetail get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        return products.get(jwt, productId);
    }

    @PostMapping
    @RequirePermission("product:manage")
    public ProductService.ProductDetail create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ProductService.ProductRequest request,
            HttpServletRequest servletRequest) {
        return products.create(jwt, request, servletRequest);
    }

    @PutMapping("/{productId}")
    @RequirePermission("product:manage")
    public ProductService.ProductDetail update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID productId,
            @Valid @RequestBody ProductService.ProductUpdateRequest request,
            HttpServletRequest servletRequest) {
        return products.update(jwt, productId, request, servletRequest);
    }
}
