package com.onvif.driver.gateway.servlet;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the RateLimiter static utility.
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    @Mock HttpServletRequest request;

    @BeforeEach
    void clearRequestsPerIP() throws Exception {
        // Reset the static requestsPerIP map between tests so counts don't bleed across
        Field field = RateLimiter.class.getDeclaredField("requestsPerIP");
        field.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) field.get(null);
        map.clear();
    }

    // -----------------------------------------------------------------------
    // getClientIP tests
    // -----------------------------------------------------------------------

    @Test
    void testGetClientIP_XForwardedForHeader_ReturnsFirstIp() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 5.6.7.8");

        String ip = RateLimiter.getClientIP(request);

        assertThat(ip).isEqualTo("1.2.3.4");
    }

    @Test
    void testGetClientIP_XRealIpHeader_ReturnsXRealIp() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn("9.10.11.12");

        String ip = RateLimiter.getClientIP(request);

        assertThat(ip).isEqualTo("9.10.11.12");
    }

    @Test
    void testGetClientIP_NoProxyHeaders_ReturnsRemoteAddr() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");

        String ip = RateLimiter.getClientIP(request);

        assertThat(ip).isEqualTo("192.168.1.100");
    }

    @Test
    void testGetClientIP_XForwardedForEmpty_FallsThrough() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("");
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        String ip = RateLimiter.getClientIP(request);

        assertThat(ip).isEqualTo("10.0.0.1");
    }

    // -----------------------------------------------------------------------
    // getConnectedClientCount tests
    // -----------------------------------------------------------------------

    @Test
    void testGetConnectedClientCount_InitiallyZero() {
        // requestsPerIP was cleared in @BeforeEach
        assertThat(RateLimiter.getConnectedClientCount()).isEqualTo(0);
    }
}
