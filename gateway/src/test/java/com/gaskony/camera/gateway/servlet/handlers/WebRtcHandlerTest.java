package com.gaskony.camera.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
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
 * Unit tests for WebRtcHandler — input validation and auth for the WebRTC
 * signaling proxy. Mirrors {@link StreamHandlerTest}'s mock setup patterns.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebRtcHandlerTest {

    @Mock GatewayContext gatewayContext;
    @Mock CameraExtensionPoint cameraExtensionPoint;
    @Mock Go2RtcManager go2RtcManager;
    @Mock AuthenticationManager authManager;
    @Mock RequestContext requestContext;
    @Mock HttpServletRequest httpRequest;
    @Mock HttpServletResponse response;

    WebRtcHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WebRtcHandler(
            gatewayContext,
            cameraExtensionPoint,
            go2RtcManager,
            authManager,
            "3.0.1"
        );
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
    void testHandle_BlankDeviceParam_Returns400() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("   ");

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

    @Test
    void testHandle_UnknownDevice_Returns404() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("NoSuchCamera");
        when(cameraExtensionPoint.getDevice("NoSuchCamera")).thenReturn(null);

        handler.handle(requestContext, response);

        verify(response).sendError(eq(404), contains("not found"));
    }

    // -----------------------------------------------------------------------
    // Missing offer body — validated before any go2rtc interaction
    // -----------------------------------------------------------------------

    @Test
    void testHandle_MissingOfferBody_Returns400() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("FrontPTZ");
        CameraDevice device = mock(CameraDevice.class);
        when(cameraExtensionPoint.getDevice("FrontPTZ")).thenReturn(device);
        when(requestContext.readBody()).thenReturn("");

        handler.handle(requestContext, response);

        verify(response).sendError(eq(400), contains("SDP offer"));
        verifyNoInteractions(go2RtcManager);
    }

    // -----------------------------------------------------------------------
    // go2rtc unavailable after a valid offer body
    // -----------------------------------------------------------------------

    @Test
    void testHandle_Go2RtcUnavailable_Returns502() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("FrontPTZ");
        CameraDevice device = mock(CameraDevice.class);
        when(cameraExtensionPoint.getDevice("FrontPTZ")).thenReturn(device);
        when(requestContext.readBody()).thenReturn("v=0\r\n");
        when(device.isGo2RtcStreamRegistered()).thenReturn(false);
        when(device.tryRegisterGo2Rtc()).thenReturn(false);
        when(go2RtcManager.isAvailable()).thenReturn(false);

        handler.handle(requestContext, response);

        verify(response).sendError(eq(502), anyString());
    }
}
