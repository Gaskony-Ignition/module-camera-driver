import { useState, useCallback } from 'react'

/**
 * React hook that persists state in localStorage.
 *
 * Returns the same `[value, setValue]` tuple as `useState`.
 * On first render the stored value is read from localStorage;
 * if absent or unparseable, `defaultValue` is used instead.
 */
export function useLocalStorage<T>(
  key: string,
  defaultValue: T,
): [T, (value: T | ((prev: T) => T)) => void] {
  const [storedValue, setStoredValue] = useState<T>(() => {
    try {
      const item = localStorage.getItem(key)
      return item !== null ? (JSON.parse(item) as T) : defaultValue
    } catch {
      return defaultValue
    }
  })

  const setValue = useCallback(
    (value: T | ((prev: T) => T)) => {
      setStoredValue((prev) => {
        const next = value instanceof Function ? value(prev) : value
        try {
          localStorage.setItem(key, JSON.stringify(next))
        } catch {
          // Storage full or unavailable — silently degrade.
        }
        return next
      })
    },
    [key],
  )

  return [storedValue, setValue]
}
