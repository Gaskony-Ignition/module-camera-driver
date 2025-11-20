package com.onvif.driver.gateway.servlet;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.gateway.device.ONVIFDevice;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Route handlers for ONVIF snapshot and streaming endpoints.
 * Registers routes under /main/data/onvif-driver/*
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
        // Mount snapshot endpoint at /main/data/onvif-driver/snapshot
        routes.newRoute("/snapshot")
            .handler(this::handleSnapshot)
            .type(RouteGroup.TYPE_OCTET_STREAM)  // Binary data (handler sets image/jpeg)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)  // Public access
            .mount();

        // Mount stream endpoint at /main/data/onvif-driver/stream
        routes.newRoute("/stream")
            .handler(this::handleStream)
            .type(RouteGroup.TYPE_OCTET_STREAM)  // Binary data (handler sets multipart/x-mixed-replace)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)  // Public access
            .mount();

        logger.info("Mounted ONVIF routes: /snapshot and /stream");
    }

    /**
     * Handles snapshot requests.
     * URL: /main/data/onvif-driver/snapshot?device=DeviceName&profile=ProfileToken
     */
    private Object handleSnapshot(RequestContext context, HttpServletResponse response) throws Exception {
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

            if (deviceName == null || deviceName.trim().isEmpty()) {
                response.sendError(400, "Missing required parameter: device");
                return null;
            }

            if (profileToken == null || profileToken.trim().isEmpty()) {
                response.sendError(400, "Missing required parameter: profile");
                return null;
            }

            // Validate parameter format
            if (!deviceName.matches("[a-zA-Z0-9_-]+")) {
                logger.warn("Invalid device name format: {}", deviceName);
                response.sendError(400, "Invalid device name format");
                return null;
            }

            if (!profileToken.matches("[a-zA-Z0-9_-]+")) {
                logger.warn("Invalid profile token format: {}", profileToken);
                response.sendError(400, "Invalid profile token format");
                return null;
            }

            logger.debug("Snapshot request - device: {}, profile: {}", deviceName, profileToken);

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

            // Get snapshot
            byte[] snapshotBytes;
            try {
                snapshotBytes = device.getClient().getSnapshot(profileToken);
            } catch (IOException e) {
                logger.error("Failed to get snapshot from device {}, profile {}: {}",
                    deviceName, profileToken, e.getMessage());
                response.sendError(500, "Failed to retrieve snapshot from camera");
                return null;
            }

            // Send response
            response.setContentType("image/jpeg");
            response.setContentLength(snapshotBytes.length);
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
            response.setHeader("Access-Control-Allow-Origin", "*");

            response.getOutputStream().write(snapshotBytes);

            long duration = System.currentTimeMillis() - startTime;
            logger.debug("Snapshot delivered - device: {}, profile: {}, size: {} bytes, duration: {} ms",
                deviceName, profileToken, snapshotBytes.length, duration);

        } finally {
            activeSnapshots.decrementAndGet();
        }

        return null;
    }

    /**
     * Handles MJPEG stream requests.
     * URL: /main/data/onvif-driver/stream?device=DeviceName&profile=ProfileToken&fps=10
     */
    private Object handleStream(RequestContext context, HttpServletResponse response) throws Exception {
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
            if (!deviceName.matches("[a-zA-Z0-9_-]+")) {
                logger.warn("Invalid device name format: {}", deviceName);
                response.sendError(400, "Invalid device name format");
                return null;
            }

            if (!profileToken.matches("[a-zA-Z0-9_-]+")) {
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
            response.setHeader("Access-Control-Allow-Origin", "*");

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
}
