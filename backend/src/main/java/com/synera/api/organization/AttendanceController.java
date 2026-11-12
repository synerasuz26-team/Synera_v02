package com.synera.api.organization;

import com.synera.api.security.RequirePermission;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance")
@SecurityRequirement(name = "bearerAuth")
public class AttendanceController {
    private final AttendanceService attendance;

    public AttendanceController(AttendanceService attendance) {
        this.attendance = attendance;
    }

    @PostMapping("/clock-in")
    @RequirePermission("attendance:record")
    public AttendanceService.Attendance clockIn(@AuthenticationPrincipal Jwt jwt) {
        return attendance.clockIn(jwt);
    }

    @PostMapping("/clock-out")
    @RequirePermission("attendance:record")
    public AttendanceService.Attendance clockOut(@AuthenticationPrincipal Jwt jwt) {
        return attendance.clockOut(jwt);
    }
}
