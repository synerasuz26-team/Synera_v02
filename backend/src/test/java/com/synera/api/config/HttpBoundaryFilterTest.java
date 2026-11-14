package com.synera.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class HttpBoundaryFilterTest {
    MockEnvironment env(){return new MockEnvironment().withProperty("APP_ENV","production").withProperty("app.http.login-per-minute","2");}
    MockHttpServletRequest request(String path){var r=new MockHttpServletRequest("POST",path);r.setSecure(true);r.setRemoteAddr("127.0.0.1");return r;}
    @Test void blocksCleartextButAllowsInternalHealth()throws Exception{
        var filter=new HttpBoundaryFilter(env(),new HttpTraffic());var r=request("/api/v1/auth/login");r.setSecure(false);var s=new MockHttpServletResponse();
        filter.doFilter(r,s,(a,b)->{throw new AssertionError("Cleartext request reached app");});assertThat(s.getStatus()).isEqualTo(426);
        r=new MockHttpServletRequest("GET","/health");s=new MockHttpServletResponse();filter.doFilter(r,s,(a,b)->{});assertThat(s.getStatus()).isEqualTo(200);
    }
    @Test void blocksKnownAndUnknownLengthOversizedJson()throws Exception{
        for(boolean known:java.util.List.of(true,false)){
            var filter=new HttpBoundaryFilter(env().withProperty("app.http.max-json-bytes","16"),new HttpTraffic());
            var r=request("/api/v1/orders/sell-in");r.setContentType("application/json");r.setContent("{\"value\":\"01234567890123456789\"}".getBytes(StandardCharsets.UTF_8));
            jakarta.servlet.http.HttpServletRequest input=known?r:new jakarta.servlet.http.HttpServletRequestWrapper(r){public long getContentLengthLong(){return -1;}public int getContentLength(){return -1;}};
            var s=new MockHttpServletResponse();filter.doFilter(input,s,(a,b)->{throw new AssertionError("Large request reached app");});assertThat(s.getStatus()).isEqualTo(413);
        }
    }
    @Test void rateLimitsLoginAndDoesNotTrustSpoofedClientIpHeader()throws Exception{
        var filter=new HttpBoundaryFilter(env(),new HttpTraffic(),Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC));var calls=new AtomicInteger();
        for(int i=0;i<3;i++){var r=request("/api/v1/auth/login");r.addHeader("X-Forwarded-For","spoof-"+i);var s=new MockHttpServletResponse();filter.doFilter(r,s,(a,b)->calls.incrementAndGet());if(i==2){assertThat(s.getStatus()).isEqualTo(429);assertThat(s.getHeader("Retry-After")).isEqualTo("60");}}
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void preservesBodyAndGeneratesSafeCorrelationHeader()throws Exception{
        var traffic=new HttpTraffic();var filter=new HttpBoundaryFilter(env(),traffic);var r=request("/api/v1/orders/sell-in");r.setContentType("application/json");r.setContent("{\"quantity\":2}".getBytes(StandardCharsets.UTF_8));r.addHeader("X-Request-Id","bad\r\nvalue");var s=new MockHttpServletResponse();
        filter.doFilter(r,s,(a,b)->{assertThat(a.getReader().readLine()).isEqualTo("{\"quantity\":2}");((jakarta.servlet.http.HttpServletResponse)b).setStatus(503);});
        assertThat(s.getHeader("X-Request-Id")).matches("[a-f0-9-]{36}");assertThat(s.getHeader("X-Frame-Options")).isEqualTo("DENY");assertThat(traffic.snapshot().get("errors5xx")).isEqualTo(1L);
        assertThat(org.slf4j.MDC.get("requestId")).isNull();
    }
    @Test void redactsSecretsAndFullPhoneNumbers(){
        assertThat(SafeJsonLogLayout.redact("password=supersecret token=refreshtoken Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature phone=0901234567"))
            .doesNotContain("supersecret","refreshtoken","eyJhbGci","0901234567");
    }
}
