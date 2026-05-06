import { useState, useRef, useEffect, useCallback } from 'react'
import Modal from './Modal'

interface GridGroupModalProps {
  isOpen: boolean
  onClose: () => void
  onCreate: (name: string) => void
}

function GridGroupModal({ isOpen, onClose, onCreate }: GridGroupModalProps) {
  const [groupName, setGroupName] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (isOpen) {
      setGroupName('')
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
    }
  }, [handleSubmit])

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Create Group"
      backdropClassName="grid-group-modal-overlay"
      className="grid-group-modal"
      footer={
        <div className="modal-actions">
          <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleSubmit}>Create</button>
        </div>
      }
    >
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
    </Modal>
  )
}

export default GridGroupModal
