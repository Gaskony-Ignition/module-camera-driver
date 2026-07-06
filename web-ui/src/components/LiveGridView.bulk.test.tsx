/**
 * Regression tests for the Live View bulk controls (Stream All / Stop All).
 * v3.1.5 shipped these as a {seq, action} broadcast from LiveGridView into
 * each GridCell; these tests drive the real components and assert the
 * broadcast actually reaches CameraStreamEngine.
 *
 * v3.1.7 added "effective assignments" auto-fill for the default "All Cameras"
 * group (see LiveGridView.getEffectiveAssignments): every connected device not
 * already manually placed is slotted into the next empty cell, alphabetically,
 * up to grid capacity. Custom groups are unaffected. The tests below cover both.
 */
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import LiveGridView from './LiveGridView'
import { apiGet } from '../utils/apiClient'
import type { Device } from '../types/device'

const engineInstances: Array<{ start: ReturnType<typeof vi.fn>; stop: ReturnType<typeof vi.fn> }> = []

vi.mock('../utils/CameraStreamEngine', () => ({
  CameraStreamEngine: vi.fn().mockImplementation(() => {
    const instance = { start: vi.fn(), stop: vi.fn() }
    engineInstances.push(instance)
    return instance
  }),
}))

vi.mock('../utils/apiClient', () => ({
  apiGet: vi.fn().mockResolvedValue({
    devices: [
      {
        name: 'CamA',
        type: 'onvif',
        status: 'Running',
        onvifAvailable: true,
        hasPTZ: true,
        profiles: [{ token: 'p1', name: 'main' }],
      },
      {
        name: 'CamB',
        type: 'generic',
        status: 'Running',
        onvifAvailable: false,
        hasPTZ: false,
        profiles: [],
      },
    ],
  }),
}))

/** Builds a running, streamable Device fixture with the given name. */
function makeDevice(name: string): Device {
  return {
    name,
    type: 'generic',
    status: 'Running',
    hasPTZ: false,
    profiles: [],
  }
}

describe('LiveGridView bulk controls', () => {
  beforeEach(() => {
    engineInstances.length = 0
    localStorage.clear()
    localStorage.setItem('camera-driver-grid-size', '2x2')
    localStorage.setItem(
      'camera-driver-grid-groups',
      JSON.stringify({ __default__: { name: 'All Cameras', assignments: { 0: 'CamA', 1: 'CamB' } } })
    )
  })

  it('Stream All starts an engine for every assigned running camera', async () => {
    render(<LiveGridView />)

    // Devices load asynchronously; the cell selects show them once loaded.
    await waitFor(() => {
      expect(screen.getAllByText('CamA').length).toBeGreaterThan(0)
    })

    fireEvent.click(screen.getByRole('button', { name: /stream all/i }))

    await waitFor(() => {
      expect(engineInstances).toHaveLength(2)
    })
    expect(engineInstances[0].start).toHaveBeenCalledTimes(1)
    expect(engineInstances[1].start).toHaveBeenCalledTimes(1)
  })

  it('Stop All stops every started engine', async () => {
    render(<LiveGridView />)

    await waitFor(() => {
      expect(screen.getAllByText('CamA').length).toBeGreaterThan(0)
    })

    fireEvent.click(screen.getByRole('button', { name: /stream all/i }))
    await waitFor(() => expect(engineInstances).toHaveLength(2))

    fireEvent.click(screen.getByRole('button', { name: /stop all/i }))
    await waitFor(() => {
      expect(engineInstances[0].stop).toHaveBeenCalled()
      expect(engineInstances[1].stop).toHaveBeenCalled()
    })
  })

  it('repeated Stream All presses re-broadcast (new seq) without duplicate starts on streaming cells', async () => {
    render(<LiveGridView />)

    await waitFor(() => {
      expect(screen.getAllByText('CamA').length).toBeGreaterThan(0)
    })

    fireEvent.click(screen.getByRole('button', { name: /stream all/i }))
    await waitFor(() => expect(engineInstances).toHaveLength(2))

    fireEvent.click(screen.getByRole('button', { name: /stream all/i }))
    // Cells already streaming must not create fresh engines.
    expect(engineInstances).toHaveLength(2)
  })

  /**
   * Regression test for fix #1 (HIGH, reproduction-confirmed): GridCell is keyed by
   * `${activeGroup}-${gridSize}-${i}`, so a grid-resize remounts every cell from scratch.
   * Before the fix, GridCell's lastBulkSeqRef always started at 0, so a nonzero bulkCommand.seq
   * already in effect looked "new" to the freshly-mounted cell and got replayed -- silently
   * auto-starting a stream the user never asked for on the new layout. Reproduced by: click
   * Stream All, then resize the grid (which is exactly what pressing "3" does) -- both new
   * cells auto-started engines. Asserts no NEW engine.start() calls happen purely from the
   * remount; only an actual fresh Stream All click after the resize should start anything.
   */
  it('a grid-size change (remount via key change) does not replay a stale bulk command', async () => {
    render(<LiveGridView />)

    await waitFor(() => {
      expect(screen.getAllByText('CamA').length).toBeGreaterThan(0)
    })

    fireEvent.click(screen.getByRole('button', { name: /stream all/i }))
    await waitFor(() => expect(engineInstances).toHaveLength(2))
    expect(engineInstances[0].start).toHaveBeenCalledTimes(1)
    expect(engineInstances[1].start).toHaveBeenCalledTimes(1)

    // Resize the grid -- this changes every GridCell's key (`${activeGroup}-${gridSize}-${i}`),
    // unmounting and remounting all cells, exactly like the reproduction's "press 3" step.
    fireEvent.change(screen.getByTitle('Grid size'), { target: { value: '3x3' } })

    // The remounted cells for CamA/CamB must NOT auto-start just from being mounted with the
    // still-nonzero bulkCommand.seq already in effect -- no new CameraStreamEngine should be
    // constructed at all, since nothing (button click) actually requested a new stream.
    await waitFor(() => {
      expect(screen.getAllByText('CamA').length).toBeGreaterThan(0)
    })
    expect(engineInstances).toHaveLength(2)

    // A genuine fresh Stream All click after the resize must still work normally.
    fireEvent.click(screen.getByRole('button', { name: /stream all/i }))
    await waitFor(() => expect(engineInstances).toHaveLength(4))
  })
})

describe('LiveGridView effective assignments (default group auto-fill)', () => {
  beforeEach(() => {
    engineInstances.length = 0
    localStorage.clear()
  })

  it('auto-fills every connected device (up to capacity) even when only some cells are manually assigned', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({
      devices: ['CamC', 'CamA', 'CamB', 'CamD', 'CamE'].map(makeDevice),
    })
    localStorage.setItem('camera-driver-grid-size', '2x2') // capacity 4
    localStorage.setItem(
      'camera-driver-grid-groups',
      // Only 2 of 5 devices manually assigned (k=2 < N=5).
      JSON.stringify({ __default__: { name: 'All Cameras', assignments: { 0: 'CamC', 1: 'CamA' } } })
    )

    const { container } = render(<LiveGridView />)

    await waitFor(() => {
      expect(container.querySelectorAll('.grid-overlay-name')).toHaveLength(4)
    })

    // Manual placements preserved; remaining capacity (2 cells) auto-filled from the
    // unplaced devices (CamB, CamD, CamE) in alphabetical order -> CamB, CamD. CamE is
    // beyond the 4-cell capacity and must not appear.
    const names = Array.from(container.querySelectorAll('.grid-overlay-name')).map(el => el.textContent)
    expect(names).toEqual(['CamC', 'CamA', 'CamB', 'CamD'])
    // CamE is beyond the 4-cell capacity: confirmed above by `names` not containing it
    // (a plain queryByText would throw here since "CamE" also appears in every unused
    // per-cell <option>).

    fireEvent.click(screen.getByRole('button', { name: /stream all/i }))
    await waitFor(() => expect(engineInstances).toHaveLength(4))
    engineInstances.forEach(engine => expect(engine.start).toHaveBeenCalledTimes(1))
  })

  it('does not auto-fill a custom (non-default) group -- only manually-assigned cells start', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({
      devices: ['CamA', 'CamB', 'CamC', 'CamD', 'CamE'].map(makeDevice),
    })
    localStorage.setItem('camera-driver-grid-size', '2x2') // capacity 4
    localStorage.setItem('camera-driver-grid-group', 'group-custom')
    localStorage.setItem(
      'camera-driver-grid-groups',
      JSON.stringify({
        'group-custom': { name: 'Custom Group', assignments: { 0: 'CamA', 2: 'CamC' } },
      })
    )

    const { container } = render(<LiveGridView />)

    await waitFor(() => {
      expect(container.querySelectorAll('.grid-overlay-name')).toHaveLength(2)
    })
    const names = Array.from(container.querySelectorAll('.grid-overlay-name')).map(el => el.textContent)
    expect(names).toEqual(['CamA', 'CamC'])

    fireEvent.click(screen.getByRole('button', { name: /stream all/i }))
    await waitFor(() => expect(engineInstances).toHaveLength(2))
  })

  it('auto-fill order is stable: alphabetical by device name, independent of API order', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({
      // Deliberately out-of-order from the API.
      devices: ['CamC', 'CamA', 'CamB'].map(makeDevice),
    })
    localStorage.setItem('camera-driver-grid-size', '2x2') // capacity 4, no manual assignments
    localStorage.setItem(
      'camera-driver-grid-groups',
      JSON.stringify({ __default__: { name: 'All Cameras', assignments: {} } })
    )

    const { container } = render(<LiveGridView />)

    await waitFor(() => {
      expect(container.querySelectorAll('.grid-overlay-name')).toHaveLength(3)
    })
    const names = Array.from(container.querySelectorAll('.grid-overlay-name')).map(el => el.textContent)
    expect(names).toEqual(['CamA', 'CamB', 'CamC'])
  })

  it('devices beyond grid capacity are not shown', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({
      devices: ['CamA', 'CamB', 'CamC', 'CamD', 'CamE'].map(makeDevice),
    })
    localStorage.setItem('camera-driver-grid-size', '1x1') // capacity 1
    localStorage.setItem(
      'camera-driver-grid-groups',
      JSON.stringify({ __default__: { name: 'All Cameras', assignments: {} } })
    )

    const { container } = render(<LiveGridView />)

    await waitFor(() => {
      expect(container.querySelectorAll('.grid-overlay-name')).toHaveLength(1)
    })
    expect(container.querySelector('.grid-overlay-name')?.textContent).toBe('CamA')

    fireEvent.click(screen.getByRole('button', { name: /stream all/i }))
    await waitFor(() => expect(engineInstances).toHaveLength(1))
  })

  /**
   * Regression test for fix #3 (MEDIUM): a stored manual assignment pointing at a device
   * that has since been deleted/renamed used to still count as "placed" forever, permanently
   * excluding its cell from auto-fill even though it renders as an empty-looking cell (no
   * matching device to select). The fix treats a stale assignment as empty for auto-fill
   * purposes in the default group, without deleting the stored value (the camera might
   * reconnect later under the same name).
   */
  it('a stale manual assignment to a deleted device frees its cell for auto-fill (default group)', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({
      // CamA was manually assigned to cell 0 but no longer exists; CamB still does; CamC is
      // a newly-connected device that should be able to claim CamA's now-stale slot.
      devices: ['CamB', 'CamC'].map(makeDevice),
    })
    localStorage.setItem('camera-driver-grid-size', '2x2') // capacity 4
    localStorage.setItem(
      'camera-driver-grid-groups',
      JSON.stringify({ __default__: { name: 'All Cameras', assignments: { 0: 'CamA', 1: 'CamB' } } })
    )

    const { container } = render(<LiveGridView />)

    await waitFor(() => {
      expect(container.querySelectorAll('.grid-overlay-name')).toHaveLength(2)
    })
    // Cell 1 keeps its manual CamB placement; cell 0's stale CamA slot is freed and
    // auto-filled with the only unplaced device, CamC -- not left empty forever.
    const names = Array.from(container.querySelectorAll('.grid-overlay-name')).map(el => el.textContent)
    expect(names).toEqual(['CamC', 'CamB'])

    // The stored assignment itself must be untouched (not deleted) -- confirmed indirectly:
    // Stream All must only start the 2 currently-resolvable cameras, not error on the stale one.
    fireEvent.click(screen.getByRole('button', { name: /stream all/i }))
    await waitFor(() => expect(engineInstances).toHaveLength(2))
  })

  it('explicitly clearing an auto-filled cell keeps it empty instead of immediately re-filling it', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({
      devices: ['CamA', 'CamB'].map(makeDevice),
    })
    localStorage.setItem('camera-driver-grid-size', '1x1') // capacity 1
    localStorage.setItem(
      'camera-driver-grid-groups',
      JSON.stringify({ __default__: { name: 'All Cameras', assignments: {} } })
    )

    const { container } = render(<LiveGridView />)

    // Cell 0 auto-fills with CamA (alphabetically first).
    await waitFor(() => {
      expect(container.querySelectorAll('.grid-overlay-name')).toHaveLength(1)
    })
    expect(container.querySelector('.grid-overlay-name')?.textContent).toBe('CamA')

    // Explicitly clear the only cell via its dropdown.
    const select = screen.getByTitle('Select camera') as HTMLSelectElement
    fireEvent.change(select, { target: { value: '' } })

    // It must stay empty on re-render, not immediately auto-refill with CamA.
    await waitFor(() => {
      expect(container.querySelectorAll('.grid-overlay-name')).toHaveLength(0)
    })
    expect(screen.getByText('Select a camera')).toBeInTheDocument()
  })
})
