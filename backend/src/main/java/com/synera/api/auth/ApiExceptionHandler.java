package com.synera.api.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException.class)
    public ResponseEntity<ApiError> concurrentWrite() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("CONCURRENT_WRITE", "Dữ liệu đang được cập nhật đồng thời. Hãy tải lại và thử lại thao tác; lô chưa hoàn tất sẽ được hoàn tác."));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ApiError> databaseFailure(org.springframework.dao.DataAccessException exception) {
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class).error("database_failure type={}",exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("DATABASE_ERROR", "Không thể xử lý dữ liệu lúc này. Vui lòng thử lại hoặc liên hệ quản trị viên."));
    }
    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<ApiError> apiProblem(ApiProblem problem) {
        return ResponseEntity.status(problem.status())
                .body(new ApiError(problem.code(), problem.getMessage(), problem.details()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng."));
    }

    @ExceptionHandler({InvalidRefreshTokenException.class, RefreshTokenReuseException.class})
    public ResponseEntity<ApiError> invalidRefreshToken(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("INVALID_REFRESH_TOKEN", exception.getMessage()));
    }

    @ExceptionHandler(RegistrationDisabledException.class)
    public ResponseEntity<ApiError> registrationDisabled(RegistrationDisabledException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("REGISTRATION_DISABLED", exception.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("FORBIDDEN", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidRequest(MethodArgumentNotValidException exception) {
        var fieldErrors = new java.util.LinkedHashMap<String, Object>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "Thông tin gửi lên không hợp lệ.", fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> invalidParameters(ConstraintViolationException exception) {
        var violations = new java.util.LinkedHashMap<String, Object>();
        exception.getConstraintViolations().forEach(violation ->
                violations.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage()));
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "Tham số gửi lên không hợp lệ.", violations));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> invalidParameterType(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
                "INVALID_REQUEST", "Kiểu tham số không hợp lệ.",
                java.util.Map.of("field", exception.getName())));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
                "INVALID_REQUEST", "Thiếu tham số bắt buộc.",
                java.util.Map.of("field", exception.getParameterName())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadableBody() {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "Nội dung JSON không hợp lệ."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalidArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> dataConflict(DataIntegrityViolationException exception) {
        if (hasConstraint(exception, "period_closed_data_lock")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiError("PERIOD_CLOSED_LOCK",
                            "Kỳ đã chốt; không thể thay đổi dữ liệu đơn nhập thuộc kỳ."));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("DATA_CONFLICT", "Dữ liệu đã tồn tại."));
    }

    private boolean hasConstraint(Throwable exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(constraintName)) {
                return true;
            }
        }
        return false;
    }
}
