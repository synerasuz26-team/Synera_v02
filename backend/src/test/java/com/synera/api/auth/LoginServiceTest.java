package com.synera.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginServiceTest {
    private final AuthIdentityRepository identities = mock(AuthIdentityRepository.class);
    private final MigratingPasswordEncoder passwordEncoder = mock(MigratingPasswordEncoder.class);
    private final LoginAttemptService attempts = mock(LoginAttemptService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LoginService loginService =
            new LoginService(identities, passwordEncoder, attempts, refreshTokens, jdbc);

    @Test
    void lockedAccountsAreRejectedBeforeCredentialLookup() {
        when(attempts.isLocked("user@example.com")).thenReturn(true);

        assertThrows(BadCredentialsException.class,
                () -> loginService.login(" User@Example.com ", "wrong-password", null));

        verify(identities, never()).findByEmail("user@example.com");
        verify(attempts, never()).recordFailure("user@example.com");
    }

    @Test
    void invalidCredentialsIncrementThePersistentRateLimit() {
        AuthIdentity identity = new AuthIdentity(
                UUID.randomUUID(), "Example User", "user@example.com", "{bcrypt}invalid",
                "ACTIVE", UUID.randomUUID(), "NPP", "NPP", "Distributor", "ACTIVE",
                List.of(new AuthIdentity.Role("NPP_OWNER", "Owner", 100)),
                Map.of(), List.of(), List.of());
        when(attempts.isLocked("user@example.com")).thenReturn(false);
        when(identities.findByEmail("user@example.com")).thenReturn(Optional.of(identity));
        when(passwordEncoder.matches("wrong-password", identity.passwordHash())).thenReturn(false);

        assertThrows(BadCredentialsException.class,
                () -> loginService.login("user@example.com", "wrong-password", null));

        verify(attempts).recordFailure("user@example.com");
        verify(refreshTokens, never()).createSession(identity, null);
    }
}
