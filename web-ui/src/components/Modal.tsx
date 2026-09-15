import { useEffect, useRef, ReactNode, useId } from 'react'
import { X } from 'lucide-react'

/**
 * Accessible Modal primitive (Sprint 3 P10 baseline).
 *
 * Provides:
 * - role="dialog" + aria-modal + aria-labelledby
 * - Focus trap (focus first focusable on open; Tab cycles within)
 * - Escape closes
 * - Optional click-outside close (default true)
 * - Returns focus to opener on close
 * - Body scroll lock while open
 *
 * Modelled on Python3's PackageInstallModal.tsx.
 */

interface ModalProps {
  isOpen: boolean
  onClose: () => void
  title: ReactNode
  children: ReactNode
  showCloseButton?: boolean
  closeOnBackdrop?: boolean
  className?: string
  backdropClassName?: string
  footer?: ReactNode
}

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'textarea:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ')

function Modal({
  isOpen,
  onClose,
  title,
  children,
  showCloseButton = true,
  closeOnBackdrop = true,
  className = 'modal-dialog',
  backdropClassName = 'modal-overlay',
  footer,
}: ModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const previouslyFocusedRef = useRef<HTMLElement | null>(null)
  const titleId = useId()

  useEffect(() => {
    if (!isOpen) return

    previouslyFocusedRef.current = (document.activeElement as HTMLElement) ?? null
    const previousBodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    const focusFirst = () => {
      const root = dialogRef.current
      if (!root) return
      const first = root.querySelector<HTMLElement>(FOCUSABLE_SELECTOR)
      if (first) {
        first.focus()
      } else {
        root.focus()
      }
    }
    const t = window.setTimeout(focusFirst, 10)

    return () => {
      window.clearTimeout(t)
      document.body.style.overflow = previousBodyOverflow
      const opener = previouslyFocusedRef.current
      if (opener && typeof opener.focus === 'function') {
        try {
          opener.focus()
        } catch {
          // ignore
        }
      }
    }
  }, [isOpen])

  useEffect(() => {
    if (!isOpen) return

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        onClose()
        return
      }
      if (e.key !== 'Tab') return

      const root = dialogRef.current
      if (!root) return

      const focusables = Array.from(
        root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      ).filter(el => el.offsetParent !== null || el === document.activeElement)

      if (focusables.length === 0) {
        e.preventDefault()
        root.focus()
        return
      }

      const first = focusables[0]
      const last = focusables[focusables.length - 1]
      const active = document.activeElement as HTMLElement | null

      if (e.shiftKey) {
        if (active === first || !root.contains(active)) {
          e.preventDefault()
          last.focus()
        }
      } else if (active === last) {
        e.preventDefault()
        first.focus()
      }
    }

    window.addEventListener('keydown', handleKeyDown, true)
    return () => window.removeEventListener('keydown', handleKeyDown, true)
  }, [isOpen, onClose])

  if (!isOpen) return null

  const handleBackdropClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!closeOnBackdrop) return
    if (e.target === e.currentTarget) onClose()
  }

  return (
    <div className={backdropClassName} onClick={handleBackdropClick}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={className}
      >
        <div className="modal-header">
          <h3 id={titleId} className="modal-title">
            {title}
          </h3>
          {showCloseButton && (
            <button
              type="button"
              className="modal-close-btn"
              onClick={onClose}
              aria-label="Close"
            >
              <X size={16} />
            </button>
          )}
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-footer">{footer}</div>}
      </div>
    </div>
  )
}

export default Modal
