package com.gaskony.camera.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
import com.gaskony.camera.gateway.device.CameraDevice;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.servlet.RateLimiter;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import com.gaskony.camera.gateway.util.ValidationUtil;
import com.gaskony.camera.gateway.onvif.PTZStatus;
import jakarta.servlet.http.HttpServletResponse;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Handles PTZ (Pan-Tilt-Zoom) control requests for cameras with ONVIF PTZ capability.
 * Routes: /ptz/move, /ptz/stop, /ptz/status
 */
public class PtzHandler extends BaseHandler {

    public PtzHandler(GatewayContext context,
                      CameraExtensionPoint cameraExtensionPoint,
                      Go2RtcManager go2RtcManager,
                      AuthenticationManager authManager,
                      String moduleVersion) {
        super(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    public Object handleMove(RequestContext requestContext, HttpServletResponse response) throws Exception {
        // P3-CD: shared AccessControl pattern.
        if (!requireAuthenticated(requestContext, response)) {
            return null;
        }
        if (!RateLimiter.checkRateLimit(requestContext.getRequest(), response)) {
            return null;
        }

        CameraDevice device = resolvePtzDevice(requestContext, response);
        if (device == null) return null;

        double pan = parseDouble(requestContext.getParameter("pan"), 0.0);
        double tilt = parseDouble(requestContext.getParameter("tilt"), 0.0);
        double zoom = parseDouble(requestContext.getParameter("zoom"), 0.0);

        pan = Math.max(-1.0, Math.min(1.0, pan));
        tilt = Math.max(-1.0, Math.min(1.0, tilt));
        zoom = Math.max(-1.0, Math.min(1.0, zoom));

        String profileToken = device.getDefaultProfileToken();
        if (profileToken == null) {
            response.sendError(400, "No media profiles available for PTZ");
            return null;
        }

        try {
            device.getClient().continuousMove(profileToken, pan, tilt, zoom);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":true}");
        } catch (Exception e) {
            logger.error("PTZ move failed for device {}: {}", requestContext.getParameter("device"), e.getMessage());
            response.sendError(500, "PTZ move failed: " + e.getMessage());
        }
        return null;
    }

    public Object handleStop(RequestContext requestContext, HttpServletResponse response) throws Exception {
        // P3-CD: shared AccessControl pattern.
        if (!requireAuthenticated(requestContext, response)) {
            return null;
        }
        if (!RateLimiter.checkRateLimit(requestContext.getRequest(), response)) {
            return null;
        }

        CameraDevice device = resolvePtzDevice(requestContext, response);
        if (device == null) return null;

        String profileToken = device.getDefaultProfileToken();
        if (profileToken == null) {
            response.sendError(400, "No media profiles available for PTZ");
            return null;
        }

        try {
            device.getClient().ptzStop(profileToken);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":true}");
        } catch (Exception e) {
            logger.error("PTZ stop failed for device {}: {}", requestContext.getParameter("device"), e.getMessage());
            response.sendError(500, "PTZ stop failed: " + e.getMessage());
        }
        return null;
    }

    public Object handleStatus(RequestContext requestContext, HttpServletResponse response) throws Exception {
        // P3-CD: shared AccessControl pattern.
        if (!requireAuthenticated(requestContext, response)) {
            return null;
        }
        if (!RateLimiter.checkRateLimit(requestContext.getRequest(), response)) {
            return null;
        }

        CameraDevice device = resolvePtzDevice(requestContext, response);
        if (device == null) return null;

        String profileToken = device.getDefaultProfileToken();
        if (profileToken == null) {
            response.sendError(400, "No media profiles available for PTZ");
            return null;
        }

        try {
            PTZStatus status = device.getClient().getPTZStatus(profileToken);
            JSONObject json = new JSONObject();
            try {
                json.put("pan", status.getPan());
                json.put("tilt", status.getTilt());
                json.put("zoom", status.getZoom());
                json.put("moveStatus", status.getMoveStatus());
            } catch (JSONException e) {
                logger.debug("Error building PTZ status JSON: {}", e.getMessage());
            }
            response.setContentType("application/json");
            response.getWriter().write(json.toString());
        } catch (Exception e) {
            logger.error("PTZ status failed for device {}: {}", requestContext.getParameter("device"), e.getMessage());
            response.sendError(500, "PTZ status failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Resolves and validates a camera device with PTZ capability from the request.
     */
    private CameraDevice resolvePtzDevice(RequestContext requestContext, HttpServletResponse response) throws Exception {
        String deviceName = requestContext.getParameter("device");
        if (deviceName == null || deviceName.trim().isEmpty()) {
            response.sendError(400, "Missing required parameter: device");
            return null;
        }
        if (!ValidationUtil.isValidDeviceName(deviceName)) {
            response.sendError(400, "Invalid device name format");
            return null;
        }

        CameraDevice device = findDevice(deviceName);
        if (device == null) {
            response.sendError(404, "Device not found");
            return null;
        }

        if (!device.hasPTZ()) {
            response.sendError(400, "This device does not have PTZ capability");
            return null;
        }
        if (device.getClient() == null) {
            response.sendError(503, "ONVIF client not initialized for device");
            return null;
        }

        return device;
    }

    private static double parseDouble(String value, double defaultValue) {
        if (value == null || value.trim().isEmpty()) return defaultValue;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
