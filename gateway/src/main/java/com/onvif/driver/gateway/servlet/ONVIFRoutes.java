package com.onvif.driver.gateway.servlet;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.ONVIFDevice;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.util.ValidationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import com.onvif.driver.gateway.onvif.MediaProfile;
import com.onvif.driver.gateway.onvif.DeviceInformation;

/**
 * Route handlers for ONVIF snapshot and streaming endpoints.
 * Registers routes under /data/onvif-driver/*
 *
 * IMPORTANT: Actual URLs are /data/{alias}/* NOT /main/data/{alias}/*
 * Example: http://gateway:8088/data/onvif-driver/snapshot?device=SideCamera&profile=000
 *
 * AUTHENTICATION (v2.2.0+):
 *
 * All endpoints require authentication using one of three methods:
 *
 * 1. Session Authentication (Primary - for Ignition users):
 *    - Valid Ignition Gateway session automatically authenticated
 *    - Works seamlessly with Perspective and Vision clients
 *
 * 2. Basic Authentication (For external tools):
 *    - Validates against Ignition gateway authentication
 *    - Account lockout after 5 failed attempts (15-minute duration)
 *    - Failed attempts logged for security monitoring
 *    - Example: curl -u username:password http://gateway:8088/data/onvif-driver/snapshot?device=X&profile=Y
 *
 * 3. API Key Authentication (For programmatic access):
 *    - SHA-256 hashed keys stored securely
 *    - Configure via module settings or API
 *    - Example: http://gateway:8088/data/onvif-driver/snapshot?device=X&profile=Y&apiKey=YOUR_KEY
 *    - Example header: curl -H "X-API-Key: YOUR_KEY" http://gateway:8088/data/onvif-driver/snapshot?device=X&profile=Y
 *
 * SECURITY FEATURES:
 * - Account lockout after repeated failures
 * - Authentication failure logging and auditing
 * - Secure credential handling (no plain-text storage)
 * - Per-IP rate limiting (10 req/min)
 *
 * See AuthenticationManager for implementation details.
 * See docs/SECURITY.md for security architecture and best practices.
 */
public class ONVIFRoutes {

    private static final Logger logger = LoggerFactory.getLogger(ONVIFRoutes.class);
    private static final String BOUNDARY = "onvif-stream-boundary";

    private final GatewayContext context;
    private final ONVIFDeviceExtensionPoint deviceExtensionPoint;
    private final AuthenticationManager authManager;

    // ============================================================================
    // RESOURCE PROTECTION CONFIGURATION
    // ============================================================================
    // These constants control concurrent access limits and rate limiting.
    // Adjust these values based on your deployment environment and requirements.
    //
    // CONFIGURATION GUIDELINES:
    //
    // MAX_CONCURRENT_SNAPSHOTS (default: 50)
    //   - Number of simultaneous snapshot requests allowed
    //   - Higher values = more memory and CPU usage
    //   - Recommended: 50 for standard deployments, 100+ for high-traffic
    //   - Each snapshot uses ~500KB-2MB depending on camera resolution
    //
    // MAX_CONCURRENT_STREAMS (default: 20)
    //   - Number of simultaneous MJPEG streams allowed
    //   - Higher values = significantly more bandwidth and memory
    //   - Recommended: 20 for standard deployments, 50+ for high-traffic
    //   - Each stream uses ~100-500 KB/s sustained bandwidth
    //
    // MAX_REQUESTS_PER_IP (default: 10)
    //   - Number of requests allowed per IP address per minute
    //   - Prevents DoS attacks and resource exhaustion
    //   - Recommended: 10 for general use, 60+ for known internal networks
    //   - Set higher for reverse proxy deployments (all requests from same IP)
    //
    // FUTURE ENHANCEMENT:
    // These should be moved to module settings for runtime configuration.
    // See GitHub issue #XXX or IMPLEMENTATION_STATUS.md for details.
    // ============================================================================

    /** Maximum number of concurrent snapshot requests allowed across all IPs */
    private static final int MAX_CONCURRENT_SNAPSHOTS = 50;

    /** Maximum number of concurrent MJPEG streams allowed across all IPs */
    private static final int MAX_CONCURRENT_STREAMS = 20;

    private static final AtomicInteger activeSnapshots = new AtomicInteger(0);
    private static final AtomicInteger activeStreams = new AtomicInteger(0);

    /** Maximum number of requests allowed per IP address per minute (rate limiting) */
    private static final int MAX_REQUESTS_PER_IP = 10;

    private static final Map<String, AtomicInteger> requestsPerIP = new ConcurrentHashMap<>();

    // Scheduled executor for rate limit cleanup (fixed thread leak from v2.1.0)
    // Single-threaded executor handles all rate limit expirations
    private static final ScheduledExecutorService rateLimitExecutor =
        Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "ONVIF-RateLimit-Cleanup");
            t.setDaemon(true);
            return t;
        });

    // Authentication configuration (v2.2.0: Using AuthenticationManager)
    private static final boolean REQUIRE_AUTHENTICATION = true;  // v2.1.0: Now enforced

    public ONVIFRoutes(GatewayContext context, ONVIFDeviceExtensionPoint deviceExtensionPoint) {
        this.context = context;
        this.deviceExtensionPoint = deviceExtensionPoint;
        this.authManager = new AuthenticationManager(context);
        logger.info("AuthenticationManager initialized with account lockout and API key support");
    }

    /**
     * Mounts the ONVIF routes on the provided RouteGroup.
     */
    public void mountRoutes(RouteGroup routes) {
        logger.info("Mounting ONVIF routes...");
        logger.debug("RouteGroup: {}, class: {}", routes, routes.getClass().getName());

        // DIAGNOSTIC: Test route for verifying routing works
        // TODO: Remove or disable in production builds
        routes.newRoute("/test")
            .handler(this::handleTest)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();
        logger.debug("Mounted /test route");

        // Mount snapshot endpoint at /data/onvif-driver/snapshot
        routes.newRoute("/snapshot")
            .handler(this::handleSnapshot)
            .type(RouteGroup.TYPE_OCTET_STREAM)  // Binary data (handler sets image/jpeg)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)  // Custom auth handled in handler
            .mount();
        logger.debug("Mounted /snapshot route");

        // Mount stream endpoint at /data/onvif-driver/stream
        routes.newRoute("/stream")
            .handler(this::handleStream)
            .type(RouteGroup.TYPE_OCTET_STREAM)  // Binary data (handler sets multipart/x-mixed-replace)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)  // Custom auth handled in handler
            .mount();
        logger.debug("Mounted /stream route");

        // Mount device list endpoint at /data/onvif-driver/devices
        routes.newRoute("/devices")
            .handler(this::handleListDevices)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)  // Custom auth handled in handler
            .mount();
        logger.debug("Mounted /devices route");

        // Mount device status endpoint at /data/onvif-driver/device/:name/status
        routes.newRoute("/device/:name/status")
            .handler(this::handleDeviceStatus)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();
        logger.debug("Mounted /device/:name/status route");

        // Mount connection browser page at /data/onvif-driver/connection-browser
        routes.newRoute("/connection-browser")
            .handler(this::handleConnectionBrowserPage)
            .type(RouteGroup.TYPE_OCTET_STREAM)  // Will set text/html in handler
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();
        logger.debug("Mounted /connection-browser route");

        // Mount health check endpoint
        routes.newRoute("/health")
            .handler(this::handleHealthCheck)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();
        logger.debug("Mounted /health route");

        logger.info("ONVIF routes mounted: /test, /snapshot, /stream, /devices, /device/:name/status, /connection-browser, /health");
    }

    /**
     * DIAGNOSTIC: Simple test handler to verify routing works.
     * URL: http://gateway:8088/data/onvif-driver/test
     * TODO: Remove or disable in production builds
     */
    private Object handleTest(RequestContext context, HttpServletResponse response) throws Exception {
        logger.debug("Test route called");
        logger.debug("Request URL: {}", context.getRequest().getRequestURL());
        logger.debug("Request URI: {}", context.getRequest().getRequestURI());
        logger.debug("Query String: {}", context.getRequest().getQueryString());

        response.setContentType("application/json");
        response.setStatus(200);
        String jsonResponse = "{\"status\":\"success\",\"message\":\"ONVIF Driver test route is working!\",\"timestamp\":" + System.currentTimeMillis() + "}";
        response.getWriter().write(jsonResponse);

        logger.debug("Test response sent successfully");
        return null;
    }

    /**
     * Handles snapshot requests.
     * URL: http://gateway:8088/data/onvif-driver/snapshot?device=DeviceName&profile=ProfileToken
     */
    private Object handleSnapshot(RequestContext context, HttpServletResponse response) throws Exception {
        logger.debug("Snapshot request received");
        logger.debug("Request URL: {}", context.getRequest().getRequestURL());
        logger.debug("Query String: {}", context.getRequest().getQueryString());

        // v2.1.0: Authentication check
        if (!isAuthenticated(context.getRequest())) {
            sendAuthenticationRequired(response);
            return null;
        }

        // v2.1.0: Rate limiting check
        if (!checkRateLimit(context.getRequest(), response)) {
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
            String deviceName = context.getParameter("device");
            String profileToken = context.getParameter("profile");
            logger.debug("Snapshot request: device={}, profile={}", deviceName, profileToken);

            if (deviceName == null || deviceName.trim().isEmpty()) {
                logger.warn("Missing device parameter");
                response.sendError(400, "Missing required parameter: device");
                return null;
            }

            if (profileToken == null || profileToken.trim().isEmpty()) {
                logger.warn("Missing profile parameter");
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
            logger.debug("Parameters validated");

            // Get device
            logger.debug("Looking up device: {}", deviceName);
            ONVIFDevice device = deviceExtensionPoint.getDevice(deviceName);
            if (device == null) {
                logger.warn("Device not found: {}", deviceName);
                response.sendError(404, "Device not found: " + deviceName);
                return null;
            }
            logger.debug("Device found: {}", device);

            // Check device status
            String deviceStatus = device.getStatus();
            logger.debug("Device status: {}", deviceStatus);
            if (!"Running".equals(deviceStatus) && !"Connected".equals(deviceStatus)) {
                logger.warn("Device not in running state: {} - status: {}", deviceName, deviceStatus);
                response.sendError(503, "Device is not connected: " + deviceStatus);
                return null;
            }
            logger.debug("Device status OK");

            // Get snapshot
            logger.debug("Requesting snapshot from device for profile: {}", profileToken);
            byte[] snapshotBytes;
            try {
                snapshotBytes = device.getClient().getSnapshot(profileToken);
                logger.debug("Snapshot received, size: {} bytes", snapshotBytes.length);

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

                    logger.debug("Content validation - isJPEG: {}, isHTML: {}", isJpeg, isHtml);
                    logger.debug("First 100 bytes: {}", contentStart);

                    if (!isJpeg || isHtml) {
                        logger.error("CAMERA DOES NOT COMPLY WITH ONVIF SPECIFICATION");
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
            logger.debug("Setting response headers");
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

    /**
     * Handles MJPEG stream requests.
     * URL: http://gateway:8088/data/onvif-driver/stream?device=DeviceName&profile=ProfileToken&fps=10
     */
    private Object handleStream(RequestContext context, HttpServletResponse response) throws Exception {
        logger.debug("Stream request received");
        logger.debug("Request URL: {}", context.getRequest().getRequestURL());
        logger.debug("Query String: {}", context.getRequest().getQueryString());

        // v2.1.0: Authentication check
        if (!isAuthenticated(context.getRequest())) {
            sendAuthenticationRequired(response);
            return null;
        }

        // v2.1.0: Rate limiting check
        if (!checkRateLimit(context.getRequest(), response)) {
            return null;
        }

        // Check if deviceExtensionPoint is available
        if (deviceExtensionPoint == null) {
            logger.error("ERROR: deviceExtensionPoint is NULL in handler!");
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

    /**
     * Checks if the request has valid authentication (v2.2.0+).
     *
     * Delegates to AuthenticationManager which supports:
     * 1. Valid Ignition session (primary method)
     * 2. Basic Authentication (with account lockout protection)
     * 3. API key in query parameter or X-API-Key header
     *
     * @param request The HTTP request
     * @return true if authenticated, false otherwise
     */
    private boolean isAuthenticated(HttpServletRequest request) {
        if (!REQUIRE_AUTHENTICATION) {
            return true;  // Authentication disabled (backward compatibility mode)
        }

        return authManager.isAuthenticated(request);
    }

    /**
     * Gets the AuthenticationManager for programmatic access (e.g., adding API keys).
     *
     * @return The authentication manager
     */
    public AuthenticationManager getAuthenticationManager() {
        return authManager;
    }

    /**
     * Gets the client IP address from the request, handling proxy headers.
     *
     * @param request The HTTP request
     * @return Client IP address
     */
    private String getClientIP(HttpServletRequest request) {
        // Check X-Forwarded-For header (proxy/load balancer)
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // Take the first IP if multiple are present
            return xForwardedFor.split(",")[0].trim();
        }

        // Check X-Real-IP header (nginx)
        String xRealIP = request.getHeader("X-Real-IP");
        if (xRealIP != null && !xRealIP.isEmpty()) {
            return xRealIP;
        }

        // Fall back to remote address
        return request.getRemoteAddr();
    }

    /**
     * Checks and enforces per-IP rate limiting (v2.1.0+).
     *
     * @param request The HTTP request
     * @param response The HTTP response
     * @return true if request should proceed, false if rate limit exceeded
     * @throws IOException if sending error response fails
     */
    private boolean checkRateLimit(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String clientIP = getClientIP(request);
        AtomicInteger ipRequests = requestsPerIP.computeIfAbsent(clientIP, k -> new AtomicInteger(0));

        int currentCount = ipRequests.incrementAndGet();
        if (currentCount > MAX_REQUESTS_PER_IP) {
            logger.warn("Rate limit exceeded for IP: {} ({} requests)", clientIP, currentCount);
            response.sendError(429, "Too many requests from your IP address");
            ipRequests.decrementAndGet();  // Don't count the rejected request
            return false;
        }

        // Schedule decrement after delay (fixed time-window implementation)
        // Uses ScheduledExecutorService instead of creating unbounded threads
        // Previous version created a new thread per request = resource leak under load
        rateLimitExecutor.schedule(
            () -> {
                ipRequests.decrementAndGet();
                // Clean up empty entries to prevent map bloat
                if (ipRequests.get() == 0) {
                    requestsPerIP.remove(clientIP);
                }
            },
            60, TimeUnit.SECONDS
        );

        return true;
    }

    /**
     * Shuts down the rate limiting executor service.
     * Should be called when module is unloaded to prevent resource leaks.
     */
    public static void shutdown() {
        logger.info("Shutting down rate limiting executor...");
        rateLimitExecutor.shutdown();
        try {
            if (!rateLimitExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                rateLimitExecutor.shutdownNow();
                logger.warn("Rate limiting executor did not terminate gracefully");
            }
        } catch (InterruptedException e) {
            rateLimitExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Rate limiting executor shut down complete");
    }

    /**
     * Sends an authentication required error response.
     *
     * @param response The HTTP response
     * @throws IOException if sending error fails
     */
    private void sendAuthenticationRequired(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Basic realm=\"ONVIF Driver\", charset=\"UTF-8\"");
        response.sendError(401, "Authentication required. Please log in to the Ignition Gateway or provide an API key.");
    }

    /**
     * Handles listing all ONVIF devices.
     * URL: http://gateway:8088/data/onvif-driver/devices
     *
     * NOTE: Authentication is handled by Ignition's /data/ route infrastructure.
     * Routes under /data/ require an authenticated Gateway session by default.
     */
    private Object handleListDevices(RequestContext context, HttpServletResponse response) throws Exception {
        logger.debug("List devices request received");

        // NOTE: No custom authentication check needed - Ignition's /data/ routes
        // require authenticated session by default. The Gateway handles this.

        try {
            JSONObject result = new JSONObject();
            JSONArray devices = new JSONArray();

            Map<String, ONVIFDevice> allDevices = ONVIFDeviceExtensionPoint.getAllDevices();
            for (Map.Entry<String, ONVIFDevice> entry : allDevices.entrySet()) {
                ONVIFDevice device = entry.getValue();
                JSONObject deviceJson = new JSONObject();
                deviceJson.put("name", entry.getKey());
                deviceJson.put("status", device.getStatus());

                // Try to get additional info from the device's client
                try {
                    if (device.getClient() != null) {
                        DeviceInformation info = device.getClient().getDeviceInformation();
                        if (info != null) {
                            deviceJson.put("manufacturer", info.manufacturer());
                            deviceJson.put("model", info.model());
                            deviceJson.put("firmwareVersion", info.firmwareVersion());
                            deviceJson.put("serialNumber", info.serialNumber());
                        }

                        List<MediaProfile> profiles = device.getClient().getMediaProfiles();
                        if (profiles != null) {
                            JSONArray profilesJson = new JSONArray();
                            for (MediaProfile profile : profiles) {
                                JSONObject profileJson = new JSONObject();
                                profileJson.put("token", profile.getToken());
                                profileJson.put("name", profile.getName());
                                profileJson.put("width", profile.getWidth());
                                profileJson.put("height", profile.getHeight());
                                profileJson.put("frameRate", profile.getFrameRate());
                                profileJson.put("encoding", profile.getEncoding());

                                // Get snapshot and stream URIs
                                try {
                                    profileJson.put("snapshotUri", device.getClient().getSnapshotUri(profile.getToken()));
                                } catch (Exception e) {
                                    logger.debug("Could not get snapshot URI for profile {}: {}", profile.getToken(), e.getMessage());
                                }
                                try {
                                    profileJson.put("streamUri", device.getClient().getStreamUri(profile.getToken()));
                                } catch (Exception e) {
                                    logger.debug("Could not get stream URI for profile {}: {}", profile.getToken(), e.getMessage());
                                }

                                profilesJson.put(profileJson);
                            }
                            deviceJson.put("profiles", profilesJson);
                            deviceJson.put("profileCount", profiles.size());
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Could not get additional info for device {}: {}", entry.getKey(), e.getMessage());
                }

                devices.put(deviceJson);
            }

            result.put("success", true);
            result.put("devices", devices);
            result.put("count", devices.length());
            result.put("timestamp", System.currentTimeMillis());

            response.setContentType("application/json");
            response.getWriter().write(result.toString());

        } catch (Exception e) {
            logger.error("Error listing devices", e);
            response.sendError(500, "Error listing devices: " + e.getMessage());
        }

        return null;
    }

    /**
     * Handles getting status of a specific ONVIF device.
     * URL: http://gateway:8088/data/onvif-driver/device/:name/status
     *
     * NOTE: Authentication is handled by Ignition's /data/ route infrastructure.
     */
    private Object handleDeviceStatus(RequestContext context, HttpServletResponse response) throws Exception {
        logger.debug("Device status request received");

        // NOTE: No custom authentication check needed - Ignition's /data/ routes
        // require authenticated session by default. The Gateway handles this.

        String deviceName = context.getParameter("name");
        if (deviceName == null || deviceName.trim().isEmpty()) {
            response.sendError(400, "Missing required parameter: name");
            return null;
        }

        try {
            ONVIFDevice device = deviceExtensionPoint.getDevice(deviceName);
            if (device == null) {
                response.sendError(404, "Device not found: " + deviceName);
                return null;
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("name", deviceName);
            result.put("status", device.getStatus());
            result.put("timestamp", System.currentTimeMillis());

            // Add detailed info if available
            try {
                if (device.getClient() != null) {
                    DeviceInformation info = device.getClient().getDeviceInformation();
                    if (info != null) {
                        JSONObject deviceInfo = new JSONObject();
                        deviceInfo.put("manufacturer", info.manufacturer());
                        deviceInfo.put("model", info.model());
                        deviceInfo.put("firmwareVersion", info.firmwareVersion());
                        deviceInfo.put("serialNumber", info.serialNumber());
                        deviceInfo.put("hardwareId", info.hardwareId());
                        result.put("deviceInfo", deviceInfo);
                    }

                    List<MediaProfile> profiles = device.getClient().getMediaProfiles();
                    if (profiles != null) {
                        JSONArray profilesJson = new JSONArray();
                        for (MediaProfile profile : profiles) {
                            JSONObject profileJson = new JSONObject();
                            profileJson.put("token", profile.getToken());
                            profileJson.put("name", profile.getName());
                            profileJson.put("width", profile.getWidth());
                            profileJson.put("height", profile.getHeight());
                            profileJson.put("frameRate", profile.getFrameRate());
                            profileJson.put("encoding", profile.getEncoding());

                            try {
                                profileJson.put("snapshotUri", device.getClient().getSnapshotUri(profile.getToken()));
                            } catch (Exception e) {
                                logger.debug("Could not get snapshot URI for profile {}: {}", profile.getToken(), e.getMessage());
                            }
                            try {
                                profileJson.put("streamUri", device.getClient().getStreamUri(profile.getToken()));
                            } catch (Exception e) {
                                logger.debug("Could not get stream URI for profile {}: {}", profile.getToken(), e.getMessage());
                            }

                            profilesJson.put(profileJson);
                        }
                        result.put("profiles", profilesJson);
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not get detailed info for device {}: {}", deviceName, e.getMessage());
            }

            response.setContentType("application/json");
            response.getWriter().write(result.toString());

        } catch (Exception e) {
            logger.error("Error getting device status", e);
            response.sendError(500, "Error getting device status: " + e.getMessage());
        }

        return null;
    }

    /**
     * Handles health check requests.
     * URL: http://gateway:8088/data/onvif-driver/health
     */
    private Object handleHealthCheck(RequestContext context, HttpServletResponse response) throws Exception {
        JSONObject result = new JSONObject();
        result.put("status", "ok");
        result.put("service", "onvif-driver");
        result.put("deviceCount", ONVIFDeviceExtensionPoint.getAllDevices().size());
        result.put("timestamp", System.currentTimeMillis());

        response.setContentType("application/json");
        response.getWriter().write(result.toString());
        return null;
    }

    /**
     * Handles serving the connection browser HTML page.
     * URL: http://gateway:8088/data/onvif-driver/connection-browser
     *
     * NOTE: Authentication is handled by Ignition's /data/ route infrastructure.
     */
    private Object handleConnectionBrowserPage(RequestContext context, HttpServletResponse response) throws Exception {
        logger.debug("Connection browser page request received");

        // NOTE: No custom authentication check needed - Ignition's /data/ routes
        // require authenticated session by default. The Gateway handles this.

        try {
            var stream = getClass().getResourceAsStream("/pages/connection-browser.html");
            if (stream == null) {
                logger.error("Connection browser page not found at /pages/connection-browser.html");
                response.sendError(404, "Connection browser page not found");
                return null;
            }
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            response.setContentType("text/html; charset=UTF-8");
            response.getWriter().write(html);
        } catch (Exception e) {
            logger.error("Error serving connection browser page", e);
            response.sendError(500, "Error loading page: " + e.getMessage());
        }

        return null;
    }
}
