package com.synera.api.auth;

public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() {
        super("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
    }
}
