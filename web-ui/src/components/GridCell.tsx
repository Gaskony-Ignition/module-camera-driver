import { useRef, useEffect, useCallback, useState } from 'react'
import { Play, Square, Camera } from 'lucide-react'
import { API_ENDPOINTS } from '../constants/api'
import { CameraStreamEngine, transportLabel, describeTransportFallback, type CameraTransport } from '../utils/CameraStreamEngine'
import type { Device, GridEvent } from '../types/device'

/** Bulk command broadcast from LiveGridView's Stream All / Stop All buttons.
 *  seq increments on every press so repeated presses re-trigger the effect. */
export interface GridBulkCommand {
  seq: number
  action: 'start' | 'stop'
}

interface GridCellProps {
  cellIndex: number
  devices: Device[]
  selectedCamera: string
  onCameraChange: (cellIndex: number, deviceName: string) => void
  onEvent: (event: GridEvent) => void
  bulkCommand?: GridBulkCommand
}

function GridCell({ cellIndex, devices, selectedCamera, onCameraChange, onEvent, bulkCommand }: GridCellProps) {
  const device = selectedCamera
    ? devices.find(d => d.name === selectedCamera) ?? null
    : null

  const videoRef = useRef<HTMLVideoElement>(null)
  const imgRef = useRef<HTMLImageElement>(null)
  const engineRef = useRef<CameraStreamEngine | null>(null)
  // React state so the play/stop icon and LIVE badge actually re-render;
  // mirrored in a ref for use inside stable callbacks (snapshot timer).
  const [isStreaming, setIsStreaming] = useState(false)
  const streamingRef = useRef(false)
  const setStreaming = (value: boolean) => {
    streamingRef.current = value
    setIsStreaming(value)
  }
  const snapshotTimerRef = useRef<number | null>(null)
  // Which transport is actually live right now — drives the transport badge and lets a
  // silent WebRTC->MSE->Snapshot fallback surface as an event-log line instead of just
  // "looking laggy". Mirrored in a ref so the onStatus closure always compares against
  // the latest value without re-creating the engine on every transport change.
  const [activeTransport, setActiveTransport] = useState<CameraTransport | null>(null)
  const activeTransportRef = useRef<CameraTransport | null>(null)

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

    setStreaming(true)
    activeTransportRef.current = null
    setActiveTransport(null)

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
          if (detail?.transport) {
            const previous = activeTransportRef.current
            // A transport change mid-session (not the first one reported) means 'auto'
            // silently fell back one rung of the chain — surface it in the event log so
            // it can never masquerade as "just laggy".
            if (previous && previous !== detail.transport) {
              onEvent({
                time: new Date().toLocaleTimeString(),
                camera: device.name,
                message: describeTransportFallback(previous, detail.transport),
              })
            }
            activeTransportRef.current = detail.transport
            setActiveTransport(detail.transport)
          }
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
      // pause() can throw in some environments (e.g. jsdom, or a video element with no
      // attached media yet) -- defensive, consistent with the engine's own try/catch style.
      try { videoRef.current.pause() } catch { /* ignore */ }
      videoRef.current.style.display = 'none'
    }
    if (streamingRef.current && device) {
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
    setStreaming(false)
    activeTransportRef.current = null
    setActiveTransport(null)
  }, [device, onEvent])

  const toggleStream = useCallback(() => {
    if (streamingRef.current) {
      stopStream()
    } else {
      startStream()
    }
  }, [startStream, stopStream])

  // -- Bulk Stream All / Stop All ------------------------------------------
  // LiveGridView broadcasts a {seq, action} command; each cell applies it once
  // per seq. Guarded by a ref so re-renders never replay an old command.
  //
  // Initialised to the MOUNT-TIME command's seq (not 0): LiveGridView keys cells by
  // `${activeGroup}-${gridSize}-${i}`, so a grid-resize or group-switch remounts every
  // cell from scratch. If this started at 0, a previously-applied nonzero seq would look
  // "new" on the fresh mount and get replayed -- silently auto-starting a stream the user
  // never asked for on the new grid layout (regression, reproduction-confirmed: Stream All,
  // then press "3" to resize, both new cells auto-started). Starting from the command
  // that's already live at mount time means only a genuinely NEW seq (a fresh button
  // press after mount) is treated as unapplied.
  const lastBulkSeqRef = useRef(bulkCommand?.seq ?? 0)
  useEffect(() => {
    if (!bulkCommand || bulkCommand.seq === 0 || bulkCommand.seq === lastBulkSeqRef.current) return
    lastBulkSeqRef.current = bulkCommand.seq
    if (bulkCommand.action === 'start') {
      if (!streamingRef.current) startStream()
    } else {
      if (streamingRef.current) stopStream()
    }
  }, [bulkCommand, startStream, stopStream])

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
              <button onClick={toggleStream} title={isStreaming ? 'Stop stream' : 'Play stream'}>
                {isStreaming
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
            {isStreaming && (
              <div className={`grid-live-badge${activeTransport ? ` transport-${activeTransport}` : ''}`}>
                <span className="live-dot" />
                {activeTransport ? transportLabel(activeTransport) : 'LIVE'}
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
