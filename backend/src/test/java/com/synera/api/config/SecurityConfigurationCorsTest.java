package com.synera.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationCorsTest {
    @Test
    void allowsIdempotentOrderRequestsFromConfiguredFrontend() {
        var source = new SecurityConfiguration().corsConfigurationSource("http://localhost:5173");
        var request = new MockHttpServletRequest("OPTIONS", "/api/v1/orders/sell-out");
        request.addHeader("Origin", "http://localhost:5173");
        var configuration = source.getCorsConfiguration(request);
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedHeaders()).contains("Authorization", "Idempotency-Key");
        assertThat(configuration.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(configuration.checkOrigin("https://unconfigured.example")).isNull();
    }
}
