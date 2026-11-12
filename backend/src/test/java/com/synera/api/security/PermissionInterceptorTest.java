package com.synera.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PermissionInterceptorTest {
    private final PermissionInterceptor interceptor = new PermissionInterceptor(new PermissionAuthorization());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void protectsTheRoleAssignmentEndpointWithItsMatrixPermission() throws Exception {
        RoleAssignmentController controller = new RoleAssignmentController(mock(RoleAssignmentService.class));
        Method method = RoleAssignmentController.class.getMethod(
                "assignRole", UUID.class, RoleAssignmentController.RoleAssignmentRequest.class,
                org.springframework.security.oauth2.jwt.Jwt.class, HttpServletRequest.class);
        HandlerMethod handler = new HandlerMethod(controller, method);

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", "token",
                        java.util.List.of(new SimpleGrantedAuthority("PERM_role:assign"))));
        assertTrue(interceptor.preHandle(mock(HttpServletRequest.class),
                new org.springframework.mock.web.MockHttpServletResponse(), handler));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", "token",
                        java.util.List.of(new SimpleGrantedAuthority("PERM_order_si:view"))));
        assertThrows(AccessDeniedException.class, () -> interceptor.preHandle(
                mock(HttpServletRequest.class), new org.springframework.mock.web.MockHttpServletResponse(), handler));
    }

    @Test
    void allowsAnyOneOfTheDeclaredPermissions() throws Exception {
        WorkflowController controller = new WorkflowController();
        Method method = WorkflowController.class.getMethod("list");
        HandlerMethod handler = new HandlerMethod(controller, method);

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", "token",
                        java.util.List.of(new SimpleGrantedAuthority("PERM_price_list:approve"))));
        assertTrue(interceptor.preHandle(mock(HttpServletRequest.class),
                new org.springframework.mock.web.MockHttpServletResponse(), handler));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", "token",
                        java.util.List.of(new SimpleGrantedAuthority("PERM_product:view"))));
        assertThrows(AccessDeniedException.class, () -> interceptor.preHandle(
                mock(HttpServletRequest.class), new org.springframework.mock.web.MockHttpServletResponse(), handler));
    }

    static class WorkflowController {
        @RequireAnyPermission({"price_list:manage", "price_list:approve"})
        public void list() {
        }
    }
}
