package com.synera.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PermissionInterceptor implements HandlerInterceptor {
    private final PermissionAuthorization authorization;

    public PermissionInterceptor(PermissionAuthorization authorization) {
        this.authorization = authorization;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequirePermission required = AnnotatedElementUtils.findMergedAnnotation(
                method.getMethod(), RequirePermission.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(
                    method.getBeanType(), RequirePermission.class);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        RequireAnyPermission requiredAny = AnnotatedElementUtils.findMergedAnnotation(
                method.getMethod(), RequireAnyPermission.class);
        if (requiredAny == null) {
            requiredAny = AnnotatedElementUtils.findMergedAnnotation(
                    method.getBeanType(), RequireAnyPermission.class);
        }
        boolean permitted = required != null && authorization.hasPermission(authentication, required.value());
        if (required == null && requiredAny == null) {
            return true;
        }
        if (!permitted && requiredAny != null) {
            permitted = java.util.Arrays.stream(requiredAny.value())
                    .anyMatch(permission -> authorization.hasPermission(authentication, permission));
        }
        if (!permitted) {
            throw new AccessDeniedException("Bạn không có quyền thực hiện thao tác này.");
        }
        return true;
    }
}
