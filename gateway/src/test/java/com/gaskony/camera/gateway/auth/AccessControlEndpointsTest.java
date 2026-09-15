package com.gaskony.camera.gateway.auth;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.servlet.handlers.DeviceApiHandler;
import com.gaskony.camera.gateway.servlet.handlers.DiagnosticsHandler;
import com.gaskony.camera.gateway.servlet.handlers.GatewayLogHandler;
import com.gaskony.camera.gateway.servlet.handlers.PageHandler;
import com.gaskony.camera.gateway.servlet.handlers.PtzHandler;
import com.gaskony.camera.gateway.servlet.handlers.SnapshotHandler;
import com.gaskony.camera.gateway.servlet.handlers.StreamHandler;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-CD parameterised regression — every protected Camera Driver endpoint
 * must return 401 when the request is unauthenticated.
 *
 * <p>Per /modules/.review/FINAL_REVIEW.md §5 P3, the previous routing model
 * (every route mounted with {@code AccessControlStrategy.OPEN_ROUTE}, each
 * handler hand-rolling its own auth check) was one missing line away from a
 * leak — and {@code PageHandler.handleConnectionBrowserPage} was exactly
 * that bug. This test parameterises over every protected endpoint so future
 * additions are forced to register here too; a regression to OPEN_ROUTE
 * without the {@code requireAuthenticated} call would fail this test.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccessControlEndpointsTest {

    @Mock GatewayContext gatewayContext;
    @Mock CameraExtensionPoint cameraExtensionPoint;
    @Mock Go2RtcManager go2RtcManager;
    @Mock AuthenticationManager authManager;

    /**
     * Each endpoint is represented by an invoker lambda. Mockito mocks for
     * RequestContext / HttpServletResponse are fresh per test so that
     * verifications don't bleed across endpoints.
     */
    @FunctionalInterface
    interface EndpointInvoker {
        void invoke(RequestContext ctx, HttpServletResponse resp,
                    GatewayContext gw, CameraExtensionPoint xp,
                    Go2RtcManager g2r, AuthenticationManager auth) throws Exception;
    }

    record EndpointCase(String name, EndpointInvoker invoker) {
        @Override public String toString() { return name; }
    }

    static Stream<EndpointCase> protectedEndpoints() {
        return Stream.of(
            new EndpointCase("SnapshotHandler.handle",
                (ctx, resp, gw, xp, g2r, auth) ->
                    new SnapshotHandler(gw, xp, g2r, auth, "test").handle(ctx, resp)),

            new EndpointCase("StreamHandler.handle",
                (ctx, resp, gw, xp, g2r, auth) ->
                    new StreamHandler(gw, xp, g2r, auth, "test").handle(ctx, resp)),

            new EndpointCase("DeviceApiHandler.handleListDevices",
                (ctx, resp, gw, xp, g2r, auth) ->
                    new DeviceApiHandler(gw, xp, g2r, auth, "test").handleListDevices(ctx, resp)),

            new EndpointCase("DeviceApiHandler.handleDeviceStatus",
                (ctx, resp, gw, xp, g2r, auth) -> {
                    when(ctx.getParameter("name")).thenReturn("anything");
                    new DeviceApiHandler(gw, xp, g2r, auth, "test").handleDeviceStatus(ctx, resp);
                }),

            new EndpointCase("DiagnosticsHandler.handleHealthCheck",
                (ctx, resp, gw, xp, g2r, auth) ->
                    new DiagnosticsHandler(gw, xp, g2r, auth, "test").handleHealthCheck(ctx, resp)),

            new EndpointCase("DiagnosticsHandler.handleDiagnostics",
                (ctx, resp, gw, xp, g2r, auth) ->
                    new DiagnosticsHandler(gw, xp, g2r, auth, "test").handleDiagnostics(ctx, resp)),

            new EndpointCase("GatewayLogHandler.handleGatewayLogs",
                (ctx, resp, gw, xp, g2r, auth) ->
                    new GatewayLogHandler(gw, xp, g2r, auth, "test").handleGatewayLogs(ctx, resp)),

            new EndpointCase("PageHandler.handlePlayerPage",
                (ctx, resp, gw, xp, g2r, auth) ->
                    new PageHandler(gw, xp, g2r, auth, "test").handlePlayerPage(ctx, resp)),

            new EndpointCase("PageHandler.handleConnectionBrowserPage",
                (ctx, resp, gw, xp, g2r, auth) ->
                    new PageHandler(gw, xp, g2r, auth, "test").handleConnectionBrowserPage(ctx, resp)),

            new EndpointCase("PtzHandler.handleMove",
                (ctx, resp, gw, xp, g2r, auth) ->
                    new PtzHandler(gw, xp, g2r, auth, "test").handleMove(ctx, resp)),

            new EndpointCase("PtzHandler.handleStop",
                (ctx, resp, gw, xp, g2r, auth) ->
                    new PtzHandler(gw, xp, g2r, auth, "test").handleStop(ctx, resp)),

            new EndpointCase("PtzHandler.handleStatus",
                (ctx, resp, gw, xp, g2r, auth) ->
                    new PtzHandler(gw, xp, g2r, auth, "test").handleStatus(ctx, resp))
        );
    }

    @ParameterizedTest(name = "{0} returns 401 when unauthenticated")
    @MethodSource("protectedEndpoints")
    void testProtectedEndpoint_UnauthenticatedRequest_Returns401(EndpointCase endpoint) throws Exception {
        // Fresh mocks per parameterised case to avoid verification bleed-over.
        RequestContext ctx = mock(RequestContext.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        // Auth manager says no.
        when(authManager.isAuthenticated(ctx)).thenReturn(false);
        // Response not committed when we get to the auth check.
        when(resp.isCommitted()).thenReturn(false);

        endpoint.invoker.invoke(ctx, resp, gatewayContext, cameraExtensionPoint, go2RtcManager, authManager);

        // The shared AccessControl helper is responsible for writing the 401.
        verify(resp).sendError(eq(401), anyString());
    }
}
