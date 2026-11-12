package com.synera.api.auth;

public class RegistrationDisabledException extends RuntimeException {
    public RegistrationDisabledException() {
        super("Đăng ký công khai đã tắt. Vui lòng liên hệ quản trị viên để nhận tài khoản.");
    }
}
