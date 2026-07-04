import { useState, useEffect, useRef, useCallback } from 'react'
import { Square, Copy } from 'lucide-react'
import { CameraStreamEngine, type CameraTransport } from '../utils/CameraStreamEngine'
import { API_ENDPOINTS } from '../constants/api'
import PtzPad from './PtzPad'
import './InlineStream.css'

interface InlineStreamProps {
  device: string
  profileUri: string
  snapshotUri: string
  onClose: () => void
  /** Whether this device reported PTZ capability — gates the PtzPad overlay. */
  hasPTZ?: boolean
}

type StreamState = 'connecting' | 'playing' | 'error'

function InlineStream({ device, profileUri, snapshotUri, onClose, hasPTZ }: InlineStreamProps) {
  const [state, setState] = useState<StreamState>('connecting')
  const [errorMessage, setErrorMessage] = useState('')
  const [activeTransport, setActiveTransport] = useState<CameraTransport | null>(null)
  const videoRef = useRef<HTMLVideoElement>(null)
  const imgRef = useRef<HTMLImageElement>(null)
  const engineRef = useRef<CameraStreamEngine | null>(null)

  useEffect(() => {
    if (!profileUri) {
      setState('error')
      setErrorMessage('No stream URI available')
      return
    }
    if (!videoRef.current) return

    // The profile token travels as a query param on the already-built stream URL —
    // reuse it so the snapshot fallback targets the same media profile.
    const profileToken = new URL(profileUri, window.location.origin).searchParams.get('profile') ?? undefined

    setState('connecting')
    setErrorMessage('')
    setActiveTransport(null)

    const engine = new CameraStreamEngine(videoRef.current, imgRef.current, {
      deviceName: device,
      streamUrl: profileUri,
      webrtcUrl: API_ENDPOINTS.webrtc(device),
      snapshotUrl: API_ENDPOINTS.snapshot(device, profileToken),
      // Gateway Config UI authenticates via the existing session cookie, not a token header.
      getAuthHeaders: () => undefined,
      transportPreference: 'auto',
      onStatus: (status, detail) => {
        if (status === 'streaming') {
          setState('playing')
          setActiveTransport(detail?.transport ?? null)
        } else if (status === 'error') {
          setState('error')
          setErrorMessage(detail?.error ?? 'Stream failed')
        }
      },
    })
    engineRef.current = engine
    engine.start()

    return () => {
      engine.stop()
      engineRef.current = null
    }
  }, [device, profileUri])

  const copyUri = useCallback((uri: string) => {
    navigator.clipboard.writeText(uri).catch(() => {
      // Clipboard write failed — silently ignore
    })
  }, [])

  const gatewayUrl = profileUri
  const fullGatewayUrl = `${window.location.origin}${gatewayUrl}`
  const showVideo = activeTransport === 'webrtc' || activeTransport === 'mse'

  return (
    <div className="inline-stream-wrapper">
      <div className="inline-stream-header">
        <span className="live-indicator">
          <span className="live-dot" />
          LIVE - {device}
        </span>
        <button className="btn btn-sm btn-danger" onClick={onClose}>
          <Square size={12} />
          Stop
        </button>
      </div>

      <div className="stream-container">
        <video
          ref={videoRef}
          autoPlay
          muted
          playsInline
          style={{ display: state === 'playing' && showVideo ? 'block' : 'none' }}
        />
        <img
          ref={imgRef}
          alt="Live Stream"
          style={{ display: state === 'playing' && !showVideo ? 'block' : 'none' }}
        />

        {state === 'connecting' && (
          <div className="stream-loading">
            <span className="loading-spinner" />
            Connecting...
          </div>
        )}

        {state === 'error' && (
          <div className="stream-error">
            {errorMessage || 'Stream failed'}
          </div>
        )}

        {hasPTZ && <PtzPad deviceName={device} />}
      </div>

      <div className="stream-uris">
        <div className="uri-row">
          <span className="uri-label-tag">Gateway</span>
          <span className="uri-value">{gatewayUrl}</span>
          <button
            className="copy-btn"
            onClick={() => copyUri(fullGatewayUrl)}
            title="Copy gateway URL"
          >
            <Copy size={10} />
          </button>
        </div>
        {snapshotUri && (
          <div className="uri-row">
            <span className="uri-label-tag">Direct</span>
            <span className="uri-value">{snapshotUri}</span>
            <button
              className="copy-btn"
              onClick={() => copyUri(snapshotUri)}
              title="Copy direct URI"
            >
              <Copy size={10} />
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

export default InlineStream
