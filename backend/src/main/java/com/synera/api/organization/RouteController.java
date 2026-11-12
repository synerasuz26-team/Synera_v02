package com.synera.api.organization;

import com.synera.api.catalog.ApiPage;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/routes")
@SecurityRequirement(name = "bearerAuth")
public class RouteController {
    private final RouteService routes;
    private final BusinessAccessService access;

    public RouteController(RouteService routes, BusinessAccessService access) {
        this.routes = routes;
        this.access = access;
    }

    @GetMapping
    @RequirePermission("route:view")
    public ApiPage<RouteService.RouteSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 120) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return routes.list(jwt, q, page, size);
    }

    @PostMapping
    @RequirePermission("route:manage")
    public RouteService.RouteSummary create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RouteService.RouteRequest request,
            HttpServletRequest servletRequest) {
        return routes.create(jwt, request, servletRequest);
    }

    @PutMapping("/{routeId}")
    @RequirePermission("route:manage")
    public RouteService.RouteSummary update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID routeId,
            @Valid @RequestBody RouteService.RouteUpdateRequest request,
            HttpServletRequest servletRequest) {
        return routes.update(jwt, routeId, request, servletRequest);
    }

    @PostMapping("/{routeId}/sales")
    @RequirePermission("route:assign")
    public void assignSales(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID routeId,
            @Valid @RequestBody RouteService.SalesAssignmentRequest request,
            HttpServletRequest servletRequest) {
        routes.assignSales(jwt, routeId, request, servletRequest);
    }

    @GetMapping("/{routeId}/outlets")
    @RequirePermission("route:view")
    public List<RouteService.RouteOutlet> outlets(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID routeId) {
        return routes.outlets(jwt, routeId);
    }

    @PostMapping("/{routeId}/outlets")
    @RequirePermission("route:assign")
    public void assignOutlet(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID routeId,
            @Valid @RequestBody RouteService.OutletAssignmentRequest request,
            HttpServletRequest servletRequest) {
        routes.assignOutlet(jwt, routeId, request, servletRequest);
    }

    @PutMapping("/{routeId}/outlets/{outletId}/location")
    @RequirePermission("route:manage")
    public RouteService.RouteOutlet setOutletLocation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID routeId,
            @PathVariable UUID outletId,
            @Valid @RequestBody RouteService.OutletLocationRequest request,
            HttpServletRequest servletRequest) {
        return routes.setOutletLocation(jwt, routeId, outletId, request, servletRequest);
    }

    @GetMapping("/{routeId}/mcp")
    @RequirePermission("route:view")
    public List<RouteService.McpEntry> mcp(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID routeId,
            @RequestParam(required = false) LocalDate date) {
        return routes.mcp(jwt, routeId, date == null ? access.businessToday() : date);
    }

    @PutMapping("/{routeId}/mcp")
    @RequirePermission("route:manage")
    public List<RouteService.McpEntry> replaceMcp(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID routeId,
            @Valid @RequestBody RouteService.McpUpdateRequest request,
            HttpServletRequest servletRequest) {
        return routes.replaceMcp(jwt, routeId, request, servletRequest);
    }
}
