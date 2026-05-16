package com.gaskony.camera.gateway.servlet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP rate limiter for Camera Driver HTTP endpoints.
 * Extracted from ONVIFRoutes to enable instance-scoped lifecycle management.
 */
public final class RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    /** Maximum number of requests allowed per IP address per minute. Set high enough
     *  that Perspective component polling (snapshot every 5s × N cameras) never hits it. */
    private static final int MAX_REQUESTS_PER_IP = 600;

    private static final Map<String, AtomicInteger> requestsPerIP = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService rateLimitExecutor =
        Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "CameraDriver-RateLimit-Cleanup");
            t.setDaemon(true);
            return t;
        });

    private RateLimiter() { throw new AssertionError("Utility class"); }

    /**
     * Checks and enforces per-IP rate limiting.
     *
     * @return true if request should proceed, false if rate limit exceeded
     */
    public static boolean checkRateLimit(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String clientIP = getClientIP(request);
        AtomicInteger ipRequests = requestsPerIP.computeIfAbsent(clientIP, k -> new AtomicInteger(0));

        int currentCount = ipRequests.incrementAndGet();
        if (currentCount > MAX_REQUESTS_PER_IP) {
            logger.warn("Rate limit exceeded for IP: {} ({} requests)", clientIP, currentCount);
            response.sendError(429, "Too many requests from your IP address");
            ipRequests.decrementAndGet();
            return false;
        }

        // Safety valve: if map exceeds threshold, clear stale entries to prevent unbounded growth.
        // Each entry represents at most 1 minute of rate-limit state, so clearing is safe.
        if (requestsPerIP.size() > 10_000) {
            logger.warn("Rate limiter IP map exceeded 10,000 entries — clearing stale state");
            requestsPerIP.clear();
        }

        rateLimitExecutor.schedule(() -> {
            ipRequests.decrementAndGet();
            if (ipRequests.get() == 0) {
                requestsPerIP.remove(clientIP);
            }
        }, 60, TimeUnit.SECONDS);

        return true;
    }

    /**
     * Gets the client IP address from the request, handling proxy headers.
     */
    public static String getClientIP(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIP = request.getHeader("X-Real-IP");
        if (xRealIP != null && !xRealIP.isEmpty()) {
            return xRealIP;
        }
        return request.getRemoteAddr();
    }

    /** Returns number of IPs currently tracked (for diagnostics). */
    public static int getConnectedClientCount() {
        return requestsPerIP.size();
    }

    /**
     * Clears all rate limit state. Call during module shutdown.
     */
    public static void reset() {
        requestsPerIP.clear();
    }

    /**
     * Shuts down the rate limiting executor. Call when module is unloaded.
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
}
