package com.onvif.driver.common;

/**
 * Single source of truth for all Camera Driver path constants.
 *
 * The mount alias determines the base URL for all routes:
 *   /res/{MOUNT_ALIAS}/*        — static resources (getMountedResourceFolder)
 *   /data/{MOUNT_ALIAS}/*       — authenticated data routes (mountRouteHandlers)
 *
 * Changing MOUNT_ALIAS here propagates to every route registration, link, and tag
 * without requiring edits in multiple files.
 */
public final class CameraDriverPaths {

    /** The mount alias registered in getMountPathAlias(). All routes hang off this. */
    public static final String MOUNT_ALIAS = "camera-driver";

    /** Base path for all authenticated data routes: /data/camera-driver */
    public static final String DATA_BASE = "/data/" + MOUNT_ALIAS;

    // ── Route segments (relative, passed to RouteGroup.newRoute()) ────────────

    public static final String ROUTE_SNAPSHOT          = "/snapshot";
    public static final String ROUTE_STREAM            = "/stream";
    public static final String ROUTE_DEVICES           = "/devices";
    public static final String ROUTE_DEVICE_STATUS     = "/device/:name/status";
    public static final String ROUTE_CONNECTION_BROWSER = "/connection-browser";
    public static final String ROUTE_HEALTH            = "/health";
    public static final String ROUTE_DIAGNOSTICS       = "/diagnostics";
    public static final String ROUTE_PLAYER            = "/player";
    public static final String ROUTE_AUTH_STATUS       = "/auth-status";
    public static final String ROUTE_LOGS_GATEWAY      = "/logs/gateway";
    public static final String ROUTE_MSE_PLAYER_JS     = "/mse-player.js";

    private CameraDriverPaths() {
        throw new UnsupportedOperationException("CameraDriverPaths is a constants class");
    }
}
