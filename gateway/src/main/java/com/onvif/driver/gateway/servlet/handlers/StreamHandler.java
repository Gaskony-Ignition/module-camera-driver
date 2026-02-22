package com.onvif.driver.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.common.DeviceStatus;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.ONVIFDevice;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.device.generic.GenericCameraConfig;
import com.onvif.driver.gateway.device.generic.GenericCameraDevice;
import com.onvif.driver.gateway.device.generic.GenericCameraExtensionPoint;
import com.onvif.driver.gateway.servlet.CorsManager;
import com.onvif.driver.gateway.servlet.RateLimiter;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import com.onvif.driver.gateway.util.ValidationUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles MJPEG stream requests for both ONVIF and Generic Camera devices.
 * URL: /data/camera-driver/stream?device=DeviceName&profile=ProfileToken&fps=10
 */
public class StreamHandler extends BaseHandler {

    private static final String BOUNDARY = "camera-stream-boundary";
    private static final int MAX_CONSECUTIVE_STREAM_ERRORS = 5;
    private static final int MAX_CONCURRENT_STREAMS = 20;
    private static final AtomicInteger activeStreams = new AtomicInteger(0);

    public StreamHandler(GatewayContext context,
                         ONVIFDeviceExtensionPoint deviceExtensionPoint,
                         GenericCameraExtensionPoint genericCameraExtensionPoint,
                         Go2RtcManager go2RtcManager,
                         AuthenticationManager authManager,
                         String moduleVersion) {
        super(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    public static int getActiveStreams() { return activeStreams.get(); }
    public static int getMaxConcurrentStreams() { return MAX_CONCURRENT_STREAMS; }

    /** Resets the active counter to 0. Call during module shutdown to ensure clean state on reload. */
    public static void resetCounters() {
        activeStreams.set(0);
    }

    /**
     * Handles MJPEG stream requests.
     * URL: http://gateway:8088/data/camera-driver/stream?device=DeviceName&profile=ProfileToken&fps=10
     */
    public Object handle(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Stream request received");
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
            deviceName = requestContext.getParameter("device");
            profileToken = requestContext.getParameter("profile");
            String fpsParam = requestContext.getParameter("fps");

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
            DeviceLookup devices = findDevice(deviceName);
            if (!devices.found()) {
                response.sendError(404, "Device not found");
                return null;
            }
            ONVIFDevice onvifDevice = devices.onvif();
            GenericCameraDevice genericDevice = devices.generic();

            // Check device status
            String deviceStatus = onvifDevice != null ? onvifDevice.getStatus() : genericDevice.getStatus();
            if (!DeviceStatus.isActive(deviceStatus)) {
                response.sendError(503, "Device is not connected: " + deviceStatus);
                return null;
            }

            String origin = requestContext.getRequest().getHeader("Origin");

            if (genericDevice != null) {
                // Generic camera - content type set inside method (MP4 for go2rtc, MJPEG for fallbacks)
                streamGenericCamera(genericDevice, deviceName, fps, response, origin, requestContext, startTime);
            } else {
                // ONVIF device - try go2rtc MP4 first, fall back to snapshot polling
                if (profileToken != null && !profileToken.trim().isEmpty()
                        && !ValidationUtil.isValidProfileToken(profileToken)) {
                    response.sendError(400, "Invalid profile token format");
                    return null;
                }
                // Auto-select default profile if none provided (Perspective components)
                if (profileToken == null || profileToken.trim().isEmpty()) {
                    profileToken = onvifDevice.getDefaultProfileToken();
                    logger.debug("Using default profile token for ONVIF device {}: {}", deviceName, profileToken);
                }
                streamOnvifDevice(onvifDevice, deviceName, profileToken, fps, response, origin, requestContext, startTime);
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
     * Streams video from an ONVIF device.
     * Prefers go2rtc MP4 proxy (if RTSP stream is registered), falls back to MJPEG snapshot polling.
     */
    private void streamOnvifDevice(ONVIFDevice device, String deviceName, String profileToken,
                                   int fps, HttpServletResponse response, String origin,
                                   RequestContext requestContext, long startTime) throws IOException {

        // Try late go2rtc registration if it wasn't available during device startup
        if (!device.isGo2RtcStreamRegistered()) {
            device.tryRegisterGo2Rtc();
        }

        // Prefer go2rtc MP4 proxy for proper RTSP streaming
        if (device.isGo2RtcStreamRegistered() && go2RtcManager != null && go2RtcManager.isAvailable()) {
            logger.info("Streaming via go2rtc MP4 for ONVIF device: {}", deviceName);
            String go2rtcMp4Url = go2RtcManager.getStreamMp4Url(deviceName);
            CorsManager.setStreamingHeaders(response, origin, requestContext.getRequest());
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
        CorsManager.setStreamingHeaders(response, origin, requestContext.getRequest());

        OutputStream output = response.getOutputStream();
        long frameDelay = 1000 / fps;
        int frameCount = 0;
        int errorCount = 0;

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
                if (errorCount >= MAX_CONSECUTIVE_STREAM_ERRORS) {
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
                                     int fps, HttpServletResponse response, String origin,
                                     RequestContext requestContext, long startTime) throws IOException {
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
            CorsManager.setStreamingHeaders(response, origin, requestContext.getRequest());
            if (proxyStream(go2rtcMp4Url, response, deviceName, startTime)) {
                return;
            }
            logger.warn("go2rtc MP4 proxy failed, trying fallback for device: {}", deviceName);
        }

        // Set MJPEG headers for fallback methods
        response.setContentType("multipart/x-mixed-replace; boundary=" + BOUNDARY);
        CorsManager.setStreamingHeaders(response, origin, requestContext.getRequest());

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
                org.apache.http.HttpEntity entity = upstream.getEntity();
                if (entity != null && entity.getContentType() != null) {
                    String upstreamContentType = entity.getContentType().getValue();
                    if (!response.isCommitted()) {
                        response.setContentType(upstreamContentType);
                        logger.debug("Forwarding upstream Content-Type: {}", upstreamContentType);
                    }
                }

                if (entity == null) {
                    logger.warn("No response entity from upstream for device: {}", deviceName);
                    return false;
                }

                OutputStream output = response.getOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;

                try (var inputStream = entity.getContent()) {
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
                if (errorCount >= MAX_CONSECUTIVE_STREAM_ERRORS) {
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
}
