import { useState, useCallback, useRef, useEffect } from 'react'
import { Camera } from 'lucide-react'
import { API_ENDPOINTS } from './constants/api'
import { apiFetch } from './utils/apiClient'
import type { HealthData } from './types/device'
import Sidebar from './components/Sidebar'
import StatusBar from './components/StatusBar'
import DashboardView from './components/DashboardView'
import CamerasView from './components/CamerasView'
import LiveGridView from './components/LiveGridView'
import DiagnosticsView from './components/DiagnosticsView'
import ErrorBoundary from './components/ErrorBoundary'
import './App.scss'

type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'auth_required'

const STORAGE_KEY = 'camera-driver-active-view'

function App() {
  const [status, setStatus] = useState<ConnectionStatus>('connecting')
  const [health, setHealth] = useState<HealthData | null>(null)
  const [activeView, setActiveView] = useState<string>(() => {
    const stored = localStorage.getItem(STORAGE_KEY) || 'dashboard'
    return stored === 'logs' ? 'diagnostics' : stored
  })
  const healthTimerRef = useRef<number | null>(null)
  const attemptsRef = useRef(0)
  const intervalRef = useRef(5000)

  const checkHealth = useCallback(async () => {
    try {
      const res = await apiFetch(API_ENDPOINTS.health)

      if (res.status === 401) {
        setStatus('auth_required')
        intervalRef.current = 10000
      } else if (res.ok) {
        const data: HealthData = await res.json()
        setHealth(data)
        setStatus('connected')
        attemptsRef.current = 0
        intervalRef.current = 5000
      } else {
        throw new Error(`HTTP ${res.status}`)
      }
    } catch {
      attemptsRef.current++
      setStatus(attemptsRef.current > 2 ? 'disconnected' : 'connecting')
      intervalRef.current = Math.min(5000 * Math.pow(2, attemptsRef.current), 30000)
    }

    if (healthTimerRef.current) {
      window.clearTimeout(healthTimerRef.current)
    }
    healthTimerRef.current = window.setTimeout(checkHealth, intervalRef.current)
  }, [])

  useEffect(() => {
    checkHealth()
    return () => {
      if (healthTimerRef.current) {
        window.clearTimeout(healthTimerRef.current)
      }
    }
  }, [checkHealth])

  const handleViewChange = useCallback((view: string) => {
    setActiveView(view)
    localStorage.setItem(STORAGE_KEY, view)
  }, [])

  if (status === 'auth_required') {
    return (
      <div className="app-wrapper">
        <div className="app-container">
          <div className="auth-required-overlay">
            <div className="auth-required-card">
              <div className="auth-module-identity">
                <Camera size={32} />
                <span>Camera Driver</span>
              </div>
              <div className="auth-divider" />
              <div className="auth-icon">&#128274;</div>
              <h2>Authentication Required</h2>
              <p>You must be logged in to the Ignition Gateway to use this module.</p>
              <a href="/web/login" target="_top" className="auth-login-btn">
                Log In to Gateway
              </a>
            </div>
          </div>
        </div>
      </div>
    )
  }

  const renderActiveView = () => {
    switch (activeView) {
      case 'cameras':
        return <CamerasView onViewChange={handleViewChange} />
      case 'grid':
        return <LiveGridView />
      case 'diagnostics':
        return <DiagnosticsView />
      default:
        return <DashboardView health={health} onViewChange={handleViewChange} />
    }
  }

  return (
    <ErrorBoundary>
      <div className="app-wrapper">
        {status === 'disconnected' && (
          <div className="connection-banner">
            Unable to connect to Camera Driver gateway. Retrying...
          </div>
        )}
        <div className="app-outer-layout">
          <Sidebar
            activeView={activeView}
            onViewChange={handleViewChange}
            moduleVersion={health?.version}
          />
          <div className="app-content-area">
            <div className="content-area">
              {renderActiveView()}
            </div>
            <StatusBar health={health} />
          </div>
        </div>
      </div>
    </ErrorBoundary>
  )
}

export default App
