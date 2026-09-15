package com.gaskony.camera.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DiagnosticsHandler.
 *
 * Tests focus on auth delegation and error responses because the full
 * handleHealthCheck / handleDiagnostics methods call static methods on
 * CameraExtensionPoint which require a live Ignition runtime.
 * The auth path is fully testable without PowerMock.
 */
@ExtendWith(MockitoExtension.class)
class DiagnosticsHandlerTest {

    @Mock GatewayContext gatewayContext;
    @Mock CameraExtensionPoint cameraExtensionPoint;
    @Mock Go2RtcManager go2RtcManager;
    @Mock AuthenticationManager authManager;
    @Mock RequestContext requestContext;
    @Mock HttpServletRequest httpRequest;
    @Mock HttpServletResponse response;

    DiagnosticsHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DiagnosticsHandler(
            gatewayContext,
            cameraExtensionPoint,
            go2RtcManager,
            authManager,
            "2.31.0"
        );
    }

    // -----------------------------------------------------------------------
    // handleAuthStatus tests
    // -----------------------------------------------------------------------

    @Test
    void testHandleAuthStatus_AlwaysReturns200WithAuthenticatedField_WhenAuthenticated() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(true);
        StringWriter responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));

        Object result = handler.handleAuthStatus(requestContext, response);

        assertThat(result).isNull();
        verify(response).setContentType("application/json");
        String body = responseBody.toString();
        assertThat(body).contains("\"authenticated\"");
        assertThat(body).contains("true");
        // Must never send a 401 for auth-status
        verify(response, never()).sendError(eq(401), anyString());
    }

    @Test
    void testHandleAuthStatus_AlwaysReturns200WithAuthenticatedField_WhenNotAuthenticated() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(false);
        StringWriter responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));

        Object result = handler.handleAuthStatus(requestContext, response);

        assertThat(result).isNull();
        verify(response).setContentType("application/json");
        String body = responseBody.toString();
        assertThat(body).contains("\"authenticated\"");
        assertThat(body).contains("false");
        // Must never send a 401 for auth-status (that's the whole point of this endpoint)
        verify(response, never()).sendError(eq(401), anyString());
    }

    // -----------------------------------------------------------------------
    // handleHealthCheck auth tests
    // -----------------------------------------------------------------------

    @Test
    void testHandleHealthCheck_UnauthenticatedRequest_Returns401() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(false);

        handler.handleHealthCheck(requestContext, response);

        verify(response).sendError(eq(401), anyString());
    }

    // -----------------------------------------------------------------------
    // handleDiagnostics auth tests
    // -----------------------------------------------------------------------

    @Test
    void testHandleDiagnostics_UnauthenticatedRequest_Returns401() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(false);

        handler.handleDiagnostics(requestContext, response);

        verify(response).sendError(eq(401), anyString());
    }
}
