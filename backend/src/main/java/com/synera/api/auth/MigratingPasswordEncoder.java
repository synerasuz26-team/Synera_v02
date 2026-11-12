package com.synera.api.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

import java.nio.charset.StandardCharsets;

public class MigratingPasswordEncoder implements PasswordEncoder {
    private static final String BCRYPT_PREFIX = "{bcrypt}";

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final PasswordEncoder legacyPbkdf2 = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    @Override
    public String encode(CharSequence rawPassword) {
        if (!canEncode(rawPassword)) {
            throw new IllegalArgumentException("Mật khẩu mới không được vượt quá 72 byte UTF-8.");
        }
        return BCRYPT_PREFIX + bcrypt.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (encodedPassword == null) {
            return false;
        }
        if (encodedPassword.startsWith(BCRYPT_PREFIX)) {
            if (!canEncode(rawPassword)) {
                return false;
            }
            return bcrypt.matches(rawPassword, encodedPassword.substring(BCRYPT_PREFIX.length()));
        }
        return legacyPbkdf2.matches(rawPassword, encodedPassword);
    }

    public boolean canEncode(CharSequence rawPassword) {
        return rawPassword.toString().getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return encodedPassword == null
                || !encodedPassword.startsWith(BCRYPT_PREFIX)
                || bcrypt.upgradeEncoding(encodedPassword.substring(BCRYPT_PREFIX.length()));
    }
}
