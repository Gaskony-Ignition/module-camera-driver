package com.gaskony.camera.gateway.servlet;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.common.CameraDriverPaths;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.servlet.handlers.DeviceApiHandler;
import com.gaskony.camera.gateway.servlet.handlers.DiagnosticsHandler;
import com.gaskony.camera.gateway.servlet.handlers.GatewayLogHandler;
import com.gaskony.camera.gateway.servlet.handlers.PageHandler;
import com.gaskony.camera.gateway.servlet.handlers.PtzHandler;
import com.gaskony.camera.gateway.servlet.handlers.SnapshotHandler;
import com.gaskony.camera.gateway.servlet.handlers.StreamHandler;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Route registration for the Camera Driver module.
 * Each route delegates to a focused handler class.
 * Registers routes under /data/camera-driver/*
 */
public class ONVIFRoutes {

    private static final Logger logger = LoggerFactory.getLogger(ONVIFRoutes.class);

    private final AuthenticationManager authManager;
    private final SnapshotHandler snapshotHandler;
    private final StreamHandler streamHandler;
    private final DeviceApiHandler deviceApiHandler;
    private final DiagnosticsHandler diagnosticsHandler;
    private final GatewayLogHandler gatewayLogHandler;
    private final PageHandler pageHandler;
    private final PtzHandler ptzHandler;

    public ONVIFRoutes(GatewayContext context,
                       CameraExtensionPoint cameraExtensionPoint,
                       Go2RtcManager go2RtcManager,
                       String moduleVersion) {
        this.authManager = new AuthenticationManager(context);
        logger.info("AuthenticationManager initialized with account lockout and API key support");

        this.snapshotHandler = new SnapshotHandler(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
        this.streamHandler = new StreamHandler(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
        this.deviceApiHandler = new DeviceApiHandler(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
        this.diagnosticsHandler = new DiagnosticsHandler(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
        this.gatewayLogHandler = new GatewayLogHandler(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
        this.pageHandler = new PageHandler(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
        this.ptzHandler = new PtzHandler(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    /**
     * Mounts the Camera Driver routes on the provided RouteGroup.
     */
    public void mountRoutes(RouteGroup routes) {
        logger.info("Mounting camera driver routes...");

        routes.newRoute(CameraDriverPaths.ROUTE_SNAPSHOT)
            .handler(snapshotHandler::handle)
            .type(RouteGroup.TYPE_OCTET_STREAM)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_STREAM)
            .handler(streamHandler::handle)
            .type(RouteGroup.TYPE_OCTET_STREAM)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_DEVICES)
            .handler(deviceApiHandler::handleListDevices)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_DEVICE_STATUS)
            .handler(deviceApiHandler::handleDeviceStatus)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_CONNECTION_BROWSER)
            .handler(pageHandler::handleConnectionBrowserPage)
            .type(RouteGroup.TYPE_OCTET_STREAM)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_HEALTH)
            .handler(diagnosticsHandler::handleHealthCheck)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_DIAGNOSTICS)
            .handler(diagnosticsHandler::handleDiagnostics)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_PLAYER)
            .handler(pageHandler::handlePlayerPage)
            .type(RouteGroup.TYPE_OCTET_STREAM)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_AUTH_STATUS)
            .handler(diagnosticsHandler::handleAuthStatus)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_LOGS_GATEWAY)
            .handler(gatewayLogHandler::handleGatewayLogs)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_MSE_PLAYER_JS)
            .handler(pageHandler::handleMsePlayerJs)
            .type(RouteGroup.TYPE_OCTET_STREAM)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_PTZ_MOVE)
            .handler(ptzHandler::handleMove)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_PTZ_STOP)
            .handler(ptzHandler::handleStop)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute(CameraDriverPaths.ROUTE_PTZ_STATUS)
            .handler(ptzHandler::handleStatus)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        logger.info("Camera driver routes mounted under /data/{}", CameraDriverPaths.MOUNT_ALIAS);
    }

    public AuthenticationManager getAuthenticationManager() {
        return authManager;
    }

    public static void shutdown() {
        RateLimiter.shutdown();
    }
}
