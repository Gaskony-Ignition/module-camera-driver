package com.onvif.driver.gateway.servlet;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.ONVIFDevice;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.device.generic.GenericCameraConfig;
import com.onvif.driver.gateway.device.generic.GenericCameraDevice;
import com.onvif.driver.gateway.device.generic.GenericCameraExtensionPoint;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import com.onvif.driver.gateway.util.ValidationUtil;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
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
 * Route handlers for the Camera Driver module's snapshot and streaming endpoints.
 * Supports both ONVIF Camera and Generic Camera device types.
 * Registers routes under /data/camera-driver/*
 *
 * IMPORTANT: Actual URLs are /data/{alias}/* NOT /main/data/{alias}/*
 * Example: http://gateway:8088/data/camera-driver/snapshot?device=SideCamera&profile=000
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
 *    - Example: curl -u username:password http://gateway:8088/data/camera-driver/snapshot?device=X&profile=Y
 *
 * 3. API Key Authentication (For programmatic access):
 *    - SHA-256 hashed keys stored securely
 *    - Configure via module settings or API
 *    - Example: http://gateway:8088/data/camera-driver/snapshot?device=X&profile=Y&apiKey=YOUR_KEY
 *    - Example header: curl -H "X-API-Key: YOUR_KEY" http://gateway:8088/data/camera-driver/snapshot?device=X&profile=Y
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
    private static final String BOUNDARY = "camera-stream-boundary";

    private final GatewayContext context;
    private final ONVIFDeviceExtensionPoint deviceExtensionPoint;
    private final GenericCameraExtensionPoint genericCameraExtensionPoint;
    private final Go2RtcManager go2RtcManager;
    private final AuthenticationManager authManager;
    private final String moduleVersion;

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
    // See IMPLEMENTATION_STATUS.md for details.
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
            Thread t = new Thread(r, "CameraDriver-RateLimit-Cleanup");
            t.setDaemon(true);
            return t;
        });

    // Authentication configuration (v2.2.0: Using AuthenticationManager)
    private static final boolean REQUIRE_AUTHENTICATION = true;  // v2.1.0: Now enforced

    public ONVIFRoutes(GatewayContext context, ONVIFDeviceExtensionPoint deviceExtensionPoint,
                       GenericCameraExtensionPoint genericCameraExtensionPoint, Go2RtcManager go2RtcManager,
                       String moduleVersion) {
        this.context = context;
        this.deviceExtensionPoint = deviceExtensionPoint;
        this.genericCameraExtensionPoint = genericCameraExtensionPoint;
        this.go2RtcManager = go2RtcManager;
        this.moduleVersion = moduleVersion;
        this.authManager = new AuthenticationManager(context);
        logger.info("AuthenticationManager initialized with account lockout and API key support");
    }

    /**
     * Mounts the Camera Driver routes on the provided RouteGroup.
     */
    public void mountRoutes(RouteGroup routes) {
        logger.info("Mounting camera driver routes...");
        logger.debug("RouteGroup: {}, class: {}", routes, routes.getClass().getName());

        // Mount snapshot endpoint at /data/camera-driver/snapshot
        routes.newRoute("/snapshot")
            .handler(this::handleSnapshot)
            .type(RouteGroup.TYPE_OCTET_STREAM)  // Binary data (handler sets image/jpeg)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)  // Custom auth handled in handler
            .mount();
        logger.debug("Mounted /snapshot route");

        // Mount stream endpoint at /data/camera-driver/stream
        routes.newRoute("/stream")
            .handler(this::handleStream)
            .type(RouteGroup.TYPE_OCTET_STREAM)  // Binary data (handler sets multipart/x-mixed-replace)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)  // Custom auth handled in handler
            .mount();
        logger.debug("Mounted /stream route");

        // Mount device list endpoint at /data/camera-driver/devices
        routes.newRoute("/devices")
            .handler(this::handleListDevices)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)  // Custom auth handled in handler
            .mount();
        logger.debug("Mounted /devices route");

        // Mount device status endpoint at /data/camera-driver/device/:name/status
        routes.newRoute("/device/:name/status")
            .handler(this::handleDeviceStatus)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();
        logger.debug("Mounted /device/:name/status route");

        // Mount connection browser page at /data/camera-driver/connection-browser
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

        // Mount diagnostics endpoint at /data/camera-driver/diagnostics
        routes.newRoute("/diagnostics")
            .handler(this::handleDiagnostics)
            .type(RouteGroup.TYPE_JSON)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();
        logger.debug("Mounted /diagnostics route");

        // Mount embeddable player page at /data/camera-driver/player
        routes.newRoute("/player")
            .handler(this::handlePlayerPage)
            .type(RouteGroup.TYPE_OCTET_STREAM)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();
        logger.debug("Mounted /player route");

        logger.info("Camera driver routes mounted: /snapshot, /stream, /devices, /device/:name/status, /connection-browser, /health, /diagnostics, /player");
    }

    /**
     * Handles snapshot requests.
     * URL: http://gateway:8088/data/camera-driver/snapshot?device=DeviceName&profile=ProfileToken
     */
    private Object handleSnapshot(RequestContext context, HttpServletResponse response) throws Exception {
        logger.debug("Snapshot request received");
        logger.debug("Request URL: {}", context.getRequest().getRequestURL());
        logger.debug("Query String: {}", context.getRequest().getQueryString());

        // v2.1.0: Authentication check
        if (!isAuthenticated(context)) {
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

            // Validate device name format
            if (!ValidationUtil.isValidDeviceName(deviceName)) {
                logger.warn("Invalid device name format: {}", deviceName);
                response.sendError(400, "Invalid device name format");
                return null;
            }

            // Look up device in both registries (ONVIF first, then Generic Camera)
            ONVIFDevice onvifDevice = deviceExtensionPoint.getDevice(deviceName);
            GenericCameraDevice genericDevice = genericCameraExtensionPoint != null
                ? genericCameraExtensionPoint.getDevice(deviceName) : null;

            if (onvifDevice == null && genericDevice == null) {
                logger.warn("Device not found in any registry: {}", deviceName);
                response.sendError(404, "Device not found: " + deviceName);
                return null;
            }

            byte[] snapshotBytes;

            if (onvifDevice != null) {
                // ONVIF device - requires profile token
                if (profileToken == null || profileToken.trim().isEmpty()) {
                    response.sendError(400, "Missing required parameter: profile (required for ONVIF devices)");
                    return null;
                }
                if (!ValidationUtil.isValidProfileToken(profileToken)) {
                    response.sendError(400, "Invalid profile token format");
                    return null;
                }

                String deviceStatus = onvifDevice.getStatus();
                if (!"Running".equals(deviceStatus) && !"Connected".equals(deviceStatus)) {
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
                if (!"Running".equals(deviceStatus) && !"Connected".equals(deviceStatus)) {
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
     * URL: http://gateway:8088/data/camera-driver/stream?device=DeviceName&profile=ProfileToken&fps=10
     */
    private Object handleStream(RequestContext context, HttpServletResponse response) throws Exception {
        logger.debug("Stream request received");
        logger.debug("Request URL: {}", context.getRequest().getRequestURL());
        logger.debug("Query String: {}", context.getRequest().getQueryString());

        // v2.1.0: Authentication check
        if (!isAuthenticated(context)) {
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

            // Validate device name format
            if (!ValidationUtil.isValidDeviceName(deviceName)) {
                logger.warn("Invalid device name format: {}", deviceName);
                response.sendError(400, "Invalid device name format");
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

            // Look up device in both registries
            ONVIFDevice onvifDevice = deviceExtensionPoint.getDevice(deviceName);
            GenericCameraDevice genericDevice = genericCameraExtensionPoint != null
                ? genericCameraExtensionPoint.getDevice(deviceName) : null;

            if (onvifDevice == null && genericDevice == null) {
                response.sendError(404, "Device not found: " + deviceName);
                return null;
            }

            // Check device status
            String deviceStatus = onvifDevice != null ? onvifDevice.getStatus() : genericDevice.getStatus();
            if (!"Running".equals(deviceStatus) && !"Connected".equals(deviceStatus)) {
                response.sendError(503, "Device is not connected: " + deviceStatus);
                return null;
            }

            String origin = context.getRequest().getHeader("Origin");

            if (genericDevice != null) {
                // Generic camera - content type set inside method (MP4 for go2rtc, MJPEG for fallbacks)
                streamGenericCamera(genericDevice, deviceName, fps, response, origin, startTime);
            } else {
                // ONVIF device - try go2rtc MP4 first, fall back to snapshot polling
                if (profileToken != null && !profileToken.trim().isEmpty()
                        && !ValidationUtil.isValidProfileToken(profileToken)) {
                    response.sendError(400, "Invalid profile token format");
                    return null;
                }
                streamOnvifDevice(onvifDevice, deviceName, profileToken, fps, response, origin, startTime);
            }

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
     * Streams video from an ONVIF device.
     * Prefers go2rtc MP4 proxy (if RTSP stream is registered), falls back to MJPEG snapshot polling.
     */
    private void streamOnvifDevice(ONVIFDevice device, String deviceName, String profileToken,
                                   int fps, HttpServletResponse response, String origin, long startTime) throws IOException {

        // Try late go2rtc registration if it wasn't available during device startup
        if (!device.isGo2RtcStreamRegistered()) {
            device.tryRegisterGo2Rtc();
        }

        // Prefer go2rtc MP4 proxy for proper RTSP streaming
        if (device.isGo2RtcStreamRegistered() && go2RtcManager != null && go2RtcManager.isAvailable()) {
            logger.info("Streaming via go2rtc MP4 for ONVIF device: {}", deviceName);
            String go2rtcMp4Url = go2RtcManager.getStreamMp4Url(deviceName);
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
            response.setHeader("Connection", "close");
            if (origin != null && isAllowedOrigin(origin)) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Access-Control-Allow-Credentials", "true");
            }
            if (proxyStream(go2rtcMp4Url, response, deviceName, startTime)) {
                return;
            }
            logger.warn("go2rtc MP4 proxy failed for ONVIF device {}, falling back to snapshot polling", deviceName);
        }

        // Fallback: MJPEG via snapshot polling (requires profile token)
        if (profileToken == null || profileToken.trim().isEmpty()) {
            response.sendError(400, "Missing required parameter: profile (required for ONVIF snapshot fallback)");
            return;
        }

        response.setContentType("multipart/x-mixed-replace; boundary=" + BOUNDARY);
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setHeader("Connection", "close");
        if (origin != null && isAllowedOrigin(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }

        OutputStream output = response.getOutputStream();
        long frameDelay = 1000 / fps;
        int frameCount = 0;
        int errorCount = 0;
        int maxConsecutiveErrors = 5;

        logger.info("Starting MJPEG snapshot stream - device: {}, profile: {}, fps: {}", deviceName, profileToken, fps);

        while (!Thread.currentThread().isInterrupted()) {
            long frameStart = System.currentTimeMillis();
            try {
                byte[] frame = device.getClient().getSnapshot(profileToken);
                if (frame != null && frame.length > 0) {
                    writeMjpegFrame(output, frame);
                    frameCount++;
                    errorCount = 0;
                    if (frameCount % 100 == 0) {
                        logger.debug("Stream {} frames delivered - device: {}", frameCount, deviceName);
                    }
                } else {
                    errorCount++;
                }
            } catch (IOException e) {
                errorCount++;
                if (errorCount >= maxConsecutiveErrors) {
                    logger.error("Too many consecutive errors, stopping stream for device: {}", deviceName);
                    break;
                }
            }
            sleepForFrameRate(frameStart, frameDelay);
        }

        logger.info("Stream ended - device: {}, frames: {}, duration: {} ms",
            deviceName, frameCount, System.currentTimeMillis() - startTime);
    }

    /**
     * Streams MJPEG for a generic camera device using the fallback chain:
     * 1. go2rtc RTSP decode (proxy MJPEG from go2rtc)
     * 2. Native MJPEG URL proxy
     * 3. Snapshot-polling MJPEG
     */
    private void streamGenericCamera(GenericCameraDevice device, String deviceName,
                                     int fps, HttpServletResponse response, String origin, long startTime) throws IOException {
        GenericCameraConfig config = device.getConfig();
        String rtspUrl = config.cameraConnection().rtspUrl();
        String mjpegUrl = config.cameraConnection().mjpegUrl();
        boolean hasRtsp = rtspUrl != null && !rtspUrl.trim().isEmpty();
        boolean hasMjpeg = mjpegUrl != null && !mjpegUrl.trim().isEmpty();
        boolean hasSnapshot = device.getCameraClient() != null
            && config.cameraConnection().snapshotUrl() != null
            && !config.cameraConnection().snapshotUrl().trim().isEmpty();

        // Try late go2rtc registration if it wasn't available during device startup
        if (hasRtsp && !device.isGo2RtcStreamRegistered()) {
            device.tryRegisterGo2Rtc();
        }

        // Fallback 1: go2rtc RTSP -> MP4 proxy (no ffmpeg required, unlike MJPEG)
        // Content-Type is forwarded from go2rtc (includes codec info needed for MSE)
        if (hasRtsp && device.isGo2RtcStreamRegistered() && go2RtcManager != null && go2RtcManager.isAvailable()) {
            logger.info("Streaming via go2rtc MP4 for device: {}", deviceName);
            String go2rtcMp4Url = go2RtcManager.getStreamMp4Url(deviceName);
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
            response.setHeader("Connection", "close");
            if (origin != null && isAllowedOrigin(origin)) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Access-Control-Allow-Credentials", "true");
            }
            if (proxyStream(go2rtcMp4Url, response, deviceName, startTime)) {
                return;
            }
            logger.warn("go2rtc MP4 proxy failed, trying fallback for device: {}", deviceName);
        }

        // Set MJPEG headers for fallback methods
        response.setContentType("multipart/x-mixed-replace; boundary=" + BOUNDARY);
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setHeader("Connection", "close");
        if (origin != null && isAllowedOrigin(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }

        // Fallback 2: Native MJPEG URL proxy
        if (hasMjpeg) {
            logger.info("Streaming native MJPEG for device: {}", deviceName);
            if (proxyStream(mjpegUrl, response, deviceName, startTime)) {
                return;
            }
            logger.warn("Native MJPEG proxy failed, trying snapshot polling for device: {}", deviceName);
        }

        // Fallback 3: Snapshot-polling MJPEG
        if (hasSnapshot) {
            logger.info("Streaming via snapshot polling for device: {}", deviceName);
            streamSnapshotPolling(device, deviceName, fps, response, startTime);
            return;
        }

        // No streaming method available
        if (!response.isCommitted()) {
            response.sendError(400, "No streaming source available. Configure RTSP, MJPEG, or snapshot URL.");
        }
    }

    /**
     * Proxies a stream from a URL to the HTTP response.
     * Used for both go2rtc MP4 streams and camera-native MJPEG streams.
     * Returns true if successfully started proxying, false if connection failed.
     */
    private boolean proxyStream(String sourceUrl, HttpServletResponse response,
                                String deviceName, long startTime) {
        RequestConfig proxyConfig = RequestConfig.custom()
            .setConnectTimeout(5000)
            .setSocketTimeout(30000)
            .build();

        try (CloseableHttpClient proxyClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(proxyConfig).build()) {
            HttpGet request = new HttpGet(sourceUrl);
            try (CloseableHttpResponse upstream = proxyClient.execute(request)) {
                int statusCode = upstream.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    logger.warn("Stream proxy got HTTP {} from {}", statusCode, sourceUrl);
                    return false;
                }

                // Forward Content-Type from upstream (preserves codec info for MSE playback)
                if (upstream.getEntity().getContentType() != null) {
                    String upstreamContentType = upstream.getEntity().getContentType().getValue();
                    if (!response.isCommitted()) {
                        response.setContentType(upstreamContentType);
                        logger.debug("Forwarding upstream Content-Type: {}", upstreamContentType);
                    }
                }

                OutputStream output = response.getOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;

                try (var inputStream = upstream.getEntity().getContent()) {
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                        output.flush();
                        totalBytes += bytesRead;
                    }
                }

                logger.info("Stream proxy ended - device: {}, bytes: {}, duration: {} ms",
                    deviceName, totalBytes, System.currentTimeMillis() - startTime);
                return true;
            }
        } catch (IOException e) {
            logger.debug("Stream proxy failed for {}: {}", deviceName, e.getMessage());
            return false;
        }
    }

    /**
     * Streams MJPEG by polling snapshots from a generic camera.
     */
    private void streamSnapshotPolling(GenericCameraDevice device, String deviceName,
                                       int fps, HttpServletResponse response, long startTime) throws IOException {
        OutputStream output = response.getOutputStream();
        long frameDelay = 1000 / fps;
        int frameCount = 0;
        int errorCount = 0;
        int maxConsecutiveErrors = 5;

        while (!Thread.currentThread().isInterrupted()) {
            long frameStart = System.currentTimeMillis();
            try {
                byte[] frame = device.getCameraClient().fetchSnapshot();
                if (frame != null && frame.length > 0) {
                    writeMjpegFrame(output, frame);
                    frameCount++;
                    errorCount = 0;
                    if (frameCount % 100 == 0) {
                        logger.debug("Stream {} frames delivered - device: {}", frameCount, deviceName);
                    }
                } else {
                    errorCount++;
                }
            } catch (IOException e) {
                errorCount++;
                if (errorCount >= maxConsecutiveErrors) {
                    logger.error("Too many snapshot errors, stopping stream for device: {}", deviceName);
                    break;
                }
            }
            sleepForFrameRate(frameStart, frameDelay);
        }

        logger.info("Snapshot-polling stream ended - device: {}, frames: {}, duration: {} ms",
            deviceName, frameCount, System.currentTimeMillis() - startTime);
    }

    /**
     * Writes a single MJPEG frame to the output stream.
     */
    private void writeMjpegFrame(OutputStream output, byte[] frame) throws IOException {
        output.write(("--" + BOUNDARY + "\r\n").getBytes());
        output.write("Content-Type: image/jpeg\r\n".getBytes());
        output.write(("Content-Length: " + frame.length + "\r\n\r\n").getBytes());
        output.write(frame);
        output.write("\r\n".getBytes());
        output.flush();
    }

    /**
     * Sleeps to maintain the target frame rate.
     */
    private void sleepForFrameRate(long frameStart, long frameDelay) {
        long frameTime = System.currentTimeMillis() - frameStart;
        long sleepTime = frameDelay - frameTime;
        if (sleepTime > 0) {
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Checks if the request has valid authentication (v2.2.0+).
     *
     * Delegates to AuthenticationManager which supports:
     * 1. Valid Ignition WebUiSession (primary method)
     * 2. Basic Authentication (with account lockout protection)
     * 3. API key in query parameter or X-API-Key header
     *
     * @param requestContext The route request context
     * @return true if authenticated, false otherwise
     */
    private boolean isAuthenticated(RequestContext requestContext) {
        if (!REQUIRE_AUTHENTICATION) {
            return true;  // Authentication disabled (backward compatibility mode)
        }

        return authManager.isAuthenticated(requestContext);
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
        response.setHeader("WWW-Authenticate", "Basic realm=\"Camera Driver\", charset=\"UTF-8\"");
        response.sendError(401, "Authentication required. Please log in to the Ignition Gateway or provide an API key.");
    }

    /**
     * Handles listing all devices.
     * URL: http://gateway:8088/data/camera-driver/devices
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
                deviceJson.put("type", "onvif");
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

                                // Get snapshot and stream URIs (with credentials embedded)
                                try {
                                    String snapshotUri = device.getClient().getSnapshotUri(profile.getToken());
                                    profileJson.put("snapshotUri", device.getAuthenticatedUrl(snapshotUri));
                                } catch (Exception e) {
                                    logger.debug("Could not get snapshot URI for profile {}: {}", profile.getToken(), e.getMessage());
                                }
                                try {
                                    String streamUri = device.getClient().getStreamUri(profile.getToken());
                                    profileJson.put("streamUri", device.getAuthenticatedUrl(streamUri));
                                } catch (Exception e) {
                                    logger.debug("Could not get stream URI for profile {}: {}", profile.getToken(), e.getMessage());
                                }

                                profilesJson.put(profileJson);
                            }
                            deviceJson.put("profiles", profilesJson);
                            deviceJson.put("profileCount", profiles.size());
                        }
                    }
                    deviceJson.put("go2rtcRegistered", device.isGo2RtcStreamRegistered());
                } catch (Exception e) {
                    logger.debug("Could not get additional info for device {}: {}", entry.getKey(), e.getMessage());
                }

                devices.put(deviceJson);
            }

            // Include generic cameras
            if (genericCameraExtensionPoint != null) {
                Map<String, GenericCameraDevice> genericDevices = GenericCameraExtensionPoint.getAllDevices();
                for (Map.Entry<String, GenericCameraDevice> entry : genericDevices.entrySet()) {
                    GenericCameraDevice device = entry.getValue();
                    JSONObject deviceJson = new JSONObject();
                    deviceJson.put("name", entry.getKey());
                    deviceJson.put("type", "generic");
                    deviceJson.put("status", device.getStatus());

                    GenericCameraConfig cfg = device.getConfig();
                    if (cfg.cameraConnection().rtspUrl() != null) {
                        deviceJson.put("rtspUrl", cfg.cameraConnection().rtspUrl());
                    }
                    if (cfg.cameraConnection().snapshotUrl() != null) {
                        deviceJson.put("snapshotUrl", cfg.cameraConnection().snapshotUrl());
                    }
                    if (cfg.cameraConnection().mjpegUrl() != null) {
                        deviceJson.put("mjpegUrl", cfg.cameraConnection().mjpegUrl());
                    }
                    deviceJson.put("go2rtcRegistered", device.isGo2RtcStreamRegistered());

                    // Generate synthetic profiles from configured URLs
                    JSONArray genericProfiles = new JSONArray();
                    String rtspUrl = device.getAuthenticatedRtspUrl();
                    if (rtspUrl != null && !rtspUrl.trim().isEmpty()) {
                        JSONObject rtspProfile = new JSONObject();
                        rtspProfile.put("token", "rtsp");
                        rtspProfile.put("name", "RTSP Stream");
                        rtspProfile.put("encoding", "H.264");
                        rtspProfile.put("streamUri", rtspUrl);
                        int fps = cfg.streamSettings() != null ? cfg.streamSettings().defaultFps() : 15;
                        rtspProfile.put("frameRate", fps);
                        genericProfiles.put(rtspProfile);
                    }
                    String snapshotUrl = cfg.cameraConnection().snapshotUrl();
                    if (snapshotUrl != null && !snapshotUrl.trim().isEmpty()) {
                        JSONObject snapshotProfile = new JSONObject();
                        snapshotProfile.put("token", "snapshot");
                        snapshotProfile.put("name", "HTTP Snapshot");
                        snapshotProfile.put("encoding", "JPEG");
                        genericProfiles.put(snapshotProfile);
                    }
                    String mjpegUrl = cfg.cameraConnection().mjpegUrl();
                    if (mjpegUrl != null && !mjpegUrl.trim().isEmpty()) {
                        JSONObject mjpegProfile = new JSONObject();
                        mjpegProfile.put("token", "mjpeg");
                        mjpegProfile.put("name", "MJPEG Stream");
                        mjpegProfile.put("encoding", "MJPEG");
                        mjpegProfile.put("streamUri", mjpegUrl);
                        genericProfiles.put(mjpegProfile);
                    }
                    if (genericProfiles.length() > 0) {
                        deviceJson.put("profiles", genericProfiles);
                        deviceJson.put("profileCount", genericProfiles.length());
                    }

                    devices.put(deviceJson);
                }
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
     * Handles getting status of a specific device (ONVIF or Generic Camera).
     * URL: http://gateway:8088/data/camera-driver/device/:name/status
     */
    private Object handleDeviceStatus(RequestContext context, HttpServletResponse response) throws Exception {
        logger.debug("Device status request received");

        String deviceName = context.getParameter("name");
        if (deviceName == null || deviceName.trim().isEmpty()) {
            response.sendError(400, "Missing required parameter: name");
            return null;
        }

        try {
            // Check both registries
            ONVIFDevice onvifDevice = deviceExtensionPoint.getDevice(deviceName);
            GenericCameraDevice genericDevice = genericCameraExtensionPoint != null
                ? genericCameraExtensionPoint.getDevice(deviceName) : null;

            if (onvifDevice == null && genericDevice == null) {
                response.sendError(404, "Device not found: " + deviceName);
                return null;
            }

            // Use whichever device was found (ONVIF device block follows for backward compatibility)
            if (genericDevice != null && onvifDevice == null) {
                // Return generic camera status
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("name", deviceName);
                result.put("type", "generic");
                result.put("status", genericDevice.getStatus());
                result.put("go2rtcRegistered", genericDevice.isGo2RtcStreamRegistered());
                result.put("timestamp", System.currentTimeMillis());

                // Generate synthetic profiles from configured URLs
                GenericCameraConfig cfg = genericDevice.getConfig();
                JSONArray genericProfiles = new JSONArray();
                String rtspUrl = genericDevice.getAuthenticatedRtspUrl();
                if (rtspUrl != null && !rtspUrl.trim().isEmpty()) {
                    JSONObject rtspProfile = new JSONObject();
                    rtspProfile.put("token", "rtsp");
                    rtspProfile.put("name", "RTSP Stream");
                    rtspProfile.put("encoding", "H.264");
                    rtspProfile.put("streamUri", rtspUrl);
                    int fps = cfg.streamSettings() != null ? cfg.streamSettings().defaultFps() : 15;
                    rtspProfile.put("frameRate", fps);
                    genericProfiles.put(rtspProfile);
                }
                String snapshotUrl = cfg.cameraConnection().snapshotUrl();
                if (snapshotUrl != null && !snapshotUrl.trim().isEmpty()) {
                    JSONObject snapshotProfile = new JSONObject();
                    snapshotProfile.put("token", "snapshot");
                    snapshotProfile.put("name", "HTTP Snapshot");
                    snapshotProfile.put("encoding", "JPEG");
                    genericProfiles.put(snapshotProfile);
                }
                String mjpegUrl = cfg.cameraConnection().mjpegUrl();
                if (mjpegUrl != null && !mjpegUrl.trim().isEmpty()) {
                    JSONObject mjpegProfile = new JSONObject();
                    mjpegProfile.put("token", "mjpeg");
                    mjpegProfile.put("name", "MJPEG Stream");
                    mjpegProfile.put("encoding", "MJPEG");
                    mjpegProfile.put("streamUri", mjpegUrl);
                    genericProfiles.put(mjpegProfile);
                }
                if (genericProfiles.length() > 0) {
                    result.put("profiles", genericProfiles);
                    result.put("profileCount", genericProfiles.length());
                }

                response.setContentType("application/json");
                response.getWriter().write(result.toString());
                return null;
            }

            // ONVIF device (original behavior)
            ONVIFDevice device = onvifDevice;

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
                                String snapshotUri = device.getClient().getSnapshotUri(profile.getToken());
                                profileJson.put("snapshotUri", device.getAuthenticatedUrl(snapshotUri));
                            } catch (Exception e) {
                                logger.debug("Could not get snapshot URI for profile {}: {}", profile.getToken(), e.getMessage());
                            }
                            try {
                                String streamUri = device.getClient().getStreamUri(profile.getToken());
                                profileJson.put("streamUri", device.getAuthenticatedUrl(streamUri));
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
     * URL: http://gateway:8088/data/camera-driver/health
     */
    private Object handleHealthCheck(RequestContext context, HttpServletResponse response) throws Exception {
        JSONObject result = new JSONObject();
        result.put("status", "ok");
        result.put("service", "camera-driver");
        result.put("version", moduleVersion);
        result.put("onvifDeviceCount", ONVIFDeviceExtensionPoint.getAllDevices().size());
        result.put("genericCameraCount", GenericCameraExtensionPoint.getAllDevices().size());
        int totalDevices = ONVIFDeviceExtensionPoint.getAllDevices().size()
            + GenericCameraExtensionPoint.getAllDevices().size();
        result.put("deviceCount", totalDevices);

        // Count running devices
        int runningCount = 0;
        for (ONVIFDevice d : ONVIFDeviceExtensionPoint.getAllDevices().values()) {
            String s = d.getStatus();
            if ("Running".equals(s) || "Connected".equals(s)) runningCount++;
        }
        for (GenericCameraDevice d : GenericCameraExtensionPoint.getAllDevices().values()) {
            String s = d.getStatus();
            if ("Running".equals(s) || "Connected".equals(s)) runningCount++;
        }
        result.put("runningCount", runningCount);

        result.put("activeSnapshots", activeSnapshots.get());
        result.put("maxSnapshots", MAX_CONCURRENT_SNAPSHOTS);
        result.put("activeStreams", activeStreams.get());
        result.put("maxStreams", MAX_CONCURRENT_STREAMS);
        result.put("go2rtcAvailable", go2RtcManager != null && go2RtcManager.isAvailable());
        result.put("timestamp", System.currentTimeMillis());

        response.setContentType("application/json");
        response.getWriter().write(result.toString());
        return null;
    }

    /**
     * Handles diagnostics requests - returns JSON with resource usage metrics.
     * URL: http://gateway:8088/data/camera-driver/diagnostics
     */
    private Object handleDiagnostics(RequestContext context, HttpServletResponse response) throws Exception {
        logger.debug("Diagnostics request received");

        if (!isAuthenticated(context)) {
            sendAuthenticationRequired(response);
            return null;
        }

        try {
            JSONObject result = new JSONObject();

            // Streaming activity
            JSONObject streaming = new JSONObject();
            streaming.put("activeSnapshots", activeSnapshots.get());
            streaming.put("maxSnapshots", MAX_CONCURRENT_SNAPSHOTS);
            streaming.put("activeStreams", activeStreams.get());
            streaming.put("maxStreams", MAX_CONCURRENT_STREAMS);
            streaming.put("connectedClients", requestsPerIP.size());
            result.put("streaming", streaming);

            // go2rtc process info
            if (go2RtcManager != null) {
                result.put("go2rtc", go2RtcManager.getProcessInfo());
                result.put("go2rtcStreams", go2RtcManager.getStreamInfo());
            } else {
                JSONObject noGo2Rtc = new JSONObject();
                noGo2Rtc.put("alive", false);
                noGo2Rtc.put("port", 0);
                result.put("go2rtc", noGo2Rtc);
            }

            // Gateway JVM metrics
            JSONObject gateway = new JSONObject();
            MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
            long heapUsed = memBean.getHeapMemoryUsage().getUsed();
            long heapMax = memBean.getHeapMemoryUsage().getMax();
            gateway.put("heapUsedMb", heapUsed / (1024 * 1024));
            gateway.put("heapMaxMb", heapMax > 0 ? heapMax / (1024 * 1024) : -1);
            gateway.put("heapPercent", heapMax > 0 ? Math.round((double) heapUsed / heapMax * 100) : -1);

            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            gateway.put("threadCount", threadBean.getThreadCount());
            result.put("gateway", gateway);

            // Device counts
            JSONObject deviceCounts = new JSONObject();
            deviceCounts.put("onvif", ONVIFDeviceExtensionPoint.getAllDevices().size());
            deviceCounts.put("generic", GenericCameraExtensionPoint.getAllDevices().size());
            deviceCounts.put("total", ONVIFDeviceExtensionPoint.getAllDevices().size()
                + GenericCameraExtensionPoint.getAllDevices().size());
            result.put("devices", deviceCounts);

            result.put("timestamp", System.currentTimeMillis());

            response.setContentType("application/json");
            response.getWriter().write(result.toString());

        } catch (Exception e) {
            logger.error("Error generating diagnostics", e);
            response.sendError(500, "Error generating diagnostics: " + e.getMessage());
        }

        return null;
    }

    /**
     * Handles serving the embeddable video player page.
     * URL: http://gateway:8088/data/camera-driver/player?device=DeviceName
     *
     * Designed to be embedded in Perspective Inline Frame component.
     */
    private Object handlePlayerPage(RequestContext context, HttpServletResponse response) throws Exception {
        logger.debug("Player page request received");

        if (!isAuthenticated(context)) {
            sendAuthenticationRequired(response);
            return null;
        }

        try {
            InputStream stream = getClass().getResourceAsStream("/pages/player.html");
            if (stream == null) {
                logger.error("Player page not found at /pages/player.html");
                response.sendError(404, "Player page not found");
                return null;
            }
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            response.setContentType("text/html; charset=UTF-8");
            response.getWriter().write(html);
        } catch (Exception e) {
            logger.error("Error serving player page", e);
            response.sendError(500, "Error loading page: " + e.getMessage());
        }

        return null;
    }

    /**
     * Handles serving the connection browser HTML page.
     * URL: http://gateway:8088/data/camera-driver/connection-browser
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
