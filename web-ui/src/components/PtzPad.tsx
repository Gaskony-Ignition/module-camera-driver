import { useCallback, useRef } from 'react'
import { ChevronUp, ChevronDown, ChevronLeft, ChevronRight, Square, ZoomIn, ZoomOut } from 'lucide-react'
import { API_ENDPOINTS } from '../constants/api'
import { showToast } from './Toast'
import './PtzPad.css'

interface PtzPadProps {
  /** Device name to target — passed straight through to the ptz/move + ptz/stop endpoints. */
  deviceName: string
}

const PAN_TILT_SPEED = 0.5
const ZOOM_SPEED = 0.5

/**
 * Compact PTZ (pan/tilt/zoom) overlay for the gateway Cameras view.
 *
 * Press-and-hold semantics: pressing a directional/zoom button fires one
 * continuous-move command; releasing (mouse up/leave or touch end) fires stop.
 * Commands are fire-and-forget — the only feedback surfaced to the admin is a
 * one-time toast if the very first command of this mount fails outright
 * (e.g. device has no PTZ, or the ONVIF client isn't initialised yet).
 */
function PtzPad({ deviceName }: PtzPadProps) {
  const firstCommandSettled = useRef(false)

  const reportFirstFailure = useCallback((status: number) => {
    if (firstCommandSettled.current) return
    firstCommandSettled.current = true
    showToast(`PTZ command failed (HTTP ${status})`, 'error')
  }, [])

  const reportFirstSuccess = useCallback(() => {
    firstCommandSettled.current = true
  }, [])

  const move = useCallback((pan: number, tilt: number, zoom: number) => {
    fetch(API_ENDPOINTS.ptzMove(deviceName, pan, tilt, zoom), {
      method: 'POST',
      credentials: 'include',
    }).then(response => {
      if (response.ok) {
        reportFirstSuccess()
      } else {
        reportFirstFailure(response.status)
      }
    }).catch(() => {
      // Fire-and-forget PTZ command — network errors are not surfaced per-press.
    })
  }, [deviceName, reportFirstFailure, reportFirstSuccess])

  const stop = useCallback(() => {
    fetch(API_ENDPOINTS.ptzStop(deviceName), {
      method: 'POST',
      credentials: 'include',
    }).catch(() => {
      // Fire-and-forget PTZ stop.
    })
  }, [deviceName])

  const press = useCallback((pan: number, tilt: number, zoom: number) =>
    (e: React.MouseEvent | React.TouchEvent) => {
      e.preventDefault()
      move(pan, tilt, zoom)
    }, [move])

  const release = useCallback((e: React.MouseEvent | React.TouchEvent) => {
    e.preventDefault()
    stop()
  }, [stop])

  const holdProps = (pan: number, tilt: number, zoom: number) => ({
    onMouseDown: press(pan, tilt, zoom),
    onMouseUp: release,
    onMouseLeave: release,
    onTouchStart: press(pan, tilt, zoom),
    onTouchEnd: release,
  })

  return (
    <div className="ptz-pad" role="group" aria-label={`PTZ controls for ${deviceName}`}>
      <div className="ptz-pad-grid">
        <span className="ptz-btn-spacer" />
        <button
          type="button"
          className="ptz-btn"
          title="Tilt up"
          aria-label="Tilt up"
          {...holdProps(0, PAN_TILT_SPEED, 0)}
        >
          <ChevronUp size={14} />
        </button>
        <span className="ptz-btn-spacer" />

        <button
          type="button"
          className="ptz-btn"
          title="Pan left"
          aria-label="Pan left"
          {...holdProps(-PAN_TILT_SPEED, 0, 0)}
        >
          <ChevronLeft size={14} />
        </button>
        <button
          type="button"
          className="ptz-btn ptz-btn-stop"
          title="Stop"
          aria-label="Stop PTZ movement"
          onClick={stop}
        >
          <Square size={11} />
        </button>
        <button
          type="button"
          className="ptz-btn"
          title="Pan right"
          aria-label="Pan right"
          {...holdProps(PAN_TILT_SPEED, 0, 0)}
        >
          <ChevronRight size={14} />
        </button>

        <span className="ptz-btn-spacer" />
        <button
          type="button"
          className="ptz-btn"
          title="Tilt down"
          aria-label="Tilt down"
          {...holdProps(0, -PAN_TILT_SPEED, 0)}
        >
          <ChevronDown size={14} />
        </button>
        <span className="ptz-btn-spacer" />
      </div>

      <div className="ptz-pad-zoom">
        <button
          type="button"
          className="ptz-btn"
          title="Zoom in"
          aria-label="Zoom in"
          {...holdProps(0, 0, ZOOM_SPEED)}
        >
          <ZoomIn size={13} />
        </button>
        <button
          type="button"
          className="ptz-btn"
          title="Zoom out"
          aria-label="Zoom out"
          {...holdProps(0, 0, -ZOOM_SPEED)}
        >
          <ZoomOut size={13} />
        </button>
      </div>
    </div>
  )
}

export default PtzPad
