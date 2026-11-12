package com.synera.api.auth;

public class RefreshTokenReuseException extends RuntimeException {
    public RefreshTokenReuseException() {
        super("Phiên đăng nhập không còn hợp lệ. Vui lòng đăng nhập lại.");
    }
}
