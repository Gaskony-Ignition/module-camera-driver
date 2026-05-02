import { useState, useRef, useEffect, useCallback } from 'react'

interface GridGroupModalProps {
  isOpen: boolean
  onClose: () => void
  onCreate: (name: string) => void
}

function GridGroupModal({ isOpen, onClose, onCreate }: GridGroupModalProps) {
  const [groupName, setGroupName] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)

  // Focus input when modal opens
  useEffect(() => {
    if (isOpen) {
      setGroupName('')
      setTimeout(() => inputRef.current?.focus(), 50)
    }
  }, [isOpen])

  const handleSubmit = useCallback(() => {
    const trimmed = groupName.trim()
    if (!trimmed) {
      inputRef.current?.focus()
      return
    }
    onCreate(trimmed)
    setGroupName('')
  }, [groupName, onCreate])

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      handleSubmit()
    } else if (e.key === 'Escape') {
      onClose()
    }
  }, [handleSubmit, onClose])

  const handleOverlayClick = useCallback((e: React.MouseEvent) => {
    if (e.target === e.currentTarget) {
      onClose()
    }
  }, [onClose])

  if (!isOpen) return null

  return (
    <div className="grid-group-modal-overlay" onClick={handleOverlayClick}>
      <div className="grid-group-modal">
        <h3>Create Group</h3>
        <label htmlFor="grid-group-name-input">Group name</label>
        <input
          id="grid-group-name-input"
          ref={inputRef}
          type="text"
          className="form-input"
          placeholder="Enter group name"
          autoComplete="off"
          value={groupName}
          onChange={(e) => setGroupName(e.target.value)}
          onKeyDown={handleKeyDown}
        />
        <div className="modal-actions">
          <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleSubmit}>Create</button>
        </div>
      </div>
    </div>
  )
}

export default GridGroupModal
