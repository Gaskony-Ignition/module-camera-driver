package com.gaskony.camera.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PageHandler — focused on authentication behaviour.
 */
@ExtendWith(MockitoExtension.class)
class PageHandlerTest {

    @Mock GatewayContext gatewayContext;
    @Mock CameraExtensionPoint cameraExtensionPoint;
    @Mock Go2RtcManager go2RtcManager;
    @Mock AuthenticationManager authManager;
    @Mock RequestContext requestContext;
    @Mock HttpServletResponse response;

    PageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PageHandler(
            gatewayContext,
            cameraExtensionPoint,
            go2RtcManager,
            authManager,
            "2.31.0"
        );
    }

    // -----------------------------------------------------------------------
    // handlePlayerPage auth tests
    // -----------------------------------------------------------------------

    @Test
    void testHandlePlayerPage_UnauthenticatedRequest_Returns401() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(false);

        handler.handlePlayerPage(requestContext, response);

        verify(response).sendError(eq(401), anyString());
    }
}
