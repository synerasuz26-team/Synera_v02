package com.synera.api.organization;

import com.synera.api.auth.ApiProblem;
import com.synera.api.security.ResolvedScope;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class AttendanceService {
    private final JdbcTemplate jdbc;
    private final BusinessAccessService access;

    public AttendanceService(JdbcTemplate jdbc, BusinessAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @Transactional
    public Attendance clockIn(Jwt jwt) {
        BusinessAccessService.Actor actor = authorizedActor(jwt);
        LocalDate date = access.businessToday();
        jdbc.update("""
                INSERT INTO attendance (id, tenant_id, user_id, attendance_date, source)
                VALUES (?, ?, ?, ?, 'API')
                ON CONFLICT (tenant_id, user_id, attendance_date) DO NOTHING
                """, UUID.randomUUID(), actor.tenantId(), actor.userId(), date);
        Attendance attendance = lock(actor.tenantId(), actor.userId(), date);
        if (attendance.clockInAt() != null) {
            throw new ApiProblem("ATTENDANCE_ALREADY_STARTED", "Bạn đã chấm công vào ngày hôm nay.",
                    HttpStatus.CONFLICT);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                UPDATE attendance SET clock_in_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND user_id = ? AND attendance_date = ?
                """, now, actor.tenantId(), actor.userId(), date);
        return new Attendance(date, now, null);
    }

    @Transactional
    public Attendance clockOut(Jwt jwt) {
        BusinessAccessService.Actor actor = authorizedActor(jwt);
        LocalDate date = access.businessToday();
        Attendance attendance = lockIfPresent(actor.tenantId(), actor.userId(), date);
        if (attendance == null || attendance.clockInAt() == null) {
            throw new ApiProblem("ATTENDANCE_CLOCK_IN_REQUIRED", "Bạn cần chấm công vào trước.",
                    HttpStatus.CONFLICT);
        }
        if (attendance.clockOutAt() != null) {
            throw new ApiProblem("ATTENDANCE_ALREADY_ENDED", "Bạn đã chấm công ra ngày hôm nay.",
                    HttpStatus.CONFLICT);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                UPDATE attendance SET clock_out_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND user_id = ? AND attendance_date = ?
                """, now, actor.tenantId(), actor.userId(), date);
        return new Attendance(date, attendance.clockInAt(), now);
    }

    private BusinessAccessService.Actor authorizedActor(Jwt jwt) {
        BusinessAccessService.Actor actor = access.actor(jwt);
        access.requireNsx(actor);
        ResolvedScope scope = access.resolve(jwt, "attendance:record");
        if (!"O".equals(scope.scope()) || !actor.userId().equals(scope.ownerId())
                || !actor.tenantId().equals(scope.tenantId())) {
            throw new AccessDeniedException("Chỉ được chấm công cho chính tài khoản của bạn.");
        }
        return actor;
    }

    private Attendance lock(UUID tenantId, UUID userId, LocalDate date) {
        return jdbc.query("""
                SELECT clock_in_at, clock_out_at FROM attendance
                WHERE tenant_id = ? AND user_id = ? AND attendance_date = ?
                FOR UPDATE
                """, rs -> {
            if (!rs.next()) {
                throw new IllegalStateException("Không thể tạo bản ghi chấm công.");
            }
            return new Attendance(date, rs.getObject("clock_in_at", OffsetDateTime.class),
                    rs.getObject("clock_out_at", OffsetDateTime.class));
        }, tenantId, userId, date);
    }

    private Attendance lockIfPresent(UUID tenantId, UUID userId, LocalDate date) {
        return jdbc.query("""
                SELECT clock_in_at, clock_out_at FROM attendance
                WHERE tenant_id = ? AND user_id = ? AND attendance_date = ?
                FOR UPDATE
                """, rs -> rs.next()
                ? new Attendance(date, rs.getObject("clock_in_at", OffsetDateTime.class),
                        rs.getObject("clock_out_at", OffsetDateTime.class))
                : null, tenantId, userId, date);
    }

    public record Attendance(LocalDate date, OffsetDateTime clockInAt, OffsetDateTime clockOutAt) {
    }
}
