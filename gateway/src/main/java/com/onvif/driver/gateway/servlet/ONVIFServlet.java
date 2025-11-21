package com.onvif.driver.gateway.servlet;

import com.onvif.driver.gateway.device.ONVIFDevice;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Direct servlet approach for ONVIF snapshot and streaming.
 * Registered directly with the gateway instead of using route mounting.
 */
public class ONVIFServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(ONVIFServlet.class);
    private final ONVIFDeviceExtensionPoint deviceExtensionPoint;

    public ONVIFServlet(ONVIFDeviceExtensionPoint deviceExtensionPoint) {
        this.deviceExtensionPoint = deviceExtensionPoint;
        logger.info("========== ONVIFServlet constructed! ==========");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        logger.info("========================================");
        logger.info("========== ONVIFServlet.doGet() CALLED! ==========");
        logger.info("========================================");
        logger.info("Request URL: " + req.getRequestURL());
        logger.info("Request URI: " + req.getRequestURI());
        logger.info("Context Path: " + req.getContextPath());
        logger.info("Servlet Path: " + req.getServletPath());
        logger.info("Path Info: " + req.getPathInfo());
        logger.info("Query String: " + req.getQueryString());

        String pathInfo = req.getPathInfo();

        if (pathInfo == null || pathInfo.equals("/")) {
            handleInfo(req, resp);
        } else if (pathInfo.startsWith("/test")) {
            handleTest(req, resp);
        } else if (pathInfo.startsWith("/snapshot")) {
            handleSnapshot(req, resp);
        } else if (pathInfo.startsWith("/stream")) {
            handleStream(req, resp);
        } else {
            resp.sendError(404, "Unknown endpoint: " + pathInfo);
        }
    }

    private void handleInfo(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        logger.info("========== handleInfo() called ==========");
        resp.setContentType("application/json");
        resp.setStatus(200);
        String json = "{\"status\":\"success\",\"message\":\"ONVIF Driver servlet is running!\",\"endpoints\":[\"/test\",\"/snapshot\",\"/stream\"]}";
        resp.getWriter().write(json);
        logger.info("========== Info response sent ==========");
    }

    private void handleTest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        logger.info("========== handleTest() called ==========");
        resp.setContentType("application/json");
        resp.setStatus(200);
        String json = "{\"status\":\"success\",\"message\":\"ONVIF Driver test endpoint works!\",\"timestamp\":" + System.currentTimeMillis() + "}";
        resp.getWriter().write(json);
        logger.info("========== Test response sent ==========");
    }

    private void handleSnapshot(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        logger.info("========== handleSnapshot() called ==========");

        String deviceName = req.getParameter("device");
        String profileToken = req.getParameter("profile");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.sendError(400, "Missing required parameter: device");
            return;
        }

        if (profileToken == null || profileToken.trim().isEmpty()) {
            resp.sendError(400, "Missing required parameter: profile");
            return;
        }

        // Validate parameter format
        if (!deviceName.matches("[a-zA-Z0-9_-]+")) {
            logger.warn("Invalid device name format: {}", deviceName);
            resp.sendError(400, "Invalid device name format");
            return;
        }

        if (!profileToken.matches("[a-zA-Z0-9_-]+")) {
            logger.warn("Invalid profile token format: {}", profileToken);
            resp.sendError(400, "Invalid profile token format");
            return;
        }

        logger.info("Snapshot request - device: {}, profile: {}", deviceName, profileToken);

        // Get device
        ONVIFDevice device = deviceExtensionPoint.getDevice(deviceName);
        if (device == null) {
            logger.warn("Device not found: {}", deviceName);
            resp.sendError(404, "Device not found: " + deviceName);
            return;
        }

        // Check device status
        String deviceStatus = device.getStatus();
        if (!"Running".equals(deviceStatus) && !"Connected".equals(deviceStatus)) {
            logger.warn("Device not in running state: {} - status: {}", deviceName, deviceStatus);
            resp.sendError(503, "Device is not connected: " + deviceStatus);
            return;
        }

        // Get snapshot
        byte[] snapshotBytes;
        try {
            snapshotBytes = device.getClient().getSnapshot(profileToken);
        } catch (IOException e) {
            logger.error("Failed to get snapshot from device {}, profile {}: {}",
                deviceName, profileToken, e.getMessage());
            resp.sendError(500, "Failed to retrieve snapshot from camera");
            return;
        }

        // Send response
        resp.setContentType("image/jpeg");
        resp.setContentLength(snapshotBytes.length);
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        resp.setHeader("Expires", "0");
        resp.setHeader("Access-Control-Allow-Origin", "*");

        resp.getOutputStream().write(snapshotBytes);
        logger.info("Snapshot delivered - {} bytes", snapshotBytes.length);
    }

    private void handleStream(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        logger.info("========== handleStream() called ==========");
        resp.sendError(501, "Streaming not yet implemented in servlet version");
    }
}
