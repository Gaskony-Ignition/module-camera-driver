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
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles MJPEG stream requests for Camera devices.
 * URL: /data/camera-driver/stream?device=DeviceName&profile=ProfileToken&fps=10
 */
public class StreamHandler extends BaseHandler {

    private static final String BOUNDARY = "camera-stream-boundary";
    private static final int MAX_CONSECUTIVE_STREAM_ERRORS = 5;
    private static final int MAX_CONCURRENT_STREAMS = 20;

    /** {@link #proxyStream} result: the upstream stream was proxied to the client successfully. */
    private static final int PROXY_STREAMED = 200;
    /** {@link #proxyStream} result: the upstream source could not be reached / returned no body. */
    private static final int PROXY_CONNECT_ERROR = -1;
    private static final AtomicInteger activeStreams = new AtomicInteger(0);

    public StreamHandler(GatewayContext context,
                         CameraExtensionPoint cameraExtensionPoint,
                         Go2RtcManager go2RtcManager,
                         AuthenticationManager authManager,
                         String moduleVersion) {
        super(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    public static int getActiveStreams() { return activeStreams.get(); }
    public static int getMaxConcurrentStreams() { return MAX_CONCURRENT_STREAMS; }

    public static void resetCounters() {
        activeStreams.set(0);
    }

    public Object handle(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Stream request received");

        // P3-CD: shared AccessControl pattern.
        if (!requireAuthenticated(requestContext, response)) {
            return null;
        }

        if (!RateLimiter.checkRateLimit(requestContext.getRequest(), response)) {
            return null;
        }

        if (activeStreams.get() >= MAX_CONCURRENT_STREAMS) {
            response.sendError(503, "Maximum concurrent streams reached");
            return null;
        }

        activeStreams.incrementAndGet();
        long startTime = System.currentTimeMillis();
        String deviceName = null;
        String profileToken = null;

        try {
            deviceName = requestContext.getParameter("device");
            profileToken = requestContext.getParameter("profile");
            String fpsParam = requestContext.getParameter("fps");

            if (deviceName == null || deviceName.trim().isEmpty()) {
                response.sendError(400, "Missing required parameter: device");
                return null;
            }

            if (!ValidationUtil.isValidDeviceName(deviceName)) {
                response.sendError(400, "Invalid device name format");
                return null;
            }

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

            String origin = requestContext.getRequest().getHeader("Origin");

            // Track whether the device actually has a streaming source configured.
            // This lets us distinguish a genuine misconfiguration (no source -> 400)
            // from a configured-but-unreachable camera (upstream failure -> 502).
            boolean sourceConfigured = false;
            String upstreamFailure = null;

            // Try late go2rtc registration
            if (!device.isGo2RtcStreamRegistered()) {
                device.tryRegisterGo2Rtc();
            }

            // Prefer go2rtc MP4 proxy for RTSP streaming
            if (device.isGo2RtcStreamRegistered() && go2RtcManager != null && go2RtcManager.isAvailable()) {
                sourceConfigured = true;
                logger.info("Streaming via go2rtc MP4 for device: {}", deviceName);
                String go2rtcMp4Url = go2RtcManager.getStreamMp4Url(deviceName);
                CorsManager.setStreamingHeaders(response, origin, requestContext.getRequest());
                int result = proxyStream(go2rtcMp4Url, response, deviceName, startTime);
                if (result == PROXY_STREAMED) {
                    return null;
                }
                upstreamFailure = describeProxyFailure("go2rtc", result);
                logger.warn("go2rtc MP4 proxy failed for {} ({}), trying fallbacks", deviceName, upstreamFailure);
            }

            // Fallback: Native MJPEG URL proxy
            String mjpegUrl = nonEmpty(device.getConfig().advanced().mjpegUrl());
            if (mjpegUrl != null) {
                sourceConfigured = true;
                logger.info("Streaming native MJPEG for device: {}", deviceName);
                CorsManager.setStreamingHeaders(response, origin, requestContext.getRequest());
                int result = proxyStream(mjpegUrl, response, deviceName, startTime);
                if (result == PROXY_STREAMED) {
                    return null;
                }
                upstreamFailure = describeProxyFailure("MJPEG source", result);
                logger.warn("Native MJPEG proxy failed for {} ({}), trying snapshot polling", deviceName, upstreamFailure);
            }

            // Fallback: ONVIF snapshot polling
            if (device.isOnvifAvailable() && device.getClient() != null) {
                if (profileToken == null || profileToken.trim().isEmpty()) {
                    profileToken = device.getDefaultProfileToken();
                }
                if (profileToken != null) {
                    if (!ValidationUtil.isValidProfileToken(profileToken)) {
                        response.sendError(400, "Invalid profile token format");
                        return null;
                    }
                    response.setContentType("multipart/x-mixed-replace; boundary=" + BOUNDARY);
                    CorsManager.setStreamingHeaders(response, origin, requestContext.getRequest());
                    streamOnvifSnapshotPolling(device, deviceName, profileToken, fps, response, startTime);
                    return null;
                }
            }

            // Fallback: Generic camera snapshot polling
            if (device.getCameraClient() != null) {
                response.setContentType("multipart/x-mixed-replace; boundary=" + BOUNDARY);
                CorsManager.setStreamingHeaders(response, origin, requestContext.getRequest());
                streamGenericSnapshotPolling(device, deviceName, fps, response, startTime);
                return null;
            }

            if (!response.isCommitted()) {
                if (sourceConfigured) {
                    // A source is configured but every attempt to pull it failed —
                    // an upstream/camera problem (offline, wrong path/credentials),
                    // NOT a misconfiguration of this gateway. Report it as such so the
                    // client doesn't misread it as "nothing is set up".
                    response.sendError(502,
                        "Camera stream source is unavailable: " + upstreamFailure
                        + ". Verify the camera is online and that its stream URL, path, and credentials are correct.");
                } else {
                    response.sendError(400,
                        "No streaming source available. Configure an RTSP, MJPEG, or snapshot URL for this device.");
                }
            }

        } catch (Exception e) {
            logger.error("Unexpected error in stream handler for device: {}", deviceName, e);
            if (!response.isCommitted()) {
                response.sendError(500, "Internal server error");
            }
        } finally {
            activeStreams.decrementAndGet();
        }

        return null;
    }

    private void streamOnvifSnapshotPolling(CameraDevice device, String deviceName,
                                            String profileToken, int fps,
                                            HttpServletResponse response, long startTime) throws IOException {
        OutputStream output = response.getOutputStream();
        long frameDelay = 1000 / fps;
        int frameCount = 0;
        int errorCount = 0;

        logger.info("Starting ONVIF snapshot stream - device: {}, profile: {}, fps: {}", deviceName, profileToken, fps);

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

    private void streamGenericSnapshotPolling(CameraDevice device, String deviceName,
                                              int fps, HttpServletResponse response, long startTime) throws IOException {
        OutputStream output = response.getOutputStream();
        long frameDelay = 1000 / fps;
        int frameCount = 0;
        int errorCount = 0;

        logger.info("Starting generic snapshot stream - device: {}, fps: {}", deviceName, fps);

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
     * Proxies an upstream stream (go2rtc MP4 or native MJPEG) to the client.
     *
     * @return {@link #PROXY_STREAMED} if the stream was proxied successfully,
     *         the upstream HTTP status code if the source responded with a
     *         non-200, or {@link #PROXY_CONNECT_ERROR} if the source could not
     *         be reached or returned no body.
     */
    private int proxyStream(String sourceUrl, HttpServletResponse response,
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
                    // Do NOT log sourceUrl — it can embed camera credentials.
                    logger.warn("Stream proxy got HTTP {} from upstream source for device {}",
                        statusCode, deviceName);
                    return statusCode;
                }

                org.apache.http.HttpEntity entity = upstream.getEntity();
                if (entity != null && entity.getContentType() != null) {
                    String upstreamContentType = entity.getContentType().getValue();
                    if (!response.isCommitted()) {
                        response.setContentType(upstreamContentType);
                    }
                }

                if (entity == null) {
                    return PROXY_CONNECT_ERROR;
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
                return PROXY_STREAMED;
            }
        } catch (IOException e) {
            logger.debug("Stream proxy failed for {}: {}", deviceName, e.getMessage());
            return PROXY_CONNECT_ERROR;
        }
    }

    /** Builds a credential-safe description of a failed proxy attempt for the client/log. */
    private static String describeProxyFailure(String sourceLabel, int result) {
        if (result == PROXY_CONNECT_ERROR) {
            return sourceLabel + " could not be reached";
        }
        return sourceLabel + " returned HTTP " + result;
    }

    private void writeMjpegFrame(OutputStream output, byte[] frame) throws IOException {
        output.write(("--" + BOUNDARY + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write("Content-Type: image/jpeg\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write(("Content-Length: " + frame.length + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write(frame);
        output.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.flush();
    }

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

    private static String nonEmpty(String s) {
        return (s != null && !s.trim().isEmpty()) ? s.trim() : null;
    }
}
