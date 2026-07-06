import { useState, useEffect, useCallback, useRef } from 'react'
import { Grid2x2, PlayCircle, Square, Camera, Settings } from 'lucide-react'
import PageHeader from './PageHeader'
import GridCell, { GridBulkCommand } from './GridCell'
import GridGroupModal from './GridGroupModal'
import { apiGet } from '../utils/apiClient'
import { API_ENDPOINTS } from '../constants/api'
import type { Device, DevicesResponse, GridEvent } from '../types/device'
import './LiveGridView.css'

type GridSize = '1x1' | '2x2' | '3x3' | '4x4'

const GRID_SIZES: GridSize[] = ['1x1', '2x2', '3x3', '4x4']
const GRID_MAX_EVENTS = 200
const DISPLAY_EVENTS = 20

const STORAGE_KEYS = {
  gridSize: 'camera-driver-grid-size',
  gridGroup: 'camera-driver-grid-group',
  gridGroups: 'camera-driver-grid-groups',
  gridEvents: 'camera-driver-grid-events',
}

const DEFAULT_GROUP_ID = '__default__'

// Sentinel stored per-cell (default group only) meaning "the admin explicitly blanked this
// cell" -- distinct from "never assigned, please auto-fill". Without this marker, clearing an
// auto-filled cell would look like a no-op: the very next render's auto-fill pass would just
// place the next unplaced device straight back into it. Custom groups never use this marker;
// a blank selection there is a plain delete, same as before.
const CLEARED_MARKER = '__cleared__'

interface GroupData {
  name: string
  assignments: Record<number, string>
}

function getGridDimension(size: GridSize): { cols: number; rows: number } {
  const parts = size.split('x')
  return { cols: parseInt(parts[0], 10), rows: parseInt(parts[1], 10) }
}

function LiveGridView() {
  // -- State ---------------------------------------------------------------

  const [devices, setDevices] = useState<Device[]>([])
  const [gridSize, setGridSize] = useState<GridSize>(() => {
    return (localStorage.getItem(STORAGE_KEYS.gridSize) as GridSize) || '2x2'
  })
  const [activeGroup, setActiveGroup] = useState<string>(() => {
    return localStorage.getItem(STORAGE_KEYS.gridGroup) || DEFAULT_GROUP_ID
  })
  const [groups, setGroups] = useState<Record<string, GroupData>>(() => {
    try {
      const stored = localStorage.getItem(STORAGE_KEYS.gridGroups)
      return stored ? JSON.parse(stored) : {}
    } catch {
      return {}
    }
  })
  const [events, setEvents] = useState<GridEvent[]>(() => {
    try {
      const stored = localStorage.getItem(STORAGE_KEYS.gridEvents)
      return stored ? JSON.parse(stored) : []
    } catch {
      return []
    }
  })
  const [modalOpen, setModalOpen] = useState(false)

  const eventsRef = useRef(events)
  eventsRef.current = events

  const groupsRef = useRef(groups)
  groupsRef.current = groups

  // -- Load devices --------------------------------------------------------

  const loadDevices = useCallback(async () => {
    try {
      const data = await apiGet<DevicesResponse>(API_ENDPOINTS.devices)
      setDevices(data.devices || [])
    } catch {
      // silently ignore
    }
  }, [])

  useEffect(() => {
    loadDevices()
  }, [loadDevices])

  // -- Grid size -----------------------------------------------------------

  const changeGridSize = useCallback((size: GridSize) => {
    setGridSize(size)
    localStorage.setItem(STORAGE_KEYS.gridSize, size)
  }, [])

  // Keyboard shortcuts: 1-4 change grid size
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement) return
      const num = parseInt(e.key, 10)
      if (num >= 1 && num <= 4) {
        const size = `${num}x${num}` as GridSize
        changeGridSize(size)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [changeGridSize])

  // -- Group management ----------------------------------------------------

  const getAssignments = useCallback((): Record<number, string> => {
    return groupsRef.current[activeGroup]?.assignments ?? {}
  }, [activeGroup])

  const saveAssignments = useCallback((assignments: Record<number, string>) => {
    setGroups(prev => {
      const updated = {
        ...prev,
        [activeGroup]: {
          name: prev[activeGroup]?.name ?? (activeGroup === DEFAULT_GROUP_ID ? 'All Cameras' : activeGroup),
          assignments,
        },
      }
      localStorage.setItem(STORAGE_KEYS.gridGroups, JSON.stringify(updated))
      return updated
    })
  }, [activeGroup])

  // "Effective" assignments used for RENDERING and for bulk actions (Stream/Stop/Snapshot All).
  // For the default "All Cameras" group, this starts from the manually-stored assignments and
  // then auto-fills every connected device that isn't already placed into the next empty cell,
  // in stable (alphabetical by name) order, up to the current grid capacity. Manual placements
  // (including cells explicitly cleared via CLEARED_MARKER) are never overwritten. Custom groups
  // are returned unchanged -- only manually-assigned cells are populated there.
  //
  // This is deliberately NOT persisted to localStorage: it's re-derived on every render from
  // the stored assignments + the current devices list, so connecting a new camera or resizing
  // the grid makes it appear immediately without ever writing auto-fill guesses back to storage.
  const getEffectiveAssignments = useCallback((): Record<number, string> => {
    const stored = getAssignments()
    if (activeGroup !== DEFAULT_GROUP_ID) {
      return stored
    }

    const dim = getGridDimension(gridSize)
    const totalCells = dim.cols * dim.rows

    const effective: Record<number, string> = {}
    const placedDeviceNames = new Set<string>()
    const emptyCellIndices: number[] = []

    for (let i = 0; i < totalCells; i++) {
      const value = stored[i]
      if (value && value !== CLEARED_MARKER) {
        effective[i] = value
        placedDeviceNames.add(value)
      } else if (value !== CLEARED_MARKER) {
        emptyCellIndices.push(i)
      }
      // value === CLEARED_MARKER: leave this cell out of `effective` (renders blank) and out
      // of `emptyCellIndices` (never auto-filled).
    }

    const unplacedDevices = devices
      .filter(d => !placedDeviceNames.has(d.name))
      .sort((a, b) => a.name.localeCompare(b.name))

    for (const cellIndex of emptyCellIndices) {
      const next = unplacedDevices.shift()
      if (!next) break
      effective[cellIndex] = next.name
    }

    return effective
  }, [activeGroup, devices, gridSize, getAssignments])

  const switchGroup = useCallback((groupId: string) => {
    setActiveGroup(groupId)
    localStorage.setItem(STORAGE_KEYS.gridGroup, groupId)
  }, [])

  const createGroup = useCallback((name: string) => {
    const id = `group-${Date.now()}`
    setGroups(prev => {
      const updated = { ...prev, [id]: { name, assignments: {} } }
      localStorage.setItem(STORAGE_KEYS.gridGroups, JSON.stringify(updated))
      return updated
    })
    switchGroup(id)
    setModalOpen(false)
  }, [switchGroup])

  // -- Camera assignment ---------------------------------------------------

  const handleCameraChange = useCallback((cellIndex: number, deviceName: string) => {
    const assignments = { ...getAssignments() }
    if (deviceName) {
      assignments[cellIndex] = deviceName
    } else if (activeGroup === DEFAULT_GROUP_ID) {
      // See CLEARED_MARKER comment: record the clear explicitly so the auto-fill pass
      // in getEffectiveAssignments() leaves this cell alone instead of re-populating it.
      assignments[cellIndex] = CLEARED_MARKER
    } else {
      delete assignments[cellIndex]
    }
    saveAssignments(assignments)

    if (deviceName) {
      addEvent(deviceName, `Camera assigned to cell ${cellIndex + 1}`)
    }
  }, [getAssignments, saveAssignments, activeGroup])

  // -- Bulk actions --------------------------------------------------------

  // Broadcast a {seq, action} command to every cell; each cell applies it once
  // per seq (starting only assigned, running, not-already-streaming cameras).
  const [bulkCommand, setBulkCommand] = useState<GridBulkCommand>({ seq: 0, action: 'stop' })

  const streamAll = useCallback(() => {
    setBulkCommand(prev => ({ seq: prev.seq + 1, action: 'start' }))
    addEvent('All', 'Stream All requested')
  }, [])

  const stopAll = useCallback(() => {
    setBulkCommand(prev => ({ seq: prev.seq + 1, action: 'stop' }))
    addEvent('All', 'Stop All requested')
  }, [])

  const snapshotAll = useCallback(() => {
    const assignments = getEffectiveAssignments()
    const dim = getGridDimension(gridSize)
    const total = dim.cols * dim.rows
    for (let i = 0; i < total; i++) {
      const deviceName = assignments[i]
      if (deviceName) {
        const device = devices.find(d => d.name === deviceName)
        if (device) {
          const profileToken = device.profiles?.[0]?.token ?? ''
          const url = API_ENDPOINTS.snapshot(device.name, profileToken) + `&t=${Date.now()}`
          window.open(url, '_blank')
        }
      }
    }
    addEvent('All', 'Snapshot All captured')
  }, [getEffectiveAssignments, gridSize, devices])

  // -- Events --------------------------------------------------------------

  const addEvent = useCallback((camera: string, message: string) => {
    const newEvent: GridEvent = {
      time: new Date().toLocaleTimeString(),
      camera,
      message,
    }
    setEvents(prev => {
      const updated = [newEvent, ...prev].slice(0, GRID_MAX_EVENTS)
      localStorage.setItem(STORAGE_KEYS.gridEvents, JSON.stringify(updated))
      return updated
    })
  }, [])

  const handleCellEvent = useCallback((event: GridEvent) => {
    setEvents(prev => {
      const updated = [event, ...prev].slice(0, GRID_MAX_EVENTS)
      localStorage.setItem(STORAGE_KEYS.gridEvents, JSON.stringify(updated))
      return updated
    })
  }, [])

  // -- Render grid cells ---------------------------------------------------

  const dim = getGridDimension(gridSize)
  const totalCells = dim.cols * dim.rows
  const assignments = getEffectiveAssignments()

  const groupKeys = Object.keys(groups).filter(k => k !== DEFAULT_GROUP_ID)

  return (
    <div className="live-grid-view">
      <PageHeader icon={Grid2x2} title="Live View" subtitle="Multi-camera grid display" />

      {/* Toolbar card */}
      <div className="grid-toolbar-card">
        <div className="grid-toolbar">
          <select
            className="grid-select"
            value={gridSize}
            onChange={(e) => changeGridSize(e.target.value as GridSize)}
            title="Grid size"
          >
            {GRID_SIZES.map(s => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
          <select
            className="grid-select"
            value={activeGroup}
            onChange={(e) => switchGroup(e.target.value)}
            title="Camera group"
          >
            <option value={DEFAULT_GROUP_ID}>All Cameras</option>
            {groupKeys.map(k => (
              <option key={k} value={k}>{groups[k].name || k}</option>
            ))}
          </select>
          <button className="btn btn-sm btn-ghost" onClick={streamAll}>
            <PlayCircle size={14} />
            Stream All
          </button>
          <button className="btn btn-sm btn-ghost" onClick={stopAll}>
            <Square size={14} />
            Stop All
          </button>
          <button className="btn btn-sm btn-ghost" onClick={snapshotAll}>
            <Camera size={14} />
            Snapshot All
          </button>
          <div className="grid-toolbar-spacer" />
          <button className="btn btn-sm btn-ghost" onClick={() => setModalOpen(true)}>
            <Settings size={14} />
            Manage Groups
          </button>
        </div>
      </div>

      {/* Camera grid */}
      <div className="grid-container-card">
        <div className={`camera-grid grid-${gridSize}`}>
          {Array.from({ length: totalCells }, (_, i) => (
            <GridCell
              key={`${activeGroup}-${gridSize}-${i}`}
              cellIndex={i}
              devices={devices}
              selectedCamera={assignments[i] || ''}
              onCameraChange={handleCameraChange}
              onEvent={handleCellEvent}
              bulkCommand={bulkCommand}
            />
          ))}
        </div>
      </div>

      {/* Event log */}
      <div className="grid-event-log">
        <div className="grid-event-log-title">Events</div>
        <div className="grid-events-body">
          {events.length === 0 ? (
            <span className="grid-events-empty">No events yet</span>
          ) : (
            events.slice(0, DISPLAY_EVENTS).map((ev, idx) => (
              <div key={idx} className="grid-event">
                <span className="grid-event-time">{ev.time}</span>
                <span className="grid-event-camera">{ev.camera}</span>
                {ev.message}
              </div>
            ))
          )}
        </div>
      </div>

      {/* Group create modal */}
      <GridGroupModal
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        onCreate={createGroup}
      />
    </div>
  )
}

export default LiveGridView
