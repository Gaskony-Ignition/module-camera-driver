package com.gaskony.camera.gateway.servlet;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CorsManager.isAllowedOrigin().
 */
@ExtendWith(MockitoExtension.class)
class CorsManagerTest {

    @Mock HttpServletRequest request;

    @BeforeEach
    void setUpGatewayDefaults() {
        // Default gateway configuration: http://mygateway:8088
        // Use lenient() so tests that return early (null/empty origin) don't trigger
        // UnnecessaryStubbingException — the request is never consulted in those paths.
        lenient().when(request.getScheme()).thenReturn("http");
        lenient().when(request.getServerName()).thenReturn("mygateway");
        lenient().when(request.getServerPort()).thenReturn(8088);
    }

    // -----------------------------------------------------------------------
    // Null / empty origin
    // -----------------------------------------------------------------------

    @Test
    void testIsAllowedOrigin_NullOrigin_ReturnsFalse() {
        assertThat(CorsManager.isAllowedOrigin(null, request)).isFalse();
    }

    @Test
    void testIsAllowedOrigin_EmptyOrigin_ReturnsFalse() {
        assertThat(CorsManager.isAllowedOrigin("", request)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Localhost / loopback — always allowed
    // -----------------------------------------------------------------------

    @Test
    void testIsAllowedOrigin_Localhost_ReturnsTrue() {
        assertThat(CorsManager.isAllowedOrigin("http://localhost:8088", request)).isTrue();
    }

    @Test
    void testIsAllowedOrigin_LoopbackIp_ReturnsTrue() {
        assertThat(CorsManager.isAllowedOrigin("http://127.0.0.1:8088", request)).isTrue();
    }

    // -----------------------------------------------------------------------
    // Gateway origin match
    // -----------------------------------------------------------------------

    @Test
    void testIsAllowedOrigin_GatewayOrigin_ReturnsTrue() {
        // Gateway is http://mygateway:8088 → matches exactly
        assertThat(CorsManager.isAllowedOrigin("http://mygateway:8088", request)).isTrue();
    }

    @Test
    void testIsAllowedOrigin_DifferentHost_ReturnsFalse() {
        // Attacker's origin must NOT be allowed even when gateway is mygateway:8088
        assertThat(CorsManager.isAllowedOrigin("http://attacker.com", request)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Port omission for default ports
    // -----------------------------------------------------------------------

    @Test
    void testIsAllowedOrigin_HttpsDefaultPort_OmitsPort() {
        // HTTPS on port 443 → gateway origin is "https://mygateway" (no port suffix)
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("mygateway");
        when(request.getServerPort()).thenReturn(443);

        assertThat(CorsManager.isAllowedOrigin("https://mygateway", request)).isTrue();
        // With port explicitly in origin → must not match (port 443 is default, omitted)
        assertThat(CorsManager.isAllowedOrigin("https://mygateway:443", request)).isFalse();
    }

    @Test
    void testIsAllowedOrigin_HttpDefaultPort_OmitsPort() {
        // HTTP on port 80 → gateway origin is "http://mygateway" (no port suffix)
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("mygateway");
        when(request.getServerPort()).thenReturn(80);

        assertThat(CorsManager.isAllowedOrigin("http://mygateway", request)).isTrue();
        assertThat(CorsManager.isAllowedOrigin("http://mygateway:80", request)).isFalse();
    }

    @Test
    void testIsAllowedOrigin_HttpNonStandardPort_IncludesPort() {
        // Non-default port must appear in the gateway origin
        // Default setUp already has port 8088
        assertThat(CorsManager.isAllowedOrigin("http://mygateway:8088", request)).isTrue();
        // Without port → different origin string → must not match
        assertThat(CorsManager.isAllowedOrigin("http://mygateway", request)).isFalse();
    }
}
