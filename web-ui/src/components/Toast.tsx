/**
 * Simple toast notification system.
 *
 * Usage:
 *   import { showToast } from './Toast'
 *   showToast('Camera connected', 'success')
 *   showToast('Connection failed', 'error')
 *   showToast('Refreshing...', 'info')
 */

const TOAST_DURATION = 3000
const CONTAINER_ID = 'camera-driver-toast-container'

function getOrCreateContainer(): HTMLElement {
  let container = document.getElementById(CONTAINER_ID)
  if (!container) {
    container = document.createElement('div')
    container.id = CONTAINER_ID
    // Sprint 3 P10: announce toasts via screen-reader live region.
    container.setAttribute('role', 'status')
    container.setAttribute('aria-live', 'polite')
    container.setAttribute('aria-atomic', 'true')
    Object.assign(container.style, {
      position: 'fixed',
      bottom: '24px',
      left: '50%',
      transform: 'translateX(-50%)',
      zIndex: '1100',
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      gap: '8px',
      pointerEvents: 'none',
    })
    document.body.appendChild(container)
  }
  return container
}

const COLOURS = {
  success: {
    bg: 'var(--accent-secondary-bg, rgba(166, 227, 161, 0.2))',
    border: 'var(--accent-secondary-border, rgba(166, 227, 161, 0.4))',
    text: 'var(--success, #a6e3a1)',
  },
  error: {
    bg: 'var(--accent-error-bg, rgba(243, 139, 168, 0.2))',
    border: 'var(--accent-error-border, rgba(243, 139, 168, 0.4))',
    text: 'var(--error, #f38ba8)',
  },
  info: {
    bg: 'var(--accent-primary-bg, rgba(137, 180, 250, 0.2))',
    border: 'var(--accent-primary-border, rgba(137, 180, 250, 0.4))',
    text: 'var(--accent-primary, #89b4fa)',
  },
} as const

export function showToast(message: string, type: 'success' | 'error' | 'info' = 'success'): void {
  const container = getOrCreateContainer()
  const colours = COLOURS[type]

  const toast = document.createElement('div')
  Object.assign(toast.style, {
    background: colours.bg,
    border: `1px solid ${colours.border}`,
    color: colours.text,
    padding: '8px 20px',
    borderRadius: '6px',
    fontSize: '12px',
    boxShadow: 'var(--shadow-lg, 0 4px 16px rgba(0,0,0,0.5))',
    animation: 'camera-toast-in 0.25s ease-out',
    pointerEvents: 'auto',
    whiteSpace: 'nowrap',
  })
  toast.textContent = message

  // Close button
  const closeBtn = document.createElement('span')
  closeBtn.textContent = '\u00d7'
  Object.assign(closeBtn.style, {
    marginLeft: '10px',
    cursor: 'pointer',
    opacity: '0.6',
  })
  closeBtn.addEventListener('click', () => toast.remove())
  toast.appendChild(closeBtn)

  container.appendChild(toast)

  // Inject keyframes once
  if (!document.getElementById('camera-toast-keyframes')) {
    const style = document.createElement('style')
    style.id = 'camera-toast-keyframes'
    style.textContent = `
      @keyframes camera-toast-in {
        from { opacity: 0; transform: translateY(10px); }
        to { opacity: 1; transform: translateY(0); }
      }
    `
    document.head.appendChild(style)
  }

  setTimeout(() => toast.remove(), TOAST_DURATION)
}
