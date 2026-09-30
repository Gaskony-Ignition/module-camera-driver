import { useState, useEffect, useCallback } from 'react'
import { Camera, RefreshCw, ImageIcon, Play, Copy } from 'lucide-react'
import PageHeader from './PageHeader'
import InlineStream from './InlineStream'
import { API_ENDPOINTS } from '../constants/api'
import { apiGet } from '../utils/apiClient'
import type { Device, DevicesResponse } from '../types/device'
import './CamerasView.css'

interface CamerasViewProps {
  onViewChange: (view: string) => void
}

/** Which profile is currently streaming (null = none). */
interface ActiveStream {
  deviceName: string
  profileToken: string
  streamUri: string
  snapshotUri: string
}

function CamerasView({ onViewChange: _onViewChange }: CamerasViewProps) {
  const [devices, setDevices] = useState<Device[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [selectedDevice, setSelectedDevice] = useState('__ALL__')
  const [activeStream, setActiveStream] = useState<ActiveStream | null>(null)

  const fetchDevices = useCallback(async () => {
    try {
      setError(null)
      setLoading(true)
      const data = await apiGet<DevicesResponse>(API_ENDPOINTS.devices)
      setDevices(data.devices || [])
    } catch (err) {
      setError(`Failed to load devices: ${(err as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchDevices()
  }, [fetchDevices])

  const handleRefresh = useCallback(() => {
    setActiveStream(null)
    fetchDevices()
  }, [fetchDevices])

  const displayedDevices = selectedDevice === '__ALL__'
    ? devices
    : devices.filter(d => d.name === selectedDevice)

  const handleSnapshot = useCallback((deviceName: string, profileToken: string) => {
    const url = `${API_ENDPOINTS.snapshot(deviceName, profileToken)}&t=${Date.now()}`
    window.open(url, '_blank')
  }, [])

  const handleStream = useCallback((
    deviceName: string,
    profileToken: string,
    streamUri: string,
    snapshotUri: string,
  ) => {
    setActiveStream({ deviceName, profileToken, streamUri, snapshotUri })
  }, [])

  const handleCopyUri = useCallback((uri: string) => {
    navigator.clipboard.writeText(uri).catch(() => {
      // Clipboard write failed — silently ignore
    })
  }, [])

  const handleCloseStream = useCallback(() => {
    setActiveStream(null)
  }, [])

  const getStatusBadgeClass = (status: string): string => {
    if (status === 'Running') return 'badge-running'
    if (status.toLowerCase().includes('error')) return 'badge-error'
    return 'badge-other'
  }

  return (
    <div className="cameras-view">
      <PageHeader
        icon={Camera}
        title="Cameras"
        subtitle={loading ? 'Loading devices...' : `${devices.length} device${devices.length !== 1 ? 's' : ''} configured`}
      >
        <button className="btn btn-ghost btn-sm" onClick={handleRefresh}>
          <RefreshCw size={14} />
          Refresh
        </button>
      </PageHeader>

      {devices.length > 1 && (
        <div className="cameras-toolbar">
          <select
            className="device-dropdown"
            value={selectedDevice}
            onChange={e => setSelectedDevice(e.target.value)}
            aria-label="Select device"
          >
            <option value="__ALL__">All Devices ({devices.length})</option>
            {devices.map(d => (
              <option key={d.name} value={d.name}>
                {d.name} - {d.status || 'Unknown'}
              </option>
            ))}
          </select>
        </div>
      )}

      <div className="cameras-content">
        {loading && (
          <div className="cameras-loading">
            <span className="loading-spinner" />
            <div>Loading devices...</div>
          </div>
        )}

        {!loading && error && (
          <div className="cameras-error">
            <span>{error}</span>
            <button className="error-retry-btn" onClick={handleRefresh}>Retry</button>
          </div>
        )}

        {!loading && !error && devices.length === 0 && (
          <div className="no-devices-msg">
            <Camera size={32} className="no-devices-icon" />
            <p className="no-devices-title">No camera devices configured</p>
            <p>Create device connections in Config &gt; OPC UA &gt; Device Connections</p>
          </div>
        )}

        {!loading && !error && devices.length > 0 && displayedDevices.length === 0 && (
          <div className="no-devices-msg">
            <p>Device &quot;{selectedDevice}&quot; not found</p>
          </div>
        )}

        {!loading && !error && displayedDevices.map(device => (
          <div key={device.name} className="device-card">
            <div className="device-card-header">
              <div className="device-card-header-left">
                <span className="device-card-name">{device.name}</span>
                <span className="device-card-type">
                  {device.type === 'onvif' ? 'ONVIF' : 'Generic'}
                </span>
              </div>
              <span className={`device-card-badge ${getStatusBadgeClass(device.status)}`}>
                {device.status}
              </span>
            </div>

            <div className="device-card-body">
              {(device.manufacturer || device.model || device.firmwareVersion || device.serialNumber) && (
                <div className="device-meta">
                  {device.manufacturer && (
                    <div className="meta-item">
                      <div className="meta-item-label">Manufacturer</div>
                      <div className="meta-item-value">{device.manufacturer}</div>
                    </div>
                  )}
                  {device.model && (
                    <div className="meta-item">
                      <div className="meta-item-label">Model</div>
                      <div className="meta-item-value">{device.model}</div>
                    </div>
                  )}
                  {device.firmwareVersion && (
                    <div className="meta-item">
                      <div className="meta-item-label">Firmware</div>
                      <div className="meta-item-value">{device.firmwareVersion}</div>
                    </div>
                  )}
                  {device.serialNumber && (
                    <div className="meta-item">
                      <div className="meta-item-label">Serial</div>
                      <div className="meta-item-value">{device.serialNumber}</div>
                    </div>
                  )}
                  {device.ip && (
                    <div className="meta-item">
                      <div className="meta-item-label">Address</div>
                      <div className="meta-item-value">
                        {device.ip}{device.port ? `:${device.port}` : ''}
                      </div>
                    </div>
                  )}
                </div>
              )}

              <div className="profiles-section">
                {device.profiles && device.profiles.length > 0 ? (
                  <>
                    <div className="profiles-title">
                      Media Profiles ({device.profiles.length})
                    </div>
                    {device.profiles.map(profile => {
                      const details: string[] = []
                      if (profile.width && profile.height) {
                        details.push(`${profile.width}x${profile.height}`)
                      }
                      if (profile.frameRate) {
                        details.push(`@ ${profile.frameRate}fps`)
                      }
                      if (profile.encoding) {
                        details.push(profile.encoding)
                      }

                      const streamUrl = API_ENDPOINTS.stream(device.name, profile.token)
                      const isStreaming = activeStream !== null
                        && activeStream.deviceName === device.name
                        && activeStream.profileToken === profile.token

                      return (
                        <div key={profile.token}>
                          <div className="profile-item">
                            <div>
                              <div className="profile-name">
                                {profile.name || profile.token}
                              </div>
                              <div className="profile-details">
                                {details.join(' | ') || 'No details'}
                              </div>
                            </div>
                            <div className="profile-actions">
                              <button
                                className="btn btn-sm btn-ghost"
                                onClick={() => handleSnapshot(device.name, profile.token)}
                                title="View snapshot in new tab"
                              >
                                <ImageIcon size={12} />
                                Snapshot
                              </button>
                              <button
                                className="btn btn-sm"
                                onClick={() => handleStream(
                                  device.name,
                                  profile.token,
                                  streamUrl,
                                  profile.snapshotUri || '',
                                )}
                                title="Start live stream"
                              >
                                <Play size={12} />
                                Stream
                              </button>
                              {profile.streamUri && (
                                <button
                                  className="btn btn-sm btn-ghost"
                                  onClick={() => handleCopyUri(profile.streamUri!)}
                                  title="Copy stream URI"
                                >
                                  <Copy size={12} />
                                  Copy URI
                                </button>
                              )}
                            </div>
                          </div>

                          {isStreaming && (
                            <InlineStream
                              device={device.name}
                              profileUri={activeStream.streamUri}
                              snapshotUri={activeStream.snapshotUri}
                              onClose={handleCloseStream}
                              hasPTZ={device.hasPTZ}
                            />
                          )}
                        </div>
                      )
                    })}
                  </>
                ) : (
                  <div className="profiles-empty">No media profiles available</div>
                )}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

export default CamerasView
