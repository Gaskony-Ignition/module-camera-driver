import { Cpu, MemoryStick, Camera, Play, Circle, ImageIcon, Radio } from 'lucide-react'
import type { HealthData } from '../types/device'
import './StatusBar.css'

interface StatusBarProps {
  health: HealthData | null
}

function StatusBar({ health }: StatusBarProps) {
  const getUsageColour = (pct: number) => {
    if (pct < 50) return 'var(--success)'
    if (pct < 75) return 'var(--warning)'
    return 'var(--error)'
  }

  const cpuPct = health?.cpuPercent ?? 0
  const ramPct = health?.ramPercent ?? 0
  const ramMb = health?.ramUsedMb ?? 0

  const snapshotPct = health && health.maxSnapshots > 0
    ? (health.activeSnapshots / health.maxSnapshots) * 100
    : 0

  const streamPct = health && health.maxStreams > 0
    ? (health.activeStreams / health.maxStreams) * 100
    : 0

  return (
    <div className="global-status-bar">
      <div className="global-status-bar-left">
        <div className="gsb-metric" title={`CPU: ${cpuPct.toFixed(1)}%`}>
          <Cpu size={11} />
          <div className="gsb-bar">
            <div
              className="gsb-bar-fill"
              style={{ width: `${Math.min(cpuPct, 100)}%`, background: getUsageColour(cpuPct) }}
            />
          </div>
          <span className="gsb-value">{cpuPct.toFixed(0)}%</span>
        </div>
        <div className="gsb-metric" title={`JVM heap: ${ramMb} MB (${ramPct.toFixed(1)}% of max heap)`}>
          <MemoryStick size={11} />
          <div className="gsb-bar">
            <div
              className="gsb-bar-fill"
              style={{ width: `${Math.min(ramPct, 100)}%`, background: getUsageColour(ramPct) }}
            />
          </div>
          <span className="gsb-value">{ramMb} MB heap</span>
        </div>
      </div>
      <div className="global-status-bar-right">
        <span className="global-status-bar-item">
          <Camera size={11} />
          <span>Devices: <strong>{health?.deviceCount ?? '-'}</strong></span>
        </span>
        <span className="global-status-bar-separator">|</span>
        <span className="global-status-bar-item">
          <Play size={11} />
          <span>Running: <strong>{health?.runningCount ?? '-'}</strong></span>
        </span>
        <span className="global-status-bar-separator">|</span>
        <span className="global-status-bar-item">
          <span>go2rtc:</span>
          <Circle
            size={6}
            fill={health?.go2rtcAvailable ? 'var(--success)' : 'var(--error)'}
            stroke="none"
          />
        </span>
        <span className="global-status-bar-separator">|</span>
        <span className="global-status-bar-item" title={`${health?.activeSnapshots ?? 0} / ${health?.maxSnapshots ?? 0}`}>
          <ImageIcon size={11} />
          <span>Snapshots: {health?.activeSnapshots ?? 0}</span>
          <span className="gsb-mini-bar">
            <span
              className="gsb-mini-bar-fill"
              style={{ width: `${Math.min(snapshotPct, 100)}%`, background: getUsageColour(snapshotPct) }}
            />
          </span>
        </span>
        <span className="global-status-bar-separator">|</span>
        <span className="global-status-bar-item" title={`${health?.activeStreams ?? 0} / ${health?.maxStreams ?? 0}`}>
          <Radio size={11} />
          <span>Streams: {health?.activeStreams ?? 0}</span>
          <span className="gsb-mini-bar">
            <span
              className="gsb-mini-bar-fill"
              style={{ width: `${Math.min(streamPct, 100)}%`, background: getUsageColour(streamPct) }}
            />
          </span>
        </span>
      </div>
    </div>
  )
}

export default StatusBar
