package com.synera.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class LoginAttemptService {
    private final JdbcTemplate jdbc;
    private final int maxFailures;
    private final int lockMinutes;

    public LoginAttemptService(
            JdbcTemplate jdbc,
            @Value("${app.auth.login-max-failures:5}") int maxFailures,
            @Value("${app.auth.login-lock-minutes:15}") int lockMinutes) {
        this.jdbc = jdbc;
        this.maxFailures = maxFailures;
        this.lockMinutes = lockMinutes;
    }

    public boolean isLocked(String email) {
        Boolean locked = jdbc.queryForObject("""
                SELECT COALESCE(
                    (SELECT locked_until > CURRENT_TIMESTAMP FROM auth_login_attempt WHERE email = ?),
                    FALSE)
                """, Boolean.class, normalize(email));
        return Boolean.TRUE.equals(locked);
    }

    @Transactional
    public void recordFailure(String email) {
        jdbc.update("""
                INSERT INTO auth_login_attempt (email, failed_attempts, locked_until)
                VALUES (?, 1, NULL)
                ON CONFLICT (email) DO UPDATE SET
                    failed_attempts = CASE
                        WHEN auth_login_attempt.locked_until IS NOT NULL
                             AND auth_login_attempt.locked_until <= CURRENT_TIMESTAMP THEN 1
                        ELSE auth_login_attempt.failed_attempts + 1
                    END,
                    locked_until = CASE
                        WHEN CASE
                            WHEN auth_login_attempt.locked_until IS NOT NULL
                                 AND auth_login_attempt.locked_until <= CURRENT_TIMESTAMP THEN 1
                            ELSE auth_login_attempt.failed_attempts + 1
                        END >= ?
                        THEN CURRENT_TIMESTAMP + (? * INTERVAL '1 minute')
                        ELSE NULL
                    END,
                    updated_at = CURRENT_TIMESTAMP
                """, normalize(email), maxFailures, lockMinutes);
    }

    @Transactional
    public void reset(String email) {
        jdbc.update("DELETE FROM auth_login_attempt WHERE email = ?", normalize(email));
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
