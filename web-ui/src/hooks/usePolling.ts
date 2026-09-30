import { useEffect, useRef } from 'react'

/**
 * Generic polling hook that calls `fetchFn` on mount and then
 * every `intervalMs` milliseconds while `enabled` is true.
 *
 * Automatically cleans up the interval on unmount or when
 * `enabled` transitions to false.
 */
export function usePolling(
  fetchFn: () => void | Promise<void>,
  intervalMs: number,
  enabled = true,
): void {
  const savedFn = useRef(fetchFn)

  // Keep the ref current so the interval always calls the latest closure.
  useEffect(() => {
    savedFn.current = fetchFn
  }, [fetchFn])

  useEffect(() => {
    if (!enabled) return

    // Fire immediately on mount / enable.
    savedFn.current()

    const id = setInterval(() => {
      savedFn.current()
    }, intervalMs)

    return () => clearInterval(id)
  }, [intervalMs, enabled])
}
