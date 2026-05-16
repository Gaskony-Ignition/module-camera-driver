package com.gaskony.camera.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.common.DeviceStatus;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
import com.gaskony.camera.gateway.device.CameraDevice;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.servlet.CorsManager;
import com.gaskony.camera.gateway.servlet.RateLimiter;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import com.gaskony.camera.gateway.util.ValidationUtil;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles snapshot image requests for Camera devices.
 * URL: /data/camera-driver/snapshot?device=DeviceName&profile=ProfileToken
 */
public class SnapshotHandler extends BaseHandler {

    private static final int MAX_CONCURRENT_SNAPSHOTS = 50;
    private static final AtomicInteger activeSnapshots = new AtomicInteger(0);

    public SnapshotHandler(GatewayContext context,
                           CameraExtensionPoint cameraExtensionPoint,
                           Go2RtcManager go2RtcManager,
                           AuthenticationManager authManager,
                           String moduleVersion) {
        super(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    public static int getActiveSnapshots() { return activeSnapshots.get(); }
    public static int getMaxConcurrentSnapshots() { return MAX_CONCURRENT_SNAPSHOTS; }

    public static void resetCounters() {
        activeSnapshots.set(0);
    }

    public Object handle(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Snapshot request received");

        // P3-CD: shared AccessControl pattern.
        if (!requireAuthenticated(requestContext, response)) {
            return null;
        }

        if (!RateLimiter.checkRateLimit(requestContext.getRequest(), response)) {
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
            String deviceName = requestContext.getParameter("device");
            String profileToken = requestContext.getParameter("profile");

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

            String deviceStatus = device.getStatus();
            if (!DeviceStatus.isActive(deviceStatus)) {
                response.sendError(503, "Device is not connected: " + deviceStatus);
                return null;
            }

            byte[] snapshotBytes = null;

            // Try ONVIF snapshot first
            if (device.isOnvifAvailable() && device.getClient() != null) {
                if (profileToken == null || profileToken.trim().isEmpty()) {
                    profileToken = device.getDefaultProfileToken();
                    if (profileToken == null) {
                        response.sendError(400, "No media profiles available for ONVIF device");
                        return null;
                    }
                }
                if (!ValidationUtil.isValidProfileToken(profileToken)) {
                    response.sendError(400, "Invalid profile token format");
                    return null;
                }

                try {
                    snapshotBytes = device.getClient().getSnapshot(profileToken);
                } catch (IOException e) {
                    logger.debug("ONVIF snapshot failed for {}: {}", deviceName, e.getMessage());
                }
            }

            // Try direct HTTP snapshot (GenericCameraClient)
            if (snapshotBytes == null && device.getCameraClient() != null) {
                try {
                    snapshotBytes = device.getCameraClient().fetchSnapshot();
                } catch (IOException e) {
                    logger.debug("Direct snapshot unavailable for {}: {}", deviceName, e.getMessage());
                }
            }

            // Fallback: go2rtc frame.jpeg
            if (snapshotBytes == null && device.isGo2RtcStreamRegistered()
                    && go2RtcManager != null && go2RtcManager.isAvailable()) {
                snapshotBytes = go2RtcManager.fetchSnapshot(deviceName);
                if (snapshotBytes != null) {
                    logger.debug("Snapshot via go2rtc for device: {}", deviceName);
                }
            }

            if (snapshotBytes == null || snapshotBytes.length == 0) {
                response.sendError(500, "No snapshot source available. Configure a snapshot URL or ensure go2rtc with ffmpeg is running.");
                return null;
            }

            // Validate JPEG content
            boolean isJpeg = snapshotBytes.length >= 3 &&
                            (snapshotBytes[0] & 0xFF) == 0xFF &&
                            (snapshotBytes[1] & 0xFF) == 0xD8 &&
                            (snapshotBytes[2] & 0xFF) == 0xFF;

            String contentStart = new String(snapshotBytes, 0, Math.min(100, snapshotBytes.length), java.nio.charset.StandardCharsets.UTF_8);
            boolean isHtml = contentStart.toLowerCase().contains("<!doctype") ||
                            contentStart.toLowerCase().contains("<html");

            if (!isJpeg || isHtml) {
                logger.error("Camera returned {} instead of JPEG for device: {}",
                    isHtml ? "HTML content" : "non-JPEG content", deviceName);
                response.sendError(500, "Camera returned non-JPEG content instead of snapshot image");
                return null;
            }

            // Send response
            response.setContentType("image/jpeg");
            response.setContentLength(snapshotBytes.length);
            String origin = requestContext.getRequest().getHeader("Origin");
            CorsManager.setStreamingHeaders(response, origin, requestContext.getRequest());

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
