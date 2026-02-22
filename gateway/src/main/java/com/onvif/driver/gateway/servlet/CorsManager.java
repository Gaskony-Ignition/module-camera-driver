package com.onvif.driver.gateway.servlet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Manages CORS headers for Camera Driver HTTP endpoints.
 * Validates origins against the gateway's own hostname to prevent cross-origin attacks.
 */
public final class CorsManager {

    private CorsManager() { throw new AssertionError("Utility class"); }

    /**
     * Sets standard no-cache and CORS response headers.
     * Only sets CORS headers if origin is allowed.
     */
    public static void setStreamingHeaders(HttpServletResponse response, String origin,
                                           HttpServletRequest request) {
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setHeader("Connection", "close");
        if (origin != null && isAllowedOrigin(origin, request)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }
    }

    /**
     * Validates if the given origin is allowed for CORS requests.
     * Compares the origin against the gateway's own scheme+host+port.
     * Localhost is always allowed for development.
     */
    public static boolean isAllowedOrigin(String origin, HttpServletRequest request) {
        if (origin == null || origin.isEmpty()) {
            return false;
        }

        // Always allow localhost and loopback for development
        if (origin.contains("localhost") || origin.contains("127.0.0.1")) {
            return true;
        }

        // Compare against gateway's own origin (scheme://hostname[:port])
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int port = request.getServerPort();

        String gatewayOrigin = scheme + "://" + serverName;
        if ((scheme.equals("http") && port != 80) || (scheme.equals("https") && port != 443)) {
            gatewayOrigin += ":" + port;
        }

        return origin.equals(gatewayOrigin);
    }
}
