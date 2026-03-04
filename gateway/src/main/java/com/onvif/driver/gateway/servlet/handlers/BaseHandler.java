package com.onvif.driver.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.CameraDevice;
import com.onvif.driver.gateway.device.CameraExtensionPoint;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Abstract base class for all Camera Driver route handlers.
 * Provides shared dependencies, authentication, and device-lookup helpers.
 */
public abstract class BaseHandler {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected static final boolean REQUIRE_AUTHENTICATION = true;

    protected final GatewayContext context;
    protected final CameraExtensionPoint cameraExtensionPoint;
    protected final Go2RtcManager go2RtcManager;
    protected final AuthenticationManager authManager;
    protected final String moduleVersion;

    protected BaseHandler(GatewayContext context,
                          CameraExtensionPoint cameraExtensionPoint,
                          Go2RtcManager go2RtcManager,
                          AuthenticationManager authManager,
                          String moduleVersion) {
        this.context = context;
        this.cameraExtensionPoint = cameraExtensionPoint;
        this.go2RtcManager = go2RtcManager;
        this.authManager = authManager;
        this.moduleVersion = moduleVersion;
    }

    /**
     * Looks up a device by name in the unified Camera registry.
     */
    protected CameraDevice findDevice(String deviceName) {
        return cameraExtensionPoint != null ? cameraExtensionPoint.getDevice(deviceName) : null;
    }

    /**
     * Checks if the request has valid authentication.
     */
    protected boolean isAuthenticated(RequestContext requestContext) {
        if (!REQUIRE_AUTHENTICATION) {
            return true;
        }
        return authManager.isAuthenticated(requestContext);
    }

    /**
     * Sends an authentication required error response.
     */
    protected void sendAuthenticationRequired(HttpServletResponse response) throws IOException {
        response.sendError(401, "Authentication required. Please log in to the Ignition Gateway or provide an API key.");
    }
}
