package com.gaskony.camera.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.gateway.auth.AccessControl;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
import com.gaskony.camera.gateway.device.CameraDevice;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Abstract base class for all Camera Driver route handlers.
 * Provides shared dependencies, authentication, and device-lookup helpers.
 */
public abstract class BaseHandler {

    protected static final Logger logger = LoggerFactory.getLogger(BaseHandler.class);
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
     *
     * <p>Retained for backward compatibility with existing handlers.
     * New code should prefer {@link #requireAuthenticated(RequestContext, HttpServletResponse)}
     * (the P3-CD shared-pattern entry point), which both checks and writes
     * the 401 in a single call.</p>
     */
    protected boolean isAuthenticated(RequestContext requestContext) {
        if (!REQUIRE_AUTHENTICATION) {
            return true;
        }
        return authManager.isAuthenticated(requestContext);
    }

    /**
     * Validates that the request is authenticated and writes a 401 if not.
     *
     * <p>This is the P3-CD migration entry point — see
     * {@link AccessControl#requireAuthenticated} and
     * /modules/.review/FINAL_REVIEW.md §5 P3. Returns {@code true} if the
     * caller can proceed; {@code false} if the 401 has already been
     * written and the caller should return without further work.</p>
     *
     * @param requestContext the route request context
     * @param response       the response to write the 401 to on failure
     * @return {@code true} when authenticated, {@code false} otherwise
     */
    protected boolean requireAuthenticated(RequestContext requestContext,
                                           HttpServletResponse response) throws IOException {
        return AccessControl.requireAuthenticated(authManager, requestContext, response);
    }

    /**
     * Validates that the request is authenticated AND has administrator
     * authority. See {@link AccessControl#requireAdministrator}.
     */
    protected boolean requireAdministrator(RequestContext requestContext,
                                           HttpServletResponse response) throws IOException {
        return AccessControl.requireAdministrator(authManager, requestContext, response);
    }

    /**
     * Sends an authentication required error response.
     */
    protected void sendAuthenticationRequired(HttpServletResponse response) throws IOException {
        response.sendError(401, "Authentication required. Please log in to the Ignition Gateway or provide an API key.");
    }

    /**
     * Ensures the device's RTSP stream is registered with go2rtc before it is
     * proxied (MP4/MJPEG stream or WebRTC signaling). Devices can connect
     * before go2rtc finishes starting, or discover their RTSP URL only after
     * initial connection (e.g. via ONVIF), so a late registration attempt is
     * made here if one has not already succeeded.
     *
     * <p>Shared by {@link StreamHandler} and {@link WebRtcHandler} so the
     * "ensure-then-proxy" ordering lives in exactly one place rather than
     * being duplicated per handler.</p>
     *
     * @param device the resolved camera device
     */
    protected void ensureGo2RtcRegistered(CameraDevice device) {
        if (!device.isGo2RtcStreamRegistered()) {
            device.tryRegisterGo2Rtc();
        }
    }
}
