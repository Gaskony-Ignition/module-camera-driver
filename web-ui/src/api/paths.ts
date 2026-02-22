/**
 * Camera Driver API path constants — TypeScript mirror of CameraDriverPaths.java.
 * Single source of truth for all fetch() URLs in frontend code.
 *
 * If the mount alias ever changes, update DATA_BASE here and the companion
 * Java constant CameraDriverPaths.MOUNT_ALIAS.
 */
const DATA_BASE = '/data/camera-driver';

export const API = {
    /** Fetch a JPEG snapshot for a device (optionally a specific profile). */
    snapshot: (device: string, profile?: string) => {
        const base = `${DATA_BASE}/snapshot?device=${encodeURIComponent(device)}`;
        return profile ? `${base}&profile=${encodeURIComponent(profile)}` : base;
    },
    /** Open an MSE / MJPEG stream for a device. */
    stream: (device: string) =>
        `${DATA_BASE}/stream?device=${encodeURIComponent(device)}`,
    /** List all registered devices. */
    devices: `${DATA_BASE}/devices`,
    /** Get status for a single named device. */
    deviceStatus: (name: string) =>
        `${DATA_BASE}/device/${encodeURIComponent(name)}/status`,
    /** Module health check (no auth required). */
    health: `${DATA_BASE}/health`,
    /** Diagnostics endpoint (auth required). */
    diagnostics: `${DATA_BASE}/diagnostics`,
    /** Auth status check — always 200, never triggers Basic Auth popup. */
    authStatus: `${DATA_BASE}/auth-status`,
    /** Gateway logs (auth required). */
    logsGateway: `${DATA_BASE}/logs/gateway`,
    /** Connection Browser page. */
    connectionBrowser: `${DATA_BASE}/connection-browser`,
} as const;
