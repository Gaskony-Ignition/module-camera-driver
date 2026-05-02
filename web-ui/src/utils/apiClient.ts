/**
 * Thin fetch wrappers with credentials and timeout.
 * Matches the PLC Emulator apiClient pattern.
 */

const DEFAULT_TIMEOUT = 5000

interface ApiFetchOptions extends RequestInit {
  timeout?: number
}

export async function apiFetch(url: string, options: ApiFetchOptions = {}): Promise<Response> {
  const { timeout = DEFAULT_TIMEOUT, ...rest } = options
  return fetch(url, {
    credentials: 'same-origin',
    signal: AbortSignal.timeout(timeout),
    ...rest,
  })
}

export async function apiGet<T>(url: string): Promise<T> {
  const res = await apiFetch(url)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json() as Promise<T>
}

export async function apiPost<T>(url: string, body: unknown): Promise<T> {
  const res = await apiFetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
    body: JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json() as Promise<T>
}
