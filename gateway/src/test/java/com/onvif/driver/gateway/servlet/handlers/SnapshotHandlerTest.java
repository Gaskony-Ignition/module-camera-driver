package com.onvif.driver.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.CameraExtensionPoint;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SnapshotHandler — focused on input validation and auth.
 */
@ExtendWith(MockitoExtension.class)
class SnapshotHandlerTest {

    @Mock GatewayContext gatewayContext;
    @Mock CameraExtensionPoint cameraExtensionPoint;
    @Mock Go2RtcManager go2RtcManager;
    @Mock AuthenticationManager authManager;
    @Mock RequestContext requestContext;
    @Mock HttpServletRequest httpRequest;
    @Mock HttpServletResponse response;

    SnapshotHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SnapshotHandler(
            gatewayContext,
            cameraExtensionPoint,
            go2RtcManager,
            authManager,
            "2.31.0"
        );
    }

    // -----------------------------------------------------------------------
    // Authentication tests
    // -----------------------------------------------------------------------

    @Test
    void testHandle_UnauthenticatedRequest_Returns401() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(false);

        handler.handle(requestContext, response);

        verify(response).sendError(eq(401), anyString());
    }

    // -----------------------------------------------------------------------
    // Input validation tests (authenticated requests)
    // -----------------------------------------------------------------------

    @Test
    void testHandle_MissingDeviceParam_Returns400() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(true);
        when(requestContext.getRequest()).thenReturn(httpRequest);
        // Simulate rate limit passes
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpRequest.getHeader("X-Real-IP")).thenReturn(null);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        // device parameter is null
        when(requestContext.getParameter("device")).thenReturn(null);

        handler.handle(requestContext, response);

        verify(response).sendError(eq(400), contains("device"));
    }

    @Test
    void testHandle_InvalidDeviceNameFormat_Returns400() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(true);
        when(requestContext.getRequest()).thenReturn(httpRequest);
        // Simulate rate limit passes
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpRequest.getHeader("X-Real-IP")).thenReturn(null);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        // Inject a device name with invalid characters (XSS attempt)
        when(requestContext.getParameter("device")).thenReturn("<script>alert(1)</script>");

        handler.handle(requestContext, response);

        verify(response).sendError(eq(400), contains("Invalid device name format"));
    }
}
