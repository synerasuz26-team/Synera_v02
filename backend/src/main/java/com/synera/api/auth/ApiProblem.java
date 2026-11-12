package com.synera.api.auth;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ApiProblem extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public ApiProblem(String code, String message, HttpStatus status) {
        this(code, message, status, null);
    }

    public ApiProblem(String code, String message, HttpStatus status, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
    }
}
