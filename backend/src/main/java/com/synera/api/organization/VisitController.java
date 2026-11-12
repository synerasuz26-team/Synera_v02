package com.synera.api.organization;

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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class VisitController {
    private final VisitService visits;
    private final BusinessAccessService access;

    public VisitController(VisitService visits, BusinessAccessService access) {
        this.visits = visits;
        this.access = access;
    }

    @GetMapping("/visits")
    @RequirePermission("visit:view")
    public ApiPage<VisitService.VisitSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID routeId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return visits.list(jwt, date, status, routeId, page, size);
    }

    @PostMapping("/visits/{visitId}/check-in")
    @RequirePermission("visit:checkin")
    public VisitService.VisitActionResult checkIn(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID visitId,
            @Valid @RequestBody VisitService.GpsActionRequest request,
            HttpServletRequest servletRequest) {
        return visits.checkIn(jwt, visitId, request, servletRequest);
    }

    @PostMapping("/visits/{visitId}/check-out")
    @RequirePermission("visit:checkin")
    public VisitService.VisitActionResult checkOut(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID visitId,
            @Valid @RequestBody VisitService.GpsActionRequest request,
            HttpServletRequest servletRequest) {
        return visits.checkOut(jwt, visitId, request, servletRequest);
    }

    @GetMapping("/visits/{visitId}/checklist-results")
    @RequirePermission("visit:view")
    public List<VisitService.ChecklistResult> checklistResults(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID visitId) {
        return visits.checklistResults(jwt, visitId);
    }

    @PostMapping("/visits/{visitId}/checklist-results")
    @RequirePermission("visit:record")
    public VisitService.ChecklistResult saveChecklist(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID visitId,
            @Valid @RequestBody VisitService.ChecklistRequest request,
            HttpServletRequest servletRequest) {
        return visits.saveChecklist(jwt, visitId, request, servletRequest);
    }

    @GetMapping("/visit-gps-exceptions")
    @RequirePermission("visit:gps_override")
    public ApiPage<VisitService.GpsExceptionSummary> pendingGpsExceptions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return visits.pendingGpsExceptions(jwt, page, size);
    }

    @PostMapping("/visit-gps-exceptions/{exceptionId}/approve")
    @RequirePermission("visit:gps_override")
    public VisitService.VisitActionResult approveGpsException(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID exceptionId,
            @Valid @RequestBody VisitService.GpsExceptionReviewRequest request,
            HttpServletRequest servletRequest) {
        return visits.reviewGpsException(jwt, exceptionId, request, true, servletRequest);
    }

    @PostMapping("/visit-gps-exceptions/{exceptionId}/reject")
    @RequirePermission("visit:gps_override")
    public VisitService.VisitActionResult rejectGpsException(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID exceptionId,
            @Valid @RequestBody VisitService.GpsExceptionReviewRequest request,
            HttpServletRequest servletRequest) {
        return visits.reviewGpsException(jwt, exceptionId, request, false, servletRequest);
    }

    @PostMapping("/visits/{visitId}/reassign")
    @RequirePermission("visit:reassign")
    public VisitService.ReassignmentResult reassign(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID visitId,
            @Valid @RequestBody VisitService.ReassignVisitRequest request,
            HttpServletRequest servletRequest) {
        return visits.reassign(jwt, visitId, request, servletRequest);
    }

    @GetMapping("/daily-report")
    @RequirePermission("daily_report:view")
    public VisitService.DailyReport dailyReport(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) LocalDate date) {
        return visits.dailyReport(jwt, date == null ? access.businessToday() : date);
    }
}
