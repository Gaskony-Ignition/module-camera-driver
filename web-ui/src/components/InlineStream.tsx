import { useState, useEffect, useRef, useCallback } from 'react'
import { Square, Copy } from 'lucide-react'
import { MsePlayer } from '../utils/MsePlayer'
import './InlineStream.css'

interface InlineStreamProps {
  device: string
  profileUri: string
  snapshotUri: string
  onClose: () => void
}

type StreamState = 'connecting' | 'playing' | 'error'

function InlineStream({ device, profileUri, snapshotUri, onClose }: InlineStreamProps) {
  const [state, setState] = useState<StreamState>('connecting')
  const [errorMessage, setErrorMessage] = useState('')
  const videoRef = useRef<HTMLVideoElement>(null)
  const imgRef = useRef<HTMLImageElement>(null)
  const playerRef = useRef<MsePlayer | null>(null)

  const usesMse = MsePlayer.isSupported()

  const handleError = useCallback((msg: string) => {
    setState('error')
    setErrorMessage(msg)
  }, [])

  useEffect(() => {
    if (!profileUri) {
      handleError('No stream URI available')
      return
    }

    if (usesMse && videoRef.current) {
      const player = new MsePlayer(videoRef.current, handleError)
      playerRef.current = player

      const onPlaying = () => setState('playing')
      videoRef.current.addEventListener('playing', onPlaying, { once: true })

      player.start(profileUri)

      return () => {
        videoRef.current?.removeEventListener('playing', onPlaying)
        player.stop()
        playerRef.current = null
      }
    } else if (imgRef.current) {
      // MJPEG fallback — set img src directly
      imgRef.current.src = profileUri
    }
  }, [profileUri, usesMse, handleError])

  const handleImgLoad = useCallback(() => {
    setState('playing')
  }, [])

  const handleImgError = useCallback(() => {
    handleError('Failed to load stream')
  }, [handleError])

  const copyUri = useCallback((uri: string) => {
    navigator.clipboard.writeText(uri).catch(() => {
      // Clipboard write failed — silently ignore
    })
  }, [])

  const gatewayUrl = profileUri
  const fullGatewayUrl = `${window.location.origin}${gatewayUrl}`

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
        {usesMse ? (
          <video
            ref={videoRef}
            autoPlay
            muted
            playsInline
            style={{ display: state !== 'error' ? 'block' : 'none' }}
          />
        ) : (
          <img
            ref={imgRef}
            alt="Live Stream"
            onLoad={handleImgLoad}
            onError={handleImgError}
            style={{ display: state !== 'error' ? 'block' : 'none' }}
          />
        )}

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
