/**
 * Formatting utilities extracted from connection-browser.html.
 */

/**
 * Converts a duration in milliseconds to a compact human-readable string.
 *
 * Examples: "42s", "3m 12s", "2h 15m", "5d 3h"
 */
export function formatUptime(ms: number): string {
  let s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`

  let m = Math.floor(s / 60)
  s = s % 60
  if (m < 60) return `${m}m ${s}s`

  let h = Math.floor(m / 60)
  m = m % 60
  if (h < 24) return `${h}h ${m}m`

  const days = Math.floor(h / 24)
  h = h % 24
  return `${days}d ${h}h`
}
