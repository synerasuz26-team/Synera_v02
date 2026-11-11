package com.synera.api.auth;

public class EmailAlreadyRegisteredException extends RuntimeException {
    public EmailAlreadyRegisteredException() {
        super("Email này đã được đăng ký.");
    }
}
