package com.synera.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigratingPasswordEncoderTest {
    private final MigratingPasswordEncoder encoder = new MigratingPasswordEncoder();

    @Test
    void writesBcryptAndVerifiesNewHashes() {
        String encoded = encoder.encode("strong-password");

        assertTrue(encoded.startsWith("{bcrypt}"));
        assertTrue(encoder.matches("strong-password", encoded));
        assertFalse(encoder.matches("another-password", encoded));
    }

    @Test
    void verifiesExistingPbkdf2HashesForGradualUpgrade() {
        String legacyHash = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()
                .encode("strong-password");

        assertTrue(encoder.matches("strong-password", legacyHash));
        assertTrue(encoder.upgradeEncoding(legacyHash));
    }

    @Test
    void rejectsNewBcryptPasswordsBeyondTheAlgorithmLimit() {
        assertTrue(encoder.canEncode("x".repeat(72)));
        assertFalse(encoder.canEncode("x".repeat(73)));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode("x".repeat(73)));
    }
}
