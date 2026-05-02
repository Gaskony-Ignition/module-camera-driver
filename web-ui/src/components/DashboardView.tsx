import {
  Camera, Activity, Video, Wifi, RefreshCw, Eye, Grid2x2
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import PageHeader from './PageHeader'
import type { HealthData } from '../types/device'
import './DashboardView.css'

interface DashboardViewProps {
  health: HealthData | null
  onViewChange: (view: string) => void
}

interface StatCard {
  label: string
  value: number | string
  sub: string
  icon: LucideIcon
  valueColour?: string
}

function DashboardView({ health, onViewChange }: DashboardViewProps) {
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

      <div className="dashboard-quick-actions">
        <button className="btn btn-ghost" onClick={() => onViewChange('dashboard')}>
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
