package com.onvif.driver.gateway.servlet;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.gateway.device.ONVIFDevice;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.util.ValidationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Route handlers for ONVIF snapshot and streaming endpoints.
 * Registers routes under /data/onvif-driver/*
 *
 * IMPORTANT: Actual URLs are /data/{alias}/* NOT /main/data/{alias}/*
 * Example: http://gateway:8088/data/onvif-driver/snapshot?device=SideCamera&profile=000
 */
public class ONVIFRoutes {

    private static final Logger logger = LoggerFactory.getLogger(ONVIFRoutes.class);
    private static final String BOUNDARY = "onvif-stream-boundary";

    private final GatewayContext context;
    private final ONVIFDeviceExtensionPoint deviceExtensionPoint;

    // Resource protection
    private static final int MAX_CONCURRENT_SNAPSHOTS = 50;
    private static final int MAX_CONCURRENT_STREAMS = 20;
    private static final AtomicInteger activeSnapshots = new AtomicInteger(0);
    private static final AtomicInteger activeStreams = new AtomicInteger(0);

    public ONVIFRoutes(GatewayContext context, ONVIFDeviceExtensionPoint deviceExtensionPoint) {
        this.context = context;
        this.deviceExtensionPoint = deviceExtensionPoint;
    }

    /**
     * Mounts the ONVIF routes on the provided RouteGroup.
     */
    public void mountRoutes(RouteGroup routes) {
        logger.info("========== mountRoutes() called with RouteGroup: " + routes + " ==========");
        logger.info("========== RouteGroup class: " + routes.getClass().getName() + " ==========");

        // DIAGNOSTIC: Mount a simple test route first
        logger.info("========== Mounting TEST route at /test ==========");
        routes.newRoute("/test")
            .handler(this::handleTest)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();
        logger.info("========== TEST route mounted ==========");

        // Mount snapshot endpoint at /main/data/onvif-driver/snapshot
        logger.info("========== Mounting SNAPSHOT route at /snapshot ==========");
        routes.newRoute("/snapshot")
            .handler(this::handleSnapshot)
            .type(RouteGroup.TYPE_OCTET_STREAM)  // Binary data (handler sets image/jpeg)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)  // TODO v2.1.0: Implement custom authentication
            .mount();
        logger.info("========== SNAPSHOT route mounted ==========");

        // Mount stream endpoint at /main/data/onvif-driver/stream
        logger.info("========== Mounting STREAM route at /stream ==========");
        routes.newRoute("/stream")
            .handler(this::handleStream)
            .type(RouteGroup.TYPE_OCTET_STREAM)  // Binary data (handler sets multipart/x-mixed-replace)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)  // TODO v2.1.0: Implement custom authentication
            .mount();
        logger.info("========== STREAM route mounted ==========");

        logger.info("Mounted ONVIF routes: /test, /snapshot and /stream");
    }

    /**
     * DIAGNOSTIC: Simple test handler to verify routing works at all.
     * URL: http://gateway:8088/data/onvif-driver/test
     */
    private Object handleTest(RequestContext context, HttpServletResponse response) throws Exception {
        logger.info("========================================");
        logger.info("========== handleTest() CALLED! ==========");
        logger.info("========== TEST ROUTE IS WORKING! ==========");
        logger.info("========================================");
        logger.info("Request URL: " + context.getRequest().getRequestURL());
        logger.info("Request URI: " + context.getRequest().getRequestURI());
        logger.info("Context Path: " + context.getRequest().getContextPath());
        logger.info("Servlet Path: " + context.getRequest().getServletPath());
        logger.info("Path Info: " + context.getRequest().getPathInfo());
        logger.info("Query String: " + context.getRequest().getQueryString());

        response.setContentType("application/json");
        response.setStatus(200);
        String jsonResponse = "{\"status\":\"success\",\"message\":\"ONVIF Driver test route is working!\",\"timestamp\":" + System.currentTimeMillis() + "}";
        response.getWriter().write(jsonResponse);

        logger.info("========== Test response sent successfully ==========");
        return null;
    }

    /**
     * Handles snapshot requests.
     * URL: http://gateway:8088/data/onvif-driver/snapshot?device=DeviceName&profile=ProfileToken
     */
    private Object handleSnapshot(RequestContext context, HttpServletResponse response) throws Exception {
        logger.info("========== handleSnapshot() CALLED! ==========");
        logger.info("Request URL: " + context.getRequest().getRequestURL());
        logger.info("Query String: " + context.getRequest().getQueryString());

        // Check if deviceExtensionPoint is available
        if (deviceExtensionPoint == null) {
            logger.error("========== ERROR: deviceExtensionPoint is NULL in handler! ==========");
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
            String deviceName = context.getParameter("device");
            String profileToken = context.getParameter("profile");
            logger.info(">>> Step 1: Got parameters - device={}, profile={}", deviceName, profileToken);

            if (deviceName == null || deviceName.trim().isEmpty()) {
                logger.info(">>> ERROR: Missing device parameter");
                response.sendError(400, "Missing required parameter: device");
                return null;
            }

            if (profileToken == null || profileToken.trim().isEmpty()) {
                logger.info(">>> ERROR: Missing profile parameter");
                response.sendError(400, "Missing required parameter: profile");
                return null;
            }
            logger.info(">>> Step 2: Parameters present");

            // Validate parameter format
            if (!ValidationUtil.isValidDeviceName(deviceName)) {
                logger.warn("Invalid device name format: {}", deviceName);
                response.sendError(400, "Invalid device name format");
                return null;
            }

            if (!ValidationUtil.isValidProfileToken(profileToken)) {
                logger.warn("Invalid profile token format: {}", profileToken);
                response.sendError(400, "Invalid profile token format");
                return null;
            }
            logger.info(">>> Step 3: Parameters validated");

            // Get device
            logger.info(">>> Step 4: Looking up device: {}", deviceName);
            ONVIFDevice device = deviceExtensionPoint.getDevice(deviceName);
            if (device == null) {
                logger.warn("Device not found: {}", deviceName);
                response.sendError(404, "Device not found: " + deviceName);
                return null;
            }
            logger.info(">>> Step 5: Device found: {}", device);

            // Check device status
            String deviceStatus = device.getStatus();
            logger.info(">>> Step 6: Device status: {}", deviceStatus);
            if (!"Running".equals(deviceStatus) && !"Connected".equals(deviceStatus)) {
                logger.warn("Device not in running state: {} - status: {}", deviceName, deviceStatus);
                response.sendError(503, "Device is not connected: " + deviceStatus);
                return null;
            }
            logger.info(">>> Step 7: Device status OK");

            // Get snapshot
            logger.info(">>> Step 8: Requesting snapshot from device for profile: {}", profileToken);
            byte[] snapshotBytes;
            try {
                snapshotBytes = device.getClient().getSnapshot(profileToken);
                logger.info(">>> Step 9: Snapshot received, size: {} bytes", snapshotBytes.length);

                // CRITICAL: Validate that we actually received a JPEG image, not HTML or other content
                if (snapshotBytes.length > 0) {
                    // Check for JPEG magic bytes (FF D8 FF)
                    boolean isJpeg = snapshotBytes.length >= 3 &&
                                    (snapshotBytes[0] & 0xFF) == 0xFF &&
                                    (snapshotBytes[1] & 0xFF) == 0xD8 &&
                                    (snapshotBytes[2] & 0xFF) == 0xFF;

                    // Check for HTML content
                    String contentStart = new String(snapshotBytes, 0, Math.min(100, snapshotBytes.length));
                    boolean isHtml = contentStart.toLowerCase().contains("<!doctype") ||
                                    contentStart.toLowerCase().contains("<html");

                    logger.info(">>> Step 9a: Content validation - isJPEG: {}, isHTML: {}", isJpeg, isHtml);
                    logger.info(">>> Step 9b: First 100 bytes: {}", contentStart);

                    if (!isJpeg || isHtml) {
                        logger.error("========== CAMERA DOES NOT COMPLY WITH ONVIF SPECIFICATION ==========");
                        logger.error("Camera returned {} instead of JPEG image", isHtml ? "HTML login page" : "non-JPEG content");
                        logger.error("Camera: {}, Profile: {}", deviceName, profileToken);
                        logger.error("Snapshot URL: {}", device.getClient().getSnapshotUri(profileToken));
                        logger.error("");
                        logger.error("This camera does not properly implement the ONVIF GetSnapshotUri specification.");
                        logger.error("The snapshot URL requires authentication methods not supported by ONVIF standard.");
                        logger.error("");
                        logger.error("RESOLUTION:");
                        logger.error("1. Use the RTSP StreamUri from OPC-UA tags: [default]OPC UA/{}/Profiles/{}/StreamUri", deviceName, profileToken);
                        logger.error("2. Contact camera manufacturer about ONVIF compliance");
                        logger.error("3. Consider using a camera with better ONVIF support (Axis, Hikvision, Dahua)");

                        response.sendError(500, "Camera does not properly implement ONVIF snapshot specification. " +
                            "Use RTSP StreamUri from OPC-UA tags instead: [default]OPC UA/" + deviceName + "/Profiles/" + profileToken + "/StreamUri");
                        return null;
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to get snapshot from device {}, profile {}: {}",
                    deviceName, profileToken, e.getMessage());
                logger.error("Exception details:", e);
                response.sendError(500, "Failed to retrieve snapshot from camera");
                return null;
            }

            // Send response
            logger.info(">>> Step 10: Setting response headers");
            response.setContentType("image/jpeg");
            response.setContentLength(snapshotBytes.length);
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
            // CORS - only allow requests from authenticated Ignition sessions
            // In production, this should be restricted to specific origins
            String origin = context.getRequest().getHeader("Origin");
            if (origin != null && isAllowedOrigin(origin)) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Access-Control-Allow-Credentials", "true");
            }

            logger.info(">>> Step 11: Writing {} bytes to response", snapshotBytes.length);
            response.getOutputStream().write(snapshotBytes);

            long duration = System.currentTimeMillis() - startTime;
            logger.info(">>> Step 12: SUCCESS! Snapshot delivered - device: {}, profile: {}, size: {} bytes, duration: {} ms",
                deviceName, profileToken, snapshotBytes.length, duration);

        } finally {
            activeSnapshots.decrementAndGet();
        }

        return null;
    }

    /**
     * Handles MJPEG stream requests.
     * URL: http://gateway:8088/data/onvif-driver/stream?device=DeviceName&profile=ProfileToken&fps=10
     */
    private Object handleStream(RequestContext context, HttpServletResponse response) throws Exception {
        logger.info("========== handleStream() CALLED! ==========");
        logger.info("Request URL: " + context.getRequest().getRequestURL());
        logger.info("Query String: " + context.getRequest().getQueryString());

        // Check if deviceExtensionPoint is available
        if (deviceExtensionPoint == null) {
            logger.error("========== ERROR: deviceExtensionPoint is NULL in handler! ==========");
            response.sendError(500, "Device extension point not initialized");
            return null;
        }

        if (activeStreams.get() >= MAX_CONCURRENT_STREAMS) {
            logger.warn("Stream request rejected - max concurrent streams reached");
            response.sendError(503, "Maximum concurrent streams reached");
            return null;
        }

        activeStreams.incrementAndGet();
        long startTime = System.currentTimeMillis();
        String deviceName = null;
        String profileToken = null;

        try {
            // Get parameters
            deviceName = context.getParameter("device");
            profileToken = context.getParameter("profile");
            String fpsParam = context.getParameter("fps");

            if (deviceName == null || deviceName.trim().isEmpty()) {
                response.sendError(400, "Missing required parameter: device");
                return null;
            }

            if (profileToken == null || profileToken.trim().isEmpty()) {
                response.sendError(400, "Missing required parameter: profile");
                return null;
            }

            // Validate parameter format
            if (!ValidationUtil.isValidDeviceName(deviceName)) {
                logger.warn("Invalid device name format: {}", deviceName);
                response.sendError(400, "Invalid device name format");
                return null;
            }

            if (!ValidationUtil.isValidProfileToken(profileToken)) {
                logger.warn("Invalid profile token format: {}", profileToken);
                response.sendError(400, "Invalid profile token format");
                return null;
            }

            // Parse FPS
            int fps = 10;
            if (fpsParam != null && !fpsParam.trim().isEmpty()) {
                try {
                    fps = Integer.parseInt(fpsParam);
                    if (fps < 1) fps = 1;
                    if (fps > 30) fps = 30;
                } catch (NumberFormatException e) {
                    logger.warn("Invalid FPS parameter: {}", fpsParam);
                }
            }

            logger.debug("Stream request - device: {}, profile: {}, fps: {}", deviceName, profileToken, fps);

            // Get device
            ONVIFDevice device = deviceExtensionPoint.getDevice(deviceName);
            if (device == null) {
                logger.warn("Device not found: {}", deviceName);
                response.sendError(404, "Device not found: " + deviceName);
                return null;
            }

            // Check device status
            String deviceStatus = device.getStatus();
            if (!"Running".equals(deviceStatus) && !"Connected".equals(deviceStatus)) {
                logger.warn("Device not in running state: {} - status: {}", deviceName, deviceStatus);
                response.sendError(503, "Device is not connected: " + deviceStatus);
                return null;
            }

            // Set response headers for MJPEG stream
            response.setContentType("multipart/x-mixed-replace; boundary=" + BOUNDARY);
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
            response.setHeader("Connection", "close");
            // CORS - only allow requests from authenticated Ignition sessions
            // In production, this should be restricted to specific origins
            String origin = context.getRequest().getHeader("Origin");
            if (origin != null && isAllowedOrigin(origin)) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Access-Control-Allow-Credentials", "true");
            }

            // Start streaming
            OutputStream output = response.getOutputStream();
            long frameDelay = 1000 / fps;
            int frameCount = 0;
            int errorCount = 0;
            int maxConsecutiveErrors = 5;

            logger.info("Starting MJPEG stream - device: {}, profile: {}, fps: {}", deviceName, profileToken, fps);

            while (!Thread.currentThread().isInterrupted()) {
                long frameStart = System.currentTimeMillis();

                try {
                    byte[] frame = device.getClient().getSnapshot(profileToken);

                    if (frame != null && frame.length > 0) {
                        output.write(("--" + BOUNDARY + "\r\n").getBytes());
                        output.write("Content-Type: image/jpeg\r\n".getBytes());
                        output.write(("Content-Length: " + frame.length + "\r\n\r\n").getBytes());
                        output.write(frame);
                        output.write("\r\n".getBytes());
                        output.flush();

                        frameCount++;
                        errorCount = 0;

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
            logger.error("Unexpected error in stream handler for device: {}, profile: {}",
                deviceName, profileToken, e);
            if (!response.isCommitted()) {
                response.sendError(500, "Internal server error");
            }
        } finally {
            activeStreams.decrementAndGet();
        }

        return null;
    }

    /**
     * Validates if the given origin is allowed for CORS requests.
     * In production, this should be configured to only allow specific origins.
     *
     * @param origin The Origin header value from the request
     * @return true if the origin is allowed, false otherwise
     */
    private boolean isAllowedOrigin(String origin) {
        // For now, allow localhost and any Ignition Gateway origin
        // In production, this should be configurable and more restrictive
        if (origin == null || origin.isEmpty()) {
            return false;
        }

        // Allow localhost and 127.0.0.1 for development
        if (origin.contains("localhost") || origin.contains("127.0.0.1")) {
            return true;
        }

        // TODO: Make this configurable via module settings
        // For now, be permissive for Ignition internal requests
        return origin.startsWith("http://") || origin.startsWith("https://");
    }
}
