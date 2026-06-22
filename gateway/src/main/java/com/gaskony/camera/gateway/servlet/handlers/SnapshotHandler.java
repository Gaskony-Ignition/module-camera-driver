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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles snapshot image requests for Camera devices.
 * URL: /data/camera-driver/snapshot?device=DeviceName&profile=ProfileToken
 *
 * Concurrent requests for the same device are coalesced: only one HTTP fetch is
 * dispatched to the camera while duplicates wait and reuse the result. The most
 * recent result is also cached for SNAPSHOT_CACHE_TTL_MS so subsequent polls by
 * multiple Perspective components don't hit the camera redundantly.
 */
public class SnapshotHandler extends BaseHandler {

    private static final int MAX_CONCURRENT_SNAPSHOTS = 50;
    private static final AtomicInteger activeSnapshots = new AtomicInteger(0);

    // Snapshot result cache: reuse a recent frame for concurrent / closely-spaced polls
    private static final long SNAPSHOT_CACHE_TTL_MS = 4_000;
    private static final ConcurrentHashMap<String, byte[]> cachedSnapshots = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> cacheTimestamps = new ConcurrentHashMap<>();

    // Coalescing map: only one in-flight fetch per device+profile at a time
    private static final ConcurrentHashMap<String, CompletableFuture<byte[]>> pendingFetches = new ConcurrentHashMap<>();

    // Per-camera metrics
    private static final ConcurrentHashMap<String, Long> lastFetchDurationMs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastFetchTimestampMs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> fetchTotals = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> fetchErrors = new ConcurrentHashMap<>();

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

    public static void invalidateCache(String deviceName) {
        String prefix = deviceName + ":";
        cachedSnapshots.keySet().removeIf(k -> k.startsWith(prefix));
        cacheTimestamps.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Returns the set of device names that have been tracked (ever received a snapshot request). */
    public static Set<String> getTrackedDevices() {
        return fetchTotals.keySet();
    }

    /** Returns a metrics snapshot for one device: lastDurationMs, lastTimestampMs, totalFetches, errors. */
    public static Map<String, Object> getDeviceMetrics(String deviceName) {
        Map<String, Object> m = new HashMap<>();
        m.put("lastFetchDurationMs", lastFetchDurationMs.getOrDefault(deviceName, -1L));
        m.put("lastFetchTimestampMs", lastFetchTimestampMs.getOrDefault(deviceName, -1L));
        AtomicLong totals = fetchTotals.get(deviceName);
        m.put("totalFetches", totals != null ? totals.get() : 0L);
        AtomicLong errors = fetchErrors.get(deviceName);
        m.put("fetchErrors", errors != null ? errors.get() : 0L);
        return m;
    }

    public Object handle(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Snapshot request received");

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

            // Resolve and validate ONVIF profile token before computing cache key
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
            }

            final String resolvedProfile = profileToken;
            String cacheKey = deviceName + ":" + (resolvedProfile != null ? resolvedProfile : "");

            // 1. Serve from recent cache if available
            Long cachedAt = cacheTimestamps.get(cacheKey);
            byte[] cached = cachedSnapshots.get(cacheKey);
            if (cached != null && cachedAt != null
                    && System.currentTimeMillis() - cachedAt < SNAPSHOT_CACHE_TTL_MS) {
                sendResponse(response, requestContext, cached, deviceName, resolvedProfile, startTime);
                return null;
            }

            // 2. Coalesce concurrent requests: only one fetch per device+profile at a time
            CompletableFuture<byte[]> mine = new CompletableFuture<>();
            CompletableFuture<byte[]> racing = pendingFetches.putIfAbsent(cacheKey, mine);

            byte[] snapshotBytes;
            if (racing != null) {
                // Another fetch is already in flight — join it instead of hitting the camera twice
                try {
                    snapshotBytes = racing.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    response.sendError(503, "Snapshot fetch failed: " + e.getCause().getMessage());
                    return null;
                } catch (TimeoutException e) {
                    response.sendError(503, "Snapshot fetch timed out");
                    return null;
                }
            } else {
                // We won the race — perform the actual camera fetch
                try {
                    snapshotBytes = fetchSnapshotBytes(device, deviceName, resolvedProfile);
                    cachedSnapshots.put(cacheKey, snapshotBytes);
                    cacheTimestamps.put(cacheKey, System.currentTimeMillis());
                    mine.complete(snapshotBytes);
                } catch (IOException e) {
                    mine.completeExceptionally(e);
                    response.sendError(500, e.getMessage());
                    return null;
                } finally {
                    pendingFetches.remove(cacheKey, mine);
                }
            }

            sendResponse(response, requestContext, snapshotBytes, deviceName, resolvedProfile, startTime);

        } finally {
            activeSnapshots.decrementAndGet();
        }

        return null;
    }

    private byte[] fetchSnapshotBytes(CameraDevice device, String deviceName, String profileToken)
            throws IOException {

        long fetchStart = System.currentTimeMillis();
        byte[] snapshotBytes = null;

        try {
            // Try ONVIF snapshot first
            if (device.isOnvifAvailable() && device.getClient() != null) {
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
                fetchErrors.computeIfAbsent(deviceName, k -> new AtomicLong()).incrementAndGet();
                throw new IOException(
                    "No snapshot source available. Configure a snapshot URL or ensure go2rtc with ffmpeg is running.");
            }

            // Validate JPEG content
            boolean isJpeg = snapshotBytes.length >= 3 &&
                    (snapshotBytes[0] & 0xFF) == 0xFF &&
                    (snapshotBytes[1] & 0xFF) == 0xD8 &&
                    (snapshotBytes[2] & 0xFF) == 0xFF;

            String contentStart = new String(
                snapshotBytes, 0, Math.min(100, snapshotBytes.length), java.nio.charset.StandardCharsets.UTF_8);
            boolean isHtml = contentStart.toLowerCase().contains("<!doctype") ||
                    contentStart.toLowerCase().contains("<html");

            if (!isJpeg || isHtml) {
                logger.error("Camera returned {} instead of JPEG for device: {}",
                    isHtml ? "HTML content" : "non-JPEG content", deviceName);
                fetchErrors.computeIfAbsent(deviceName, k -> new AtomicLong()).incrementAndGet();
                throw new IOException("Camera returned non-JPEG content instead of snapshot image");
            }

            // Record successful fetch metrics
            long duration = System.currentTimeMillis() - fetchStart;
            lastFetchDurationMs.put(deviceName, duration);
            lastFetchTimestampMs.put(deviceName, System.currentTimeMillis());
            fetchTotals.computeIfAbsent(deviceName, k -> new AtomicLong()).incrementAndGet();

            return snapshotBytes;

        } catch (IOException e) {
            // Ensure error is counted even if thrown from a path that didn't increment above
            // (the above branches already call incrementAndGet before throw, this is a safety net)
            throw e;
        }
    }

    private void sendResponse(HttpServletResponse response, RequestContext requestContext,
            byte[] snapshotBytes, String deviceName, String profileToken, long startTime)
            throws IOException {

        response.setContentType("image/jpeg");
        response.setContentLength(snapshotBytes.length);
        String origin = requestContext.getRequest().getHeader("Origin");
        CorsManager.setStreamingHeaders(response, origin, requestContext.getRequest());

        try {
            response.getOutputStream().write(snapshotBytes);
            response.getOutputStream().flush();
        } catch (IOException e) {
            logger.debug("Client disconnected during snapshot delivery for device: {}", deviceName);
            return;
        }

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Snapshot delivered - device: {}, profile: {}, size: {} bytes, duration: {} ms",
            deviceName, profileToken, snapshotBytes.length, duration);
    }
}
