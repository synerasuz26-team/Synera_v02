package com.synera.api.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private AppUserRepository users;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @Test
    void registerNormalizesEmailAndStoresOnlyEncodedPassword() {
        when(users.existsByEmail("person@example.com")).thenReturn(false);
        when(passwordEncoder.encode("strong-password-value")).thenReturn("bcrypt-hash");
        when(users.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AppUser user = authService.register(new CreateAccountRequest(
                "  Example Person  ",
                "Person@Example.com ",
                "strong-password-value"));

        assertEquals("Example Person", user.getFullName());
        assertEquals("person@example.com", user.getEmail());
        assertEquals("bcrypt-hash", user.getPasswordHash());
        verify(users).saveAndFlush(user);
    }

    @Test
    void registerRejectsAnExistingEmailBeforeEncodingPassword() {
        when(users.existsByEmail("person@example.com")).thenReturn(true);

        assertThrows(EmailAlreadyRegisteredException.class, () -> authService.register(
                new CreateAccountRequest("Example Person", "person@example.com", "strong-password-value")));

        verify(passwordEncoder, never()).encode(any());
        verify(users, never()).saveAndFlush(any());
    }
}
