package com.onvif.driver.gateway.servlet;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.gateway.device.ONVIFDevice;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servlet that provides snapshot images from ONVIF cameras.
 *
 * <p>Endpoint: /system/onvif/snapshot
 *
 * <p>Parameters:
 * <ul>
 *   <li>device - Device connection name (required)</li>
 *   <li>profile - Profile token (required)</li>
 * </ul>
 *
 * <p>Returns: JPEG image
 *
 * <p>Security:
 * <ul>
 *   <li>Requires valid Ignition session</li>
 *   <li>Rate limited to prevent abuse</li>
 *   <li>Input validation on all parameters</li>
 * </ul>
 */
public class ONVIFSnapshotServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(ONVIFSnapshotServlet.class);

    // Resource protection
    private static final int MAX_CONCURRENT_REQUESTS = 50;
    private static final AtomicInteger activeRequests = new AtomicInteger(0);

    private final GatewayContext context;
    private final ONVIFDeviceExtensionPoint deviceExtensionPoint;

    /**
     * Creates a new snapshot servlet.
     *
     * @param context Gateway context
     * @param deviceExtensionPoint ONVIF device extension point
     */
    public ONVIFSnapshotServlet(GatewayContext context, ONVIFDeviceExtensionPoint deviceExtensionPoint) {
        this.context = context;
        this.deviceExtensionPoint = deviceExtensionPoint;
        logger.info("ONVIFSnapshotServlet initialized");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Check if we're at capacity
        if (activeRequests.get() >= MAX_CONCURRENT_REQUESTS) {
            logger.warn("Snapshot request rejected - max concurrent requests reached");
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Maximum concurrent snapshot requests reached. Please try again later.");
            return;
        }

        activeRequests.incrementAndGet();
        long startTime = System.currentTimeMillis();

        try {
            // Get and validate parameters
            String deviceName = req.getParameter("device");
            String profileToken = req.getParameter("profile");

            if (deviceName == null || deviceName.trim().isEmpty()) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing required parameter: device");
                return;
            }

            if (profileToken == null || profileToken.trim().isEmpty()) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing required parameter: profile");
                return;
            }

            // Validate device name format (alphanumeric, dash, underscore only)
            if (!deviceName.matches("[a-zA-Z0-9_-]+")) {
                logger.warn("Invalid device name format: {}", deviceName);
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid device name format");
                return;
            }

            // Validate profile token format
            if (!profileToken.matches("[a-zA-Z0-9_-]+")) {
                logger.warn("Invalid profile token format: {}", profileToken);
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid profile token format");
                return;
            }

            logger.debug("Snapshot request - device: {}, profile: {}", deviceName, profileToken);

            // Get device from device manager
            ONVIFDevice device = deviceExtensionPoint.getDevice(deviceName);

            if (device == null) {
                logger.warn("Device not found: {}", deviceName);
                resp.sendError(HttpServletResponse.SC_NOT_FOUND,
                    String.format("Device not found: %s", deviceName));
                return;
            }

            // Check device status
            String deviceStatus = device.getStatus();
            if (!"Running".equals(deviceStatus) && !"Connected".equals(deviceStatus)) {
                logger.warn("Device not in running state: {} - status: {}", deviceName, deviceStatus);
                resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    String.format("Device is not connected: %s", deviceStatus));
                return;
            }

            // Get snapshot from device
            byte[] snapshotBytes;
            try {
                snapshotBytes = device.getClient().getSnapshot(profileToken);
            } catch (IOException e) {
                logger.error("Failed to get snapshot from device {}, profile {}: {}",
                    deviceName, profileToken, e.getMessage());
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to retrieve snapshot from camera");
                return;
            }

            // Set response headers
            resp.setContentType("image/jpeg");
            resp.setContentLength(snapshotBytes.length);
            resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            resp.setHeader("Pragma", "no-cache");
            resp.setHeader("Expires", "0");

            // Allow CORS for Perspective clients
            resp.setHeader("Access-Control-Allow-Origin", "*");
            resp.setHeader("Access-Control-Allow-Methods", "GET");

            // Write image to response
            resp.getOutputStream().write(snapshotBytes);
            resp.getOutputStream().flush();

            long duration = System.currentTimeMillis() - startTime;
            logger.debug("Snapshot delivered - device: {}, profile: {}, size: {} bytes, duration: {} ms",
                deviceName, profileToken, snapshotBytes.length, duration);

        } catch (Exception e) {
            logger.error("Unexpected error in snapshot servlet", e);
            if (!resp.isCommitted()) {
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Internal server error");
            }
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        // Handle CORS preflight requests
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    public void destroy() {
        logger.info("ONVIFSnapshotServlet destroyed");
        super.destroy();
    }

    /**
     * Gets the current number of active requests.
     * Useful for monitoring and diagnostics.
     *
     * @return Active request count
     */
    public static int getActiveRequestCount() {
        return activeRequests.get();
    }
}
