/**
 * API endpoint constants for Camera Driver.
 *
 * These mirror the fetch() calls in the legacy connection-browser.html
 * and must stay in sync with CameraDriverPaths.java and api/paths.ts.
 */

const DATA_BASE = '/data/camera-driver'

export const API_ENDPOINTS = {
  /** GET — module health / status-bar data. */
  health: `${DATA_BASE}/health`,

  /** GET — list all registered camera devices. */
  devices: `${DATA_BASE}/devices`,

  /** GET — diagnostics (streaming, go2rtc, JVM stats). */
  diagnostics: `${DATA_BASE}/diagnostics`,

  /** GET — gateway log entries. Accepts query params: lines, moduleOnly, after, level. */
  logsGateway: `${DATA_BASE}/logs/gateway`,

  /** GET — auth status check (never triggers Basic Auth popup). */
  authStatus: `${DATA_BASE}/auth-status`,

  /** GET — JPEG snapshot for a device + optional profile. */
  snapshot: (device: string, profile?: string) => {
    const base = `${DATA_BASE}/snapshot?device=${encodeURIComponent(device)}`
    return profile ? `${base}&profile=${encodeURIComponent(profile)}` : base
  },

  /** GET — MSE / MJPEG stream for a device + optional profile + fps. */
  stream: (device: string, profile?: string, fps = 10) => {
    let url = `${DATA_BASE}/stream?device=${encodeURIComponent(device)}&fps=${fps}`
    if (profile) url += `&profile=${encodeURIComponent(profile)}`
    return url
  },

  /** POST — WebRTC signaling: raw SDP offer body in, raw SDP answer out. */
  webrtc: (device: string) =>
    `${DATA_BASE}/webrtc?device=${encodeURIComponent(device)}`,

  /** GET — single device status. */
  deviceStatus: (name: string) =>
    `${DATA_BASE}/device/${encodeURIComponent(name)}/status`,

  /** POST — PTZ continuous move. */
  ptzMove: (device: string, pan: number, tilt: number, zoom: number) =>
    `${DATA_BASE}/ptz/move?device=${encodeURIComponent(device)}&pan=${pan}&tilt=${tilt}&zoom=${zoom}`,

  /** POST — PTZ stop. */
  ptzStop: (device: string) =>
    `${DATA_BASE}/ptz/stop?device=${encodeURIComponent(device)}`,

  /** GET — PTZ status. */
  ptzStatus: (device: string) =>
    `${DATA_BASE}/ptz/status?device=${encodeURIComponent(device)}`,
} as const
