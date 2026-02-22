package com.onvif.driver.gateway.servlet;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.common.CameraDriverPaths;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.device.generic.GenericCameraExtensionPoint;
import com.onvif.driver.gateway.servlet.handlers.DeviceApiHandler;
import com.onvif.driver.gateway.servlet.handlers.DiagnosticsHandler;
import com.onvif.driver.gateway.servlet.handlers.GatewayLogHandler;
import com.onvif.driver.gateway.servlet.handlers.PageHandler;
import com.onvif.driver.gateway.servlet.handlers.SnapshotHandler;
import com.onvif.driver.gateway.servlet.handlers.StreamHandler;
import com.onvif.driver.gateway.stream.Go2RtcManager;
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

    public ONVIFRoutes(GatewayContext context,
                       ONVIFDeviceExtensionPoint deviceExtensionPoint,
                       GenericCameraExtensionPoint genericCameraExtensionPoint,
                       Go2RtcManager go2RtcManager,
                       String moduleVersion) {
        this.authManager = new AuthenticationManager(context);
        logger.info("AuthenticationManager initialized with account lockout and API key support");

        this.snapshotHandler = new SnapshotHandler(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
        this.streamHandler = new StreamHandler(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
        this.deviceApiHandler = new DeviceApiHandler(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
        this.diagnosticsHandler = new DiagnosticsHandler(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
        this.gatewayLogHandler = new GatewayLogHandler(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
        this.pageHandler = new PageHandler(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    /**
     * Mounts the Camera Driver routes on the provided RouteGroup.
     */
    public void mountRoutes(RouteGroup routes) {
        logger.info("Mounting camera driver routes...");
        logger.debug("RouteGroup: {}, class: {}", routes, routes.getClass().getName());

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

        // Returns 200 with {authenticated: true/false} — no 401, no WWW-Authenticate header
        // Used by standalone.html to check auth without triggering browser Basic Auth popup
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

        logger.info("Camera driver routes mounted under /data/{}", CameraDriverPaths.MOUNT_ALIAS);
    }

    /**
     * Gets the AuthenticationManager for programmatic access (e.g., adding API keys).
     */
    public AuthenticationManager getAuthenticationManager() {
        return authManager;
    }

    /**
     * Shuts down background resources. Called when module is unloaded.
     */
    public static void shutdown() {
        RateLimiter.shutdown();
    }
}
