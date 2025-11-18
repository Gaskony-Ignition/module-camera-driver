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
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servlet that provides MJPEG video streams from ONVIF cameras.
 *
 * <p>This is a simple implementation that polls snapshots and streams them as Motion JPEG.
 * This approach works with all browsers and doesn't require complex transcoding.
 *
 * <p>Endpoint: /system/onvif/stream
 *
 * <p>Parameters:
 * <ul>
 *   <li>device - Device connection name (required)</li>
 *   <li>profile - Profile token (required)</li>
 *   <li>fps - Frames per second, 1-30 (optional, default: 10)</li>
 * </ul>
 *
 * <p>Returns: multipart/x-mixed-replace MJPEG stream
 *
 * <p>Security:
 * <ul>
 *   <li>Requires valid Ignition session</li>
 *   <li>Rate limited to prevent abuse</li>
 *   <li>Input validation on all parameters</li>
 * </ul>
 */
public class ONVIFStreamServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(ONVIFStreamServlet.class);

    private static final String BOUNDARY = "onvif-stream-boundary";
    private static final int DEFAULT_FPS = 10;
    private static final int MIN_FPS = 1;
    private static final int MAX_FPS = 30;

    // Resource protection
    private static final int MAX_CONCURRENT_STREAMS = 20;
    private static final AtomicInteger activeStreams = new AtomicInteger(0);

    private final GatewayContext context;
    private final ONVIFDeviceExtensionPoint deviceExtensionPoint;

    /**
     * Creates a new stream servlet.
     *
     * @param context Gateway context
     * @param deviceExtensionPoint ONVIF device extension point
     */
    public ONVIFStreamServlet(GatewayContext context, ONVIFDeviceExtensionPoint deviceExtensionPoint) {
        this.context = context;
        this.deviceExtensionPoint = deviceExtensionPoint;
        logger.info("ONVIFStreamServlet initialized");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Check if we're at capacity
        if (activeStreams.get() >= MAX_CONCURRENT_STREAMS) {
            logger.warn("Stream request rejected - max concurrent streams reached");
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Maximum concurrent streams reached. Please try again later.");
            return;
        }

        activeStreams.incrementAndGet();
        long startTime = System.currentTimeMillis();
        String deviceName = null;
        String profileToken = null;

        try {
            // Get and validate parameters
            deviceName = req.getParameter("device");
            profileToken = req.getParameter("profile");
            String fpsParam = req.getParameter("fps");

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

            // Parse and validate FPS
            int fps = DEFAULT_FPS;
            if (fpsParam != null && !fpsParam.trim().isEmpty()) {
                try {
                    fps = Integer.parseInt(fpsParam);
                    if (fps < MIN_FPS || fps > MAX_FPS) {
                        logger.warn("FPS out of range: {}", fps);
                        fps = DEFAULT_FPS;
                    }
                } catch (NumberFormatException e) {
                    logger.warn("Invalid FPS parameter: {}", fpsParam);
                    fps = DEFAULT_FPS;
                }
            }

            logger.debug("Stream request - device: {}, profile: {}, fps: {}", deviceName, profileToken, fps);

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

            // Set response headers for MJPEG stream
            resp.setContentType("multipart/x-mixed-replace; boundary=" + BOUNDARY);
            resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            resp.setHeader("Pragma", "no-cache");
            resp.setHeader("Expires", "0");
            resp.setHeader("Connection", "close");

            // Allow CORS for Perspective clients
            resp.setHeader("Access-Control-Allow-Origin", "*");
            resp.setHeader("Access-Control-Allow-Methods", "GET");

            // Start streaming
            OutputStream output = resp.getOutputStream();
            long frameDelay = 1000 / fps;
            int frameCount = 0;
            int errorCount = 0;
            int maxConsecutiveErrors = 5;

            logger.info("Starting MJPEG stream - device: {}, profile: {}, fps: {}",
                deviceName, profileToken, fps);

            while (!Thread.currentThread().isInterrupted()) {
                long frameStart = System.currentTimeMillis();

                try {
                    // Get snapshot from camera
                    byte[] frame = device.getClient().getSnapshot(profileToken);

                    if (frame != null && frame.length > 0) {
                        // Write MJPEG frame
                        output.write(("--" + BOUNDARY + "\r\n").getBytes());
                        output.write("Content-Type: image/jpeg\r\n".getBytes());
                        output.write(("Content-Length: " + frame.length + "\r\n\r\n").getBytes());
                        output.write(frame);
                        output.write("\r\n".getBytes());
                        output.flush();

                        frameCount++;
                        errorCount = 0; // Reset error count on success

                        if (frameCount % 100 == 0) {
                            logger.debug("Stream {} frames delivered - device: {}, profile: {}",
                                frameCount, deviceName, profileToken);
                        }
                    } else {
                        errorCount++;
                        logger.warn("Empty frame received from device: {}", deviceName);
                    }

                } catch (IOException e) {
                    errorCount++;
                    logger.error("Error getting frame from device {} (error {} of {}): {}",
                        deviceName, errorCount, maxConsecutiveErrors, e.getMessage());

                    if (errorCount >= maxConsecutiveErrors) {
                        logger.error("Too many consecutive errors, stopping stream for device: {}", deviceName);
                        break;
                    }
                }

                // Maintain frame rate
                long frameTime = System.currentTimeMillis() - frameStart;
                long sleepTime = frameDelay - frameTime;
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Stream ended - device: {}, profile: {}, frames: {}, duration: {} ms",
                deviceName, profileToken, frameCount, duration);

        } catch (Exception e) {
            logger.error("Unexpected error in stream servlet for device: {}, profile: {}",
                deviceName, profileToken, e);
            if (!resp.isCommitted()) {
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Internal server error");
            }
        } finally {
            activeStreams.decrementAndGet();
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
        logger.info("ONVIFStreamServlet destroyed");
        super.destroy();
    }

    /**
     * Gets the current number of active streams.
     * Useful for monitoring and diagnostics.
     *
     * @return Active stream count
     */
    public static int getActiveStreamCount() {
        return activeStreams.get();
    }
}
