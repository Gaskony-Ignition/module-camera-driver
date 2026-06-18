package com.gaskony.camera.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
import com.gaskony.camera.gateway.device.CameraConfig;
import com.gaskony.camera.gateway.device.CameraDevice;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StreamHandler — input validation, auth, and the
 * upstream-failure vs no-source-configured error semantics.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamHandlerTest {

    @Mock GatewayContext gatewayContext;
    @Mock CameraExtensionPoint cameraExtensionPoint;
    @Mock Go2RtcManager go2RtcManager;
    @Mock AuthenticationManager authManager;
    @Mock RequestContext requestContext;
    @Mock HttpServletRequest httpRequest;
    @Mock HttpServletResponse response;

    StreamHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StreamHandler(
            gatewayContext,
            cameraExtensionPoint,
            go2RtcManager,
            authManager,
            "3.0.1"
        );
        StreamHandler.resetCounters();
    }

    /** Wires up authentication + rate-limiter pass-through for authenticated cases. */
    private void authenticatedRequest() {
        when(authManager.isAuthenticated(requestContext)).thenReturn(true);
        when(requestContext.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpRequest.getHeader("X-Real-IP")).thenReturn(null);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    // -----------------------------------------------------------------------
    // Authentication / input validation
    // -----------------------------------------------------------------------

    @Test
    void testHandle_UnauthenticatedRequest_Returns401() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(false);

        handler.handle(requestContext, response);

        verify(response).sendError(eq(401), anyString());
    }

    @Test
    void testHandle_MissingDeviceParam_Returns400() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn(null);

        handler.handle(requestContext, response);

        verify(response).sendError(eq(400), contains("device"));
    }

    @Test
    void testHandle_InvalidDeviceNameFormat_Returns400() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("<script>alert(1)</script>");

        handler.handle(requestContext, response);

        verify(response).sendError(eq(400), contains("Invalid device name format"));
    }

    // -----------------------------------------------------------------------
    // Upstream-failure vs no-source-configured semantics (the robustness fix)
    // -----------------------------------------------------------------------

    /** Builds a connected device mock with no MJPEG/ONVIF/snapshot fallbacks. */
    private CameraDevice connectedDeviceWithoutFallbacks(String name) {
        CameraDevice device = mock(CameraDevice.class);
        when(cameraExtensionPoint.getDevice(name)).thenReturn(device);
        when(device.getStatus()).thenReturn("Connected");
        // No native MJPEG url configured
        CameraConfig config = mock(CameraConfig.class);
        CameraConfig.Advanced advanced = mock(CameraConfig.Advanced.class);
        when(device.getConfig()).thenReturn(config);
        when(config.advanced()).thenReturn(advanced);
        when(advanced.mjpegUrl()).thenReturn(null);
        // Not an ONVIF device and no generic snapshot client
        when(device.isOnvifAvailable()).thenReturn(false);
        when(device.getCameraClient()).thenReturn(null);
        return device;
    }

    @Test
    void testHandle_Go2RtcConfiguredButUpstreamFails_Returns502() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("FrontPTZ");
        CameraDevice device = connectedDeviceWithoutFallbacks("FrontPTZ");

        // go2rtc IS registered+available, but the upstream MP4 URL is unreachable.
        when(device.isGo2RtcStreamRegistered()).thenReturn(true);
        when(go2RtcManager.isAvailable()).thenReturn(true);
        // Port 1 refuses instantly -> proxyStream() reports a connect error.
        when(go2RtcManager.getStreamMp4Url("FrontPTZ"))
            .thenReturn("http://127.0.0.1:1/api/stream.mp4?src=FrontPTZ");

        handler.handle(requestContext, response);

        // Configured source that can't be pulled => 502, NOT a misleading 400.
        verify(response).sendError(eq(502), contains("unavailable"));
        verify(response, never()).sendError(eq(400), anyString());
    }

    @Test
    void testHandle_NoSourceConfigured_Returns400() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("EmptyCam");
        CameraDevice device = connectedDeviceWithoutFallbacks("EmptyCam");

        // No go2rtc registration and registration attempt fails.
        when(device.isGo2RtcStreamRegistered()).thenReturn(false);
        when(device.tryRegisterGo2Rtc()).thenReturn(false);

        handler.handle(requestContext, response);

        // Genuinely nothing configured => 400 telling the user to configure a source.
        verify(response).sendError(eq(400), contains("No streaming source available"));
        verify(response, never()).sendError(eq(502), anyString());
    }
}
