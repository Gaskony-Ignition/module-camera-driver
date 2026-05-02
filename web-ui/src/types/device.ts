/**
 * TypeScript interfaces for Camera Driver data shapes.
 * Derived from the connection-browser.html fetch responses.
 */

/** A camera device returned by GET /data/camera-driver/devices. */
export interface Device {
  name: string
  type: 'onvif' | 'generic'
  status: string
  manufacturer?: string
  model?: string
  firmwareVersion?: string
  serialNumber?: string
  ip?: string
  port?: number
  go2rtcRegistered?: boolean
  profiles: MediaProfile[]
}

/** A media profile within an ONVIF or generic device. */
export interface MediaProfile {
  name: string
  token: string
  encoding?: string
  width?: number
  height?: number
  frameRate?: number
  streamUri?: string
  snapshotUri?: string
}

/** Response from GET /data/camera-driver/devices. */
export interface DevicesResponse {
  devices: Device[]
}

/** Response from GET /data/camera-driver/health. */
export interface HealthData {
  deviceCount: number
  runningCount: number
  onvifDeviceCount: number
  genericCameraCount: number
  go2rtcAvailable: boolean
  cpuPercent: number
  ramPercent: number
  ramUsedMb: number
  activeSnapshots: number
  maxSnapshots: number
  activeStreams: number
  maxStreams: number
  version: string
}

/** Streaming sub-section of DiagnosticsData. */
export interface StreamingInfo {
  activeSnapshots: number
  maxSnapshots: number
  activeStreams: number
  maxStreams: number
  connectedClients: number
}

/** go2rtc process sub-section of DiagnosticsData. */
export interface Go2RtcInfo {
  alive: boolean
  pid: number
  vmRssKb: number
  uptimeMs: number
  restartCount: number
}

/** Per-stream detail within go2rtcStreams. */
export interface StreamDetail {
  name: string
  producerTracks: string[]
  bitrateKbps: number
  consumers: number
  deduplication: boolean
}

/** go2rtc streams sub-section of DiagnosticsData. */
export interface Go2RtcStreamsInfo {
  registeredStreams: number
  activeProducers: number
  activeConsumers: number
  streams: StreamDetail[]
}

/** Gateway / JVM sub-section of DiagnosticsData. */
export interface GatewayInfo {
  heapUsedMb: number
  heapMaxMb: number
  heapPercent: number
  threadCount: number
}

/** Response from GET /data/camera-driver/diagnostics. */
export interface DiagnosticsData {
  streaming: StreamingInfo
  go2rtc: Go2RtcInfo
  go2rtcStreams: Go2RtcStreamsInfo
  gateway: GatewayInfo
}

/** A single log entry from GET /data/camera-driver/logs/gateway. */
export interface LogEntry {
  id: number
  timestampMs: number
  level: 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'
  logger: string
  source: string
  message: string
}

/** Response from GET /data/camera-driver/logs/gateway. */
export interface LogsResponse {
  success: boolean
  entries: LogEntry[]
}

/** A named camera grid group (stored in localStorage). */
export interface GridGroup {
  name: string
  cameras: string[]
}

/** An event entry in the grid view event log. */
export interface GridEvent {
  time: string
  camera: string
  message: string
}
