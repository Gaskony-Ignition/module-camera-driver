import {
  Camera, Activity, Video, Wifi, RefreshCw, Eye, Grid2x2,
  Users, Radio, Clock, AlertCircle, CheckCircle2, MemoryStick
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import PageHeader from './PageHeader'
import type { HealthData, MetricsData, CameraMetrics } from '../types/device'
import './DashboardView.css'

interface DashboardViewProps {
  health: HealthData | null
  metrics: MetricsData | null
  onViewChange: (view: string) => void
  onRefresh: () => void
}

interface StatCard {
  label: string
  value: number | string
  sub: string
  icon: LucideIcon
  valueColour?: string
}

function formatBitrate(kbps: number): string {
  if (kbps <= 0) return '—'
  if (kbps >= 1000) return `${(kbps / 1000).toFixed(1)} Mbps`
  return `${kbps} kbps`
}

function formatDuration(ms: number): string {
  if (ms < 0) return '—'
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`
  return `${ms}ms`
}

function formatAge(tsMs: number, serverNowMs: number | undefined): string {
  if (tsMs < 0) return '—'
  if (serverNowMs === undefined) return '—'
  const ageS = Math.round((serverNowMs - tsMs) / 1000)
  if (ageS < 0) return '—'
  if (ageS < 60) return `${ageS}s ago`
  return `${Math.round(ageS / 60)}m ago`
}

function CameraMetricsRow({ name, cam, serverNowMs }: { name: string; cam: CameraMetrics; serverNowMs: number | undefined }) {
  const isStreaming = cam.go2rtcConsumers > 0
  const hasErrors = cam.snapshotErrors > 0
  const tracks = Array.isArray(cam.go2rtcProducerTracks) ? cam.go2rtcProducerTracks : []

  return (
    <tr className="cam-metrics-row">
      <td className="cam-metrics-name">
        <span className={`cam-status-dot ${cam.go2rtcRegistered ? 'active' : 'inactive'}`} />
        {name}
      </td>
      <td>
        <span className={`cam-tag ${isStreaming ? 'active' : ''}`}>
          <Users size={10} />
          {cam.go2rtcConsumers}
        </span>
      </td>
      <td>{formatBitrate(cam.go2rtcBitrateKbps)}</td>
      <td className="cam-tracks">
        {tracks.length > 0
          ? tracks.map((t, i) => <span key={i} className="cam-track-badge">{t}</span>)
          : <span className="cam-muted">—</span>
        }
      </td>
      <td>{formatDuration(cam.snapshotLastDurationMs)}</td>
      <td className="cam-muted">{formatAge(cam.snapshotLastTimestampMs, serverNowMs)}</td>
      <td>
        {hasErrors
          ? <span className="cam-error-badge"><AlertCircle size={11} />{cam.snapshotErrors}</span>
          : <CheckCircle2 size={12} className="cam-ok-icon" />
        }
      </td>
    </tr>
  )
}

function DashboardView({ health, metrics, onViewChange, onRefresh }: DashboardViewProps) {
  const loading = health === null

  const statCards: StatCard[] = loading
    ? [
        { label: 'Total Devices', value: '--', sub: 'loading...', icon: Camera },
        { label: 'Running', value: '--', sub: 'loading...', icon: Activity },
        { label: 'ONVIF', value: '--', sub: 'loading...', icon: Wifi },
        { label: 'Generic', value: '--', sub: 'loading...', icon: Video },
      ]
    : [
        {
          label: 'Total Devices',
          value: health.deviceCount,
          sub: 'Configured connections',
          icon: Camera,
        },
        {
          label: 'Running',
          value: health.runningCount,
          sub: 'Active & connected',
          icon: Activity,
          valueColour: health.runningCount > 0 ? 'var(--success)' : undefined,
        },
        {
          label: 'ONVIF',
          value: health.onvifDeviceCount,
          sub: 'ONVIF protocol cameras',
          icon: Wifi,
        },
        {
          label: 'Generic',
          value: health.genericCameraCount,
          sub: 'RTSP / MJPEG / Snapshot',
          icon: Video,
        },
      ]

  const cameraNames = metrics ? Object.keys(metrics.cameras).sort() : []
  const jvm = metrics?.jvm
  const go2rtcMem = metrics?.go2rtc
  const serverNowMs = metrics?.timestamp

  return (
    <div className="dashboard-view">
      <PageHeader
        icon={Grid2x2}
        title="Dashboard"
        subtitle="Camera Driver module overview"
      />

      <div className="dashboard-stats-grid">
        {statCards.map(card => {
          const Icon = card.icon
          return (
            <div
              key={card.label}
              className={`dashboard-stat-card ${loading ? 'loading' : ''}`}
            >
              <div className="dashboard-stat-card-icon"><Icon size={18} /></div>
              <div className="dashboard-stat-card-label">{card.label}</div>
              <div
                className="dashboard-stat-card-value"
                style={card.valueColour ? { color: card.valueColour } : undefined}
              >
                {card.value}
              </div>
              <div className="dashboard-stat-card-sub">{card.sub}</div>
            </div>
          )
        })}
      </div>

      {/* Memory summary strip */}
      {(jvm || go2rtcMem) && (
        <div className="dashboard-mem-strip">
          {jvm && (
            <span className="dashboard-mem-item" title={`JVM heap: ${jvm.heapUsedMb} MB / ${jvm.heapMaxMb} MB`}>
              <MemoryStick size={12} />
              JVM heap {jvm.heapUsedMb} MB
              <span className="dashboard-mem-bar">
                <span
                  className="dashboard-mem-bar-fill"
                  style={{
                    width: `${Math.min(jvm.heapPercent, 100)}%`,
                    background: jvm.heapPercent > 75 ? 'var(--error)' : jvm.heapPercent > 50 ? 'var(--warning)' : 'var(--success)',
                  }}
                />
              </span>
              {jvm.heapPercent}%
            </span>
          )}
          {go2rtcMem && go2rtcMem.alive && (
            <span className="dashboard-mem-item" title="go2rtc process RSS memory">
              <Radio size={12} />
              go2rtc {go2rtcMem.processMemoryMb} MB RSS
            </span>
          )}
        </div>
      )}

      {/* Per-camera metrics — Frigate-style */}
      {cameraNames.length > 0 && (
        <div className="dashboard-cam-metrics">
          <div className="dashboard-section-title">Camera Resources</div>
          <div className="cam-metrics-table-wrap">
            <table className="cam-metrics-table">
              <thead>
                <tr>
                  <th>Camera</th>
                  <th><Users size={11} /> Viewers</th>
                  <th><Radio size={11} /> Bitrate</th>
                  <th>Tracks</th>
                  <th><Clock size={11} /> Snap latency</th>
                  <th>Last snap</th>
                  <th>Errors</th>
                </tr>
              </thead>
              <tbody>
                {cameraNames.map(name => (
                  <CameraMetricsRow key={name} name={name} cam={metrics!.cameras[name]} serverNowMs={serverNowMs} />
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div className="dashboard-quick-actions">
        <button className="btn btn-ghost" onClick={onRefresh}>
          <RefreshCw size={14} />
          Refresh Devices
        </button>
        <button className="btn btn-ghost" onClick={() => onViewChange('cameras')}>
          <Eye size={14} />
          View Cameras
        </button>
        <button className="btn btn-ghost" onClick={() => onViewChange('grid')}>
          <Grid2x2 size={14} />
          Open Live View
        </button>
      </div>
    </div>
  )
}

export default DashboardView
