package com.onvif.driver.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.common.DeviceStatus;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.ONVIFDevice;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.device.generic.GenericCameraDevice;
import com.onvif.driver.gateway.device.generic.GenericCameraExtensionPoint;
import com.onvif.driver.gateway.servlet.CorsManager;
import com.onvif.driver.gateway.servlet.RateLimiter;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import com.onvif.driver.gateway.util.ValidationUtil;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles snapshot image requests for both ONVIF and Generic Camera devices.
 * URL: /data/camera-driver/snapshot?device=DeviceName&profile=ProfileToken
 */
public class SnapshotHandler extends BaseHandler {

    /** Maximum number of concurrent snapshot requests allowed across all IPs. */
    private static final int MAX_CONCURRENT_SNAPSHOTS = 50;
    private static final AtomicInteger activeSnapshots = new AtomicInteger(0);

    public SnapshotHandler(GatewayContext context,
                           ONVIFDeviceExtensionPoint deviceExtensionPoint,
                           GenericCameraExtensionPoint genericCameraExtensionPoint,
                           Go2RtcManager go2RtcManager,
                           AuthenticationManager authManager,
                           String moduleVersion) {
        super(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    public static int getActiveSnapshots() { return activeSnapshots.get(); }
    public static int getMaxConcurrentSnapshots() { return MAX_CONCURRENT_SNAPSHOTS; }

    /** Resets the active counter to 0. Call during module shutdown to ensure clean state on reload. */
    public static void resetCounters() {
        activeSnapshots.set(0);
    }

    /**
     * Handles snapshot requests.
     * URL: http://gateway:8088/data/camera-driver/snapshot?device=DeviceName&profile=ProfileToken
     */
    public Object handle(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Snapshot request received");
        logger.debug("Request URL: {}", requestContext.getRequest().getRequestURL());
        logger.debug("Query String: {}", requestContext.getRequest().getQueryString());

        // v2.1.0: Authentication check
        if (!isAuthenticated(requestContext)) {
            sendAuthenticationRequired(response);
            return null;
        }

        // v2.1.0: Rate limiting check
        if (!RateLimiter.checkRateLimit(requestContext.getRequest(), response)) {
            return null;
        }

        // Check if deviceExtensionPoint is available
        if (deviceExtensionPoint == null) {
            logger.error("ERROR: deviceExtensionPoint is NULL in handler!");
            response.sendError(500, "Device extension point not initialized");
            return null;
        }

        if (activeSnapshots.get() >= MAX_CONCURRENT_SNAPSHOTS) {
            logger.warn("Snapshot request rejected - max concurrent requests reached");
            response.sendError(503, "Maximum concurrent snapshot requests reached");
            return null;
        }

        activeSnapshots.incrementAndGet();
        long startTime = System.currentTimeMillis();

        try {
            // Get parameters
            String deviceName = requestContext.getParameter("device");
            String profileToken = requestContext.getParameter("profile");
            logger.debug("Snapshot request: device={}, profile={}", deviceName, profileToken);

            if (deviceName == null || deviceName.trim().isEmpty()) {
                logger.warn("Missing device parameter");
                response.sendError(400, "Missing required parameter: device");
                return null;
            }

            // Validate device name format
            if (!ValidationUtil.isValidDeviceName(deviceName)) {
                logger.warn("Invalid device name format: {}", deviceName);
                response.sendError(400, "Invalid device name format");
                return null;
            }

            // Look up device in both registries (ONVIF first, then Generic Camera)
            DeviceLookup devices = findDevice(deviceName);
            if (!devices.found()) {
                logger.warn("Device not found in any registry: {}", deviceName);
                response.sendError(404, "Device not found");
                return null;
            }
            ONVIFDevice onvifDevice = devices.onvif();
            GenericCameraDevice genericDevice = devices.generic();

            byte[] snapshotBytes;

            if (onvifDevice != null) {
                // ONVIF device - use provided profile token or fall back to default
                if (profileToken == null || profileToken.trim().isEmpty()) {
                    profileToken = onvifDevice.getDefaultProfileToken();
                    if (profileToken == null) {
                        response.sendError(400, "No media profiles available for ONVIF device");
                        return null;
                    }
                    logger.debug("Using default profile token for ONVIF device {}: {}", deviceName, profileToken);
                }
                if (!ValidationUtil.isValidProfileToken(profileToken)) {
                    response.sendError(400, "Invalid profile token format");
                    return null;
                }

                String deviceStatus = onvifDevice.getStatus();
                if (!DeviceStatus.isActive(deviceStatus)) {
                    response.sendError(503, "Device is not connected: " + deviceStatus);
                    return null;
                }

                try {
                    snapshotBytes = onvifDevice.getClient().getSnapshot(profileToken);
                } catch (IOException e) {
                    logger.error("Failed to get snapshot from ONVIF device {}: {}", deviceName, e.getMessage());
                    response.sendError(500, "Failed to retrieve snapshot from camera");
                    return null;
                }
            } else {
                // Generic camera device
                String deviceStatus = genericDevice.getStatus();
                if (!DeviceStatus.isActive(deviceStatus)) {
                    response.sendError(503, "Device is not connected: " + deviceStatus);
                    return null;
                }

                if (genericDevice.getCameraClient() == null) {
                    response.sendError(500, "Camera client not initialized");
                    return null;
                }

                try {
                    snapshotBytes = genericDevice.getCameraClient().fetchSnapshot();
                } catch (IOException e) {
                    logger.error("Failed to get snapshot from generic camera {}: {}", deviceName, e.getMessage());
                    response.sendError(500, "Failed to retrieve snapshot from camera");
                    return null;
                }
            }

            // Validate snapshot content
            if (snapshotBytes != null && snapshotBytes.length > 0) {
                boolean isJpeg = snapshotBytes.length >= 3 &&
                                (snapshotBytes[0] & 0xFF) == 0xFF &&
                                (snapshotBytes[1] & 0xFF) == 0xD8 &&
                                (snapshotBytes[2] & 0xFF) == 0xFF;

                String contentStart = new String(snapshotBytes, 0, Math.min(100, snapshotBytes.length));
                boolean isHtml = contentStart.toLowerCase().contains("<!doctype") ||
                                contentStart.toLowerCase().contains("<html");

                if (!isJpeg || isHtml) {
                    logger.error("Camera returned {} instead of JPEG image for device: {}",
                        isHtml ? "HTML content" : "non-JPEG content", deviceName);
                    response.sendError(500, "Camera returned non-JPEG content instead of snapshot image");
                    return null;
                }
            } else {
                response.sendError(500, "Empty snapshot received from camera");
                return null;
            }

            // Send response
            logger.debug("Setting response headers");
            response.setContentType("image/jpeg");
            response.setContentLength(snapshotBytes.length);
            String origin = requestContext.getRequest().getHeader("Origin");
            CorsManager.setStreamingHeaders(response, origin, requestContext.getRequest());

            logger.debug("Writing {} bytes to response", snapshotBytes.length);
            response.getOutputStream().write(snapshotBytes);

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Snapshot delivered - device: {}, profile: {}, size: {} bytes, duration: {} ms",
                deviceName, profileToken, snapshotBytes.length, duration);

        } finally {
            activeSnapshots.decrementAndGet();
        }

        return null;
    }
}
