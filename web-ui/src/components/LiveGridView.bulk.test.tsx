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
