import { useRef, useEffect, useCallback } from 'react'
import { Play, Square, Camera } from 'lucide-react'
import { API_ENDPOINTS } from '../constants/api'
import { CameraStreamEngine } from '../utils/CameraStreamEngine'
import type { Device, GridEvent } from '../types/device'

interface GridCellProps {
  cellIndex: number
  devices: Device[]
  selectedCamera: string
  onCameraChange: (cellIndex: number, deviceName: string) => void
  onEvent: (event: GridEvent) => void
}

function GridCell({ cellIndex, devices, selectedCamera, onCameraChange, onEvent }: GridCellProps) {
  const device = selectedCamera
    ? devices.find(d => d.name === selectedCamera) ?? null
    : null

  const videoRef = useRef<HTMLVideoElement>(null)
  const imgRef = useRef<HTMLImageElement>(null)
  const engineRef = useRef<CameraStreamEngine | null>(null)
  const streamingRef = useRef(false)
  const snapshotTimerRef = useRef<number | null>(null)

  const statusClass = device
    ? device.status === 'Running'
      ? 'running'
      : device.status?.toLowerCase().includes('error')
        ? 'error'
        : 'offline'
    : ''

  const typeLabel = device?.type === 'onvif' ? 'ONVIF' : 'Generic'

  // -- Snapshot refresh ----------------------------------------------------

  const loadSnapshot = useCallback(() => {
    if (!device || device.status !== 'Running' || streamingRef.current) return
    const profileToken = device.profiles?.[0]?.token ?? ''
    const url = API_ENDPOINTS.snapshot(device.name, profileToken) + `&t=${Date.now()}`
    if (imgRef.current) {
      imgRef.current.src = url
    }
  }, [device])

  useEffect(() => {
    if (device && device.status === 'Running') {
      loadSnapshot()
      snapshotTimerRef.current = window.setInterval(loadSnapshot, 30_000)
    }
    return () => {
      if (snapshotTimerRef.current !== null) {
        clearInterval(snapshotTimerRef.current)
        snapshotTimerRef.current = null
      }
    }
  }, [device, loadSnapshot])

  // Cleanup stream on unmount or camera change
  useEffect(() => {
    return () => {
      stopStream()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedCamera])

  // -- Stream controls -----------------------------------------------------

  const startStream = useCallback(() => {
    if (!device || device.status !== 'Running' || !videoRef.current) return
    const profileToken = device.profiles?.[0]?.token ?? ''

    streamingRef.current = true

    const engine = new CameraStreamEngine(videoRef.current, imgRef.current, {
      deviceName: device.name,
      streamUrl: API_ENDPOINTS.stream(device.name, profileToken, 10),
      webrtcUrl: API_ENDPOINTS.webrtc(device.name),
      snapshotUrl: API_ENDPOINTS.snapshot(device.name, profileToken),
      // Gateway Config UI authenticates via the existing session cookie, not a token header.
      getAuthHeaders: () => undefined,
      transportPreference: 'auto',
      onStatus: (status, detail) => {
        if (status === 'streaming') {
          const showVideo = detail?.transport === 'webrtc' || detail?.transport === 'mse'
          if (videoRef.current) videoRef.current.style.display = showVideo ? 'block' : 'none'
          if (imgRef.current) imgRef.current.style.display = showVideo ? 'none' : 'block'
        } else if (status === 'error') {
          onEvent({
            time: new Date().toLocaleTimeString(),
            camera: device.name,
            message: `Stream error: ${detail?.error ?? 'unknown error'}`,
          })
        }
      },
    })
    engineRef.current = engine
    engine.start()

    onEvent({
      time: new Date().toLocaleTimeString(),
      camera: device.name,
      message: 'Stream started',
    })
  }, [device, onEvent])

  const stopStream = useCallback(() => {
    if (engineRef.current) {
      engineRef.current.stop()
      engineRef.current = null
    }
    if (videoRef.current) {
      videoRef.current.pause()
      videoRef.current.style.display = 'none'
    }
    if (streamingRef.current && device) {
      streamingRef.current = false
      // Restore snapshot
      if (imgRef.current && device.status === 'Running') {
        const profileToken = device.profiles?.[0]?.token ?? ''
        imgRef.current.src = API_ENDPOINTS.snapshot(device.name, profileToken) + `&t=${Date.now()}`
        imgRef.current.style.display = 'block'
      }
      onEvent({
        time: new Date().toLocaleTimeString(),
        camera: device.name,
        message: 'Stream stopped',
      })
    }
    streamingRef.current = false
  }, [device, onEvent])

  const toggleStream = useCallback(() => {
    if (streamingRef.current) {
      stopStream()
    } else {
      startStream()
    }
  }, [startStream, stopStream])

  const captureSnapshot = useCallback(() => {
    if (!device) return
    const profileToken = device.profiles?.[0]?.token ?? ''
    const url = API_ENDPOINTS.snapshot(device.name, profileToken) + `&t=${Date.now()}`
    window.open(url, '_blank')
    onEvent({
      time: new Date().toLocaleTimeString(),
      camera: device.name,
      message: 'Snapshot captured',
    })
  }, [device, onEvent])

  // -- Render --------------------------------------------------------------

  return (
    <div className="grid-cell">
      <div className="grid-cell-header">
        <select
          value={selectedCamera}
          onChange={(e) => onCameraChange(cellIndex, e.target.value)}
          title="Select camera"
        >
          <option value="">-- Select Camera --</option>
          {devices.map(d => (
            <option key={d.name} value={d.name}>{d.name}</option>
          ))}
        </select>
        <div className="grid-cell-actions">
          {device && (
            <>
              <button onClick={toggleStream} title={streamingRef.current ? 'Stop stream' : 'Play stream'}>
                {streamingRef.current
                  ? <Square size={14} />
                  : <Play size={14} />}
              </button>
              <button onClick={captureSnapshot} title="Snapshot">
                <Camera size={14} />
              </button>
            </>
          )}
        </div>
      </div>
      <div className="grid-cell-body">
        {device ? (
          <>
            <span className="grid-overlay-name">{device.name}</span>
            <span className={`grid-overlay-status ${statusClass}`}>{device.status}</span>
            <span className="grid-overlay-type">{typeLabel}</span>
            <img
              ref={imgRef}
              alt=""
              onLoad={() => { if (imgRef.current) imgRef.current.style.display = 'block' }}
              onError={() => { /* silently ignore */ }}
              style={{ display: 'none' }}
            />
            <video
              ref={videoRef}
              autoPlay
              muted
              playsInline
              style={{ display: 'none' }}
            />
            {streamingRef.current && (
              <div className="grid-live-badge">
                <span className="live-dot" />
                LIVE
              </div>
            )}
            {statusClass !== 'running' && (
              <div className="grid-offline-overlay">OFFLINE</div>
            )}
          </>
        ) : (
          <div className="grid-cell-empty">Select a camera</div>
        )}
      </div>
    </div>
  )
}

export default GridCell
