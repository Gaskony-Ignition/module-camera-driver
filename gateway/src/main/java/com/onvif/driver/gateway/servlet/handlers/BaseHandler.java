package com.onvif.driver.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.ONVIFDevice;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.device.generic.GenericCameraDevice;
import com.onvif.driver.gateway.device.generic.GenericCameraExtensionPoint;
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
    protected final ONVIFDeviceExtensionPoint deviceExtensionPoint;
    protected final GenericCameraExtensionPoint genericCameraExtensionPoint;
    protected final Go2RtcManager go2RtcManager;
    protected final AuthenticationManager authManager;
    protected final String moduleVersion;

    /**
     * Holds the result of looking up a device by name across both registries.
     */
    protected record DeviceLookup(ONVIFDevice onvif, GenericCameraDevice generic) {
        boolean found() { return onvif != null || generic != null; }
    }

    protected BaseHandler(GatewayContext context,
                          ONVIFDeviceExtensionPoint deviceExtensionPoint,
                          GenericCameraExtensionPoint genericCameraExtensionPoint,
                          Go2RtcManager go2RtcManager,
                          AuthenticationManager authManager,
                          String moduleVersion) {
        this.context = context;
        this.deviceExtensionPoint = deviceExtensionPoint;
        this.genericCameraExtensionPoint = genericCameraExtensionPoint;
        this.go2RtcManager = go2RtcManager;
        this.authManager = authManager;
        this.moduleVersion = moduleVersion;
    }

    /**
     * Looks up a device by name in both the ONVIF and Generic Camera registries.
     */
    protected DeviceLookup findDevice(String deviceName) {
        ONVIFDevice onvif = deviceExtensionPoint != null ? deviceExtensionPoint.getDevice(deviceName) : null;
        GenericCameraDevice generic = genericCameraExtensionPoint != null
            ? genericCameraExtensionPoint.getDevice(deviceName) : null;
        return new DeviceLookup(onvif, generic);
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
        // Do NOT set WWW-Authenticate: Basic — it can trigger auth dialogs in the Designer's
        // embedded JCEF browser, intercepting the 401 before the component's fetch() sees it.
        // The connection browser uses its own auth flow via /auth-status, not WWW-Authenticate.
        response.sendError(401, "Authentication required. Please log in to the Ignition Gateway or provide an API key.");
    }
}
