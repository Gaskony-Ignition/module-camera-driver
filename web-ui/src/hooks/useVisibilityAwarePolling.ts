import { useEffect, useRef } from 'react'

/**
 * Polling hook that automatically pauses while the document is hidden
 * (`document.visibilityState === 'hidden'`) and resumes (with an immediate
 * fetch) when it becomes visible again.
 *
 * Sprint 3 HIGH-tier perf: stops background tabs from hammering the gateway
 * when the user has switched away.
 */
export function useVisibilityAwarePolling(
  fetchFn: () => void | Promise<void>,
  intervalMs: number,
  opts: { enabled?: boolean; runImmediately?: boolean } = {},
): void {
  const { enabled = true, runImmediately = true } = opts
  const savedFn = useRef(fetchFn)

  useEffect(() => {
    savedFn.current = fetchFn
  }, [fetchFn])

  useEffect(() => {
    if (!enabled) return

    let timerId: number | null = null

    const stop = () => {
      if (timerId !== null) {
        window.clearInterval(timerId)
        timerId = null
      }
    }

    const start = () => {
      if (timerId !== null) return
      timerId = window.setInterval(() => {
        if (document.visibilityState === 'visible') {
          savedFn.current()
        }
      }, intervalMs)
    }

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'hidden') {
        stop()
      } else {
        savedFn.current()
        start()
      }
    }

    if (runImmediately && document.visibilityState === 'visible') {
      savedFn.current()
    }
    if (document.visibilityState === 'visible') {
      start()
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)

    return () => {
      stop()
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [intervalMs, enabled, runImmediately])
}

export default useVisibilityAwarePolling
